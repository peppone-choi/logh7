using System.Text.Json;
using Logh7.Server.Authority;
using Npgsql;

namespace Logh7.Server.Storage;

public sealed partial class PostgresAccountStore
{
    public async Task<OriginalReturnBaseStoreResult> SetOriginalReturnBaseAsync(
        Guid accountId, OriginalReturnBaseWrite write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(write.CharacterId);
        ArgumentException.ThrowIfNullOrWhiteSpace(write.RequestFingerprint);
        if (write.RequestFingerprint.Length != 64 ||
            write.RequestFingerprint.Any(c => c is not (>= '0' and <= '9') and not (>= 'a' and <= 'f')))
            throw new ArgumentException("RETURN_BASE_FINGERPRINT", nameof(write));

        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        // Shared account lock serializes with character deletion and other authority writes.
        long version;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id=$1 FOR UPDATE", connection, transaction))
        {
            account.Parameters.AddWithValue(accountId);
            version = (long)(await account.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND"));
        }
        uint currentBase;
        await using (var character = new NpgsqlCommand(
            "SELECT return_base_id FROM character WHERE account_id=$1 AND character_id=$2 FOR UPDATE",
            connection, transaction))
        {
            character.Parameters.AddWithValue(accountId);
            character.Parameters.AddWithValue(write.CharacterId);
            currentBase = checked((uint)(long)(await character.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("CHARACTER_NOT_FOUND")));
        }
        await using (var replay = new NpgsqlCommand(
            "SELECT character_id,return_base_id FROM original_return_base_request WHERE account_id=$1 AND request_fingerprint=$2",
            connection, transaction))
        {
            replay.Parameters.AddWithValue(accountId);
            replay.Parameters.AddWithValue(write.RequestFingerprint);
            await using var reader = await replay.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken))
            {
                if (reader.GetInt64(0) != write.CharacterId || reader.GetInt64(1) != write.ReturnBaseId)
                    throw new InvalidOperationException("RETURN_BASE_REQUEST_CONFLICT");
                await reader.DisposeAsync();
                await transaction.CommitAsync(cancellationToken);
                // Old A replay after newer B must leave B in effect.
                return new(currentBase, false, version);
            }
        }

        var changed = currentBase != write.ReturnBaseId;
        if (changed)
        {
            version = checked(version + 1);
            await using (var update = new NpgsqlCommand(
                "UPDATE character SET return_base_id=$3,authority_version=$4 WHERE account_id=$1 AND character_id=$2",
                connection, transaction))
            {
                update.Parameters.AddWithValue(accountId);
                update.Parameters.AddWithValue(write.CharacterId);
                update.Parameters.AddWithValue((long)write.ReturnBaseId);
                update.Parameters.AddWithValue(version);
                if (await update.ExecuteNonQueryAsync(cancellationToken) != 1)
                    throw new InvalidOperationException("CHARACTER_NOT_FOUND");
            }
            var payload = JsonSerializer.Serialize(new
            {
                characterId = write.CharacterId, previousBaseId = currentBase,
                returnBaseId = write.ReturnBaseId, requestFingerprint = write.RequestFingerprint
            });
            await using (var record = new NpgsqlCommand(
                "INSERT INTO domain_event(account_id,aggregate_type,aggregate_id,event_type,payload,authority_version) " +
                "VALUES($1,'character',$2,'OriginalReturnBaseChanged',$3::jsonb,$4)", connection, transaction))
            {
                record.Parameters.AddWithValue(accountId);
                record.Parameters.AddWithValue(write.CharacterId.ToString(System.Globalization.CultureInfo.InvariantCulture));
                record.Parameters.AddWithValue(payload);
                record.Parameters.AddWithValue(version);
                await record.ExecuteNonQueryAsync(cancellationToken);
            }
            var hash = AuthorityStateHash.OriginalReturnBaseChanged(accountId, version,
                write.CharacterId, write.ReturnBaseId, write.RequestFingerprint);
            await using (var update = new NpgsqlCommand(
                "UPDATE account SET authority_version=$2,authority_state_hash=$3,updated_at=transaction_timestamp() WHERE account_id=$1",
                connection, transaction))
            {
                update.Parameters.AddWithValue(accountId);
                update.Parameters.AddWithValue(version);
                update.Parameters.AddWithValue(hash);
                if (await update.ExecuteNonQueryAsync(cancellationToken) != 1)
                    throw new InvalidOperationException("ACCOUNT_VERSION_UPDATE_FAILED");
            }
        }
        // Record even an unchanged request: replaying it after another setting must not undo that setting.
        await using (var receipt = new NpgsqlCommand(
            "INSERT INTO original_return_base_request(account_id,request_fingerprint,character_id,return_base_id,authority_version) " +
            "VALUES($1,$2,$3,$4,$5)", connection, transaction))
        {
            receipt.Parameters.AddWithValue(accountId);
            receipt.Parameters.AddWithValue(write.RequestFingerprint);
            receipt.Parameters.AddWithValue(write.CharacterId);
            receipt.Parameters.AddWithValue((long)write.ReturnBaseId);
            receipt.Parameters.AddWithValue(version);
            await receipt.ExecuteNonQueryAsync(cancellationToken);
        }
        await transaction.CommitAsync(cancellationToken);
        return new(write.ReturnBaseId, changed, version);
    }
}
