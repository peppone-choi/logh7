using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Npgsql;

namespace Logh7.Server.Storage;

public sealed partial class PostgresAccountStore
{
    public async Task<OriginalInjuryReturnStoreResult> ReturnInjuredOriginalUnitAsync(
        Guid accountId, OriginalInjuryReturnWrite write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.DefeatId == Guid.Empty || write.CharacterId <= 0 || write.UnitId == 0 ||
            write.SourceGrid == 0 || write.DestinationGrid == 0 || write.DestinationBase == 0 ||
            write.ExpectedUnitVersion <= 0 || write.Number == 0 ||
            write.Destroyed != write.Number || write.Damaged != write.Number)
            throw new ArgumentException("INJURY_RETURN_INVALID_LOSS",nameof(write));
        await using var connection=await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction=await connection.BeginTransactionAsync(cancellationToken);
        long version;
        await using (var account=new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id=$1 FOR UPDATE",connection,transaction))
        {
            account.Parameters.AddWithValue(accountId);
            version=(long)(await account.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND"));
        }
        OriginalGridUnitRecord unit;
        uint preference;
        string? previousRequestHash;
        var requestHash=Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            "original-injury-return-request/v1|" + JsonSerializer.Serialize(write))));
        await using (var select=new NpgsqlCommand("""
            SELECT u.character_id,u.unit_id,u.authority_card_id,u.current_cell_id,u.authority_version,
                   u.base_id,u.damaged,u.destroyed,u.injury_return_id,u.ship_generation,u.cruising,c.return_base_id,u.injury_return_request_hash,u.mode,u.unit_number,u.supplies,u.morale
            FROM original_grid_unit u JOIN character c USING(account_id,character_id)
            WHERE u.account_id=$1 AND u.character_id=$2 AND u.unit_id=$3
            FOR UPDATE OF u,c
            """,connection,transaction))
        {
            select.Parameters.AddWithValue(accountId);
            select.Parameters.AddWithValue(write.CharacterId);
            select.Parameters.AddWithValue((long)write.UnitId);
            await using var reader=await select.ExecuteReaderAsync(cancellationToken);
            if (!await reader.ReadAsync(cancellationToken))
                throw new InvalidOperationException("INJURY_RETURN_UNIT_NOT_OWNED");
            unit=ReadOriginalGridUnit(reader);
            preference=checked((uint)reader.GetInt64(11));
            previousRequestHash=reader.IsDBNull(12)?null:reader.GetString(12);
        }
        // Recovery clears only the active casualty fields. The old request
        // identity survives so retries cannot re-kill a replacement flagship.
        await using (var archived=new NpgsqlCommand("""
            SELECT injury_request_hash FROM original_flagship_recovery
            WHERE account_id=$1 AND character_id=$2 AND unit_id=$3 AND return_id=$4
            """,connection,transaction))
        {
            archived.Parameters.AddWithValue(accountId); archived.Parameters.AddWithValue(write.CharacterId);
            archived.Parameters.AddWithValue((long)write.UnitId); archived.Parameters.AddWithValue(write.DefeatId);
            if (await archived.ExecuteScalarAsync(cancellationToken) is string archivedHash)
            {
                if (archivedHash != requestHash)
                    throw new InvalidOperationException("INJURY_RETURN_REPLAY_CONFLICT");
                await transaction.CommitAsync(cancellationToken);
                return new(unit,false);
            }
        }
        if (unit.InjuryReturnId == write.DefeatId)
        {
            if (previousRequestHash != requestHash)
                throw new InvalidOperationException("INJURY_RETURN_REPLAY_CONFLICT");
            // Logical replay returns current state; never teleports an already returned unit again.
            await transaction.CommitAsync(cancellationToken);
            return new(unit,false);
        }
        if (unit.InjuryReturnId is not null || unit.CurrentCellId != write.SourceGrid ||
            unit.AuthorityVersion != write.ExpectedUnitVersion)
            throw new InvalidOperationException("INJURY_RETURN_SOURCE_STALE");
        if (preference != write.DestinationBase)
            throw new InvalidOperationException("INJURY_RETURN_PREFERENCE_CHANGED");
        // Historical replays above return current state without applying loss.
        // A new defeat must use this incarnation's persisted complement.
        if (write.Number != unit.UnitNumber)
            throw new InvalidOperationException("INJURY_RETURN_COMPLEMENT_MISMATCH");
        // NEW_DESIGN: a casualty returns inside its selected friendly base.
        // Mode4 is the original client's garrison stance; do not retain the
        // destroyed ship's open-space/combat stance. Historical replays above
        // return current state and must never dock a later incarnation again.
        const byte returnMode=4;
        version=checked(version+1);
        await using (var update=new NpgsqlCommand("""
            UPDATE original_grid_unit SET current_cell_id=$4,base_id=$5,damaged=$6,destroyed=$7,
                injury_return_id=$8,authority_version=$9,injury_return_request_hash=$10,mode=$11,updated_at=transaction_timestamp()
            WHERE account_id=$1 AND character_id=$2 AND unit_id=$3
            """,connection,transaction))
        {
            update.Parameters.AddWithValue(accountId); update.Parameters.AddWithValue(write.CharacterId);
            update.Parameters.AddWithValue((long)write.UnitId); update.Parameters.AddWithValue((long)write.DestinationGrid);
            update.Parameters.AddWithValue((long)write.DestinationBase); update.Parameters.AddWithValue((int)write.Damaged);
            update.Parameters.AddWithValue((int)write.Destroyed); update.Parameters.AddWithValue(write.DefeatId);
            update.Parameters.AddWithValue(version);
            update.Parameters.AddWithValue(requestHash);
            update.Parameters.AddWithValue((short)returnMode);
            if(await update.ExecuteNonQueryAsync(cancellationToken)!=1)
                throw new InvalidOperationException("INJURY_RETURN_UNIT_NOT_OWNED");
        }
        var payload=JsonSerializer.Serialize(new {
            defeatId=write.DefeatId,characterId=write.CharacterId,unitId=write.UnitId,
            sourceGrid=write.SourceGrid,sourceBase=unit.BaseId,destinationGrid=write.DestinationGrid,
            destinationBase=write.DestinationBase,number=write.Number,damaged=write.Damaged,destroyed=write.Destroyed,
            sourceMode=unit.Mode,mode=returnMode
        });
        await using (var record=new NpgsqlCommand("""
            INSERT INTO domain_event(account_id,aggregate_type,aggregate_id,event_type,payload,authority_version)
            VALUES($1,'original-grid-unit',$2,'OriginalUnitInjuryReturned',$3::jsonb,$4)
            """,connection,transaction))
        {
            record.Parameters.AddWithValue(accountId);
            record.Parameters.AddWithValue(write.UnitId.ToString(CultureInfo.InvariantCulture));
            record.Parameters.AddWithValue(payload); record.Parameters.AddWithValue(version);
            await record.ExecuteNonQueryAsync(cancellationToken);
        }
        var canonical=FormattableString.Invariant($"original-injury-return/v1|{accountId:N}|{version}|{payload}");
        var hash=Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(canonical)));
        await using (var update=new NpgsqlCommand("""
            UPDATE account SET authority_version=$2,authority_state_hash=$3,updated_at=transaction_timestamp()
            WHERE account_id=$1
            """,connection,transaction))
        {
            update.Parameters.AddWithValue(accountId); update.Parameters.AddWithValue(version); update.Parameters.AddWithValue(hash);
            if(await update.ExecuteNonQueryAsync(cancellationToken)!=1)
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND");
        }
        await transaction.CommitAsync(cancellationToken);
        return new(unit with {
            CurrentCellId=write.DestinationGrid,BaseId=write.DestinationBase,Damaged=write.Damaged,
            Destroyed=write.Destroyed,InjuryReturnId=write.DefeatId,AuthorityVersion=version,Mode=returnMode
        },true);
    }
}
