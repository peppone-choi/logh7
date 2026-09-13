using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Logh7.Server.OriginalGateway;
using Npgsql;

namespace Logh7.Server.Storage;

public sealed record OriginalCommandPointWrite(
    long CharacterId,
    OriginalCommandPointPool Pool,
    uint Cost,
    string RequestFingerprint);

public sealed record OriginalCommandPointState(
    uint Political,
    uint Military,
    uint Direct,
    uint Substitute,
    long AuthorityVersion,
    bool Applied)
{
    /// <summary>The committed charge's event payload, used for the account state hash.</summary>
    public string Payload { get; init; } = string.Empty;
}

public sealed partial class PostgresAccountStore
{
    private sealed record PointRow(uint Political, uint Military, DateTimeOffset AccruedAt,
        long PoliticalDirect, long MilitaryDirect, long PoliticalSubstitute, long MilitarySubstitute);

    private static async Task<PointRow> ReadPointsAsync(NpgsqlConnection connection, NpgsqlTransaction transaction,
        Guid accountId, long characterId, CancellationToken cancellationToken)
    {
        await using var select = new NpgsqlCommand("""
            SELECT pcp, mcp, points_accrued_at, pcp_spent_direct, mcp_spent_direct,
                   pcp_spent_substitute, mcp_spent_substitute
            FROM character WHERE account_id = $1 AND character_id = $2 FOR UPDATE
            """, connection, transaction);
        select.Parameters.AddWithValue(accountId);
        select.Parameters.AddWithValue(characterId);
        await using var reader = await select.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken))
            throw new InvalidOperationException("COMMAND_POINTS_CHARACTER_NOT_OWNED");
        return new((uint)reader.GetInt64(0), (uint)reader.GetInt64(1), reader.GetFieldValue<DateTimeOffset>(2),
            reader.GetInt64(3), reader.GetInt64(4), reader.GetInt64(5), reader.GetInt64(6));
    }

    /// <summary>
    /// Applies elapsed regeneration only. Regeneration is a passive authored
    /// policy effect, not a player command, so it does not advance the account
    /// authority version and writes nothing when no whole interval elapsed.
    /// </summary>
    public async Task<OriginalCommandPointState> AccrueCommandPointsAsync(Guid accountId, long characterId,
        OriginalCommandPointPolicy policy, DateTimeOffset now, bool inTactics, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(policy);
        if (characterId <= 0) throw new ArgumentException("COMMAND_POINTS_INVALID_CHARACTER");
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        var row = await ReadPointsAsync(connection, transaction, accountId, characterId, cancellationToken);
        var political = policy.Accrue(row.Political, row.AccruedAt, now, inTactics);
        var military = policy.Accrue(row.Military, row.AccruedAt, now, inTactics);
        long version;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1", connection, transaction))
        {
            account.Parameters.AddWithValue(accountId);
            version = (long)(await account.ExecuteScalarAsync(cancellationToken)
                ?? throw new InvalidOperationException("COMMAND_POINTS_ACCOUNT_NOT_FOUND"));
        }
        if (political.AccruedAt == row.AccruedAt)
        {
            await transaction.RollbackAsync(cancellationToken);
            return new(row.Political, row.Military, 0, 0, version, false);
        }
        await using (var update = new NpgsqlCommand("""
            UPDATE character SET pcp = $3, mcp = $4, points_accrued_at = $5
            WHERE account_id = $1 AND character_id = $2
            """, connection, transaction))
        {
            update.Parameters.AddWithValue(accountId);
            update.Parameters.AddWithValue(characterId);
            update.Parameters.AddWithValue((long)political.Balance);
            update.Parameters.AddWithValue((long)military.Balance);
            update.Parameters.AddWithValue(political.AccruedAt);
            if (await update.ExecuteNonQueryAsync(cancellationToken) != 1)
                throw new InvalidOperationException("COMMAND_POINTS_UPDATE_FAILED");
        }
        await transaction.CommitAsync(cancellationToken);
        return new(political.Balance, military.Balance, 0, 0, version, political.Granted + military.Granted > 0);
    }

    /// <summary>
    /// Charges one command under the authored policy in a single transaction.
    /// A repeated fingerprint returns the first outcome, and an insufficient
    /// balance changes nothing at all instead of spending a partial amount.
    /// </summary>
    public async Task<OriginalCommandPointState> ChargeCommandPointsAsync(Guid accountId,
        OriginalCommandPointWrite write, OriginalCommandPointPolicy policy, DateTimeOffset now, bool inTactics,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        ArgumentNullException.ThrowIfNull(policy);
        if (write.CharacterId <= 0 || write.RequestFingerprint is not { Length: 64 } ||
            write.RequestFingerprint.Any(c => !Uri.IsHexDigit(c)))
            throw new ArgumentException("COMMAND_POINTS_INVALID_REQUEST");
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long version;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE", connection, transaction))
        {
            account.Parameters.AddWithValue(accountId);
            version = (long)(await account.ExecuteScalarAsync(cancellationToken)
                ?? throw new InvalidOperationException("COMMAND_POINTS_ACCOUNT_NOT_FOUND"));
        }
        var state = await ApplyCommandPointChargeAsync(connection, transaction, accountId, write, policy,
            now, inTactics, checked(version + 1), cancellationToken);
        if (!state.Applied)
        {
            await transaction.CommitAsync(cancellationToken);
            return state;
        }
        var stateHash = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            "logh7-authority-state/command-points-v1|" + accountId.ToString("D") + "|" + state.Payload)));
        await using (var account = new NpgsqlCommand("""
            UPDATE account SET authority_version = $2, authority_state_hash = $3, updated_at = transaction_timestamp()
            WHERE account_id = $1
            """, connection, transaction))
        {
            account.Parameters.AddWithValue(accountId);
            account.Parameters.AddWithValue(state.AuthorityVersion);
            account.Parameters.AddWithValue(stateHash);
            if (await account.ExecuteNonQueryAsync(cancellationToken) != 1)
                throw new InvalidOperationException("COMMAND_POINTS_ACCOUNT_UPDATE_FAILED");
        }
        await transaction.CommitAsync(cancellationToken);
        return state;
    }

    /// <summary>
    /// The charge itself, inside a transaction the caller owns. The caller is
    /// responsible for the account row: a command that already advances the
    /// authority version (a grid move, say) must charge at that same version
    /// rather than bumping it twice for one player action.
    /// </summary>
    private async Task<OriginalCommandPointState> ApplyCommandPointChargeAsync(NpgsqlConnection connection,
        NpgsqlTransaction transaction, Guid accountId, OriginalCommandPointWrite write,
        OriginalCommandPointPolicy policy, DateTimeOffset now, bool inTactics, long version,
        CancellationToken cancellationToken, bool emitDomainEvent = true)
    {
        var row = await ReadPointsAsync(connection, transaction, accountId, write.CharacterId, cancellationToken);
        await using (var replay = new NpgsqlCommand("""
            SELECT character_id, pool, cost, direct, substitute, pcp_after, mcp_after, authority_version
            FROM original_command_point_charge WHERE account_id = $1 AND request_fingerprint = $2
            """, connection, transaction))
        {
            replay.Parameters.AddWithValue(accountId);
            replay.Parameters.AddWithValue(write.RequestFingerprint);
            await using var reader = await replay.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken))
            {
                if (reader.GetInt64(0) != write.CharacterId || reader.GetInt16(1) != (short)write.Pool ||
                    reader.GetInt64(2) != write.Cost)
                    throw new InvalidOperationException("COMMAND_POINTS_REPLAY_CONFLICT");
                var replayed = new OriginalCommandPointState((uint)reader.GetInt64(5), (uint)reader.GetInt64(6),
                    (uint)reader.GetInt64(3), (uint)reader.GetInt64(4), reader.GetInt64(7), false);
                await reader.DisposeAsync();
                return replayed;
            }
        }
        var political = policy.Accrue(row.Political, row.AccruedAt, now, inTactics);
        var military = policy.Accrue(row.Military, row.AccruedAt, now, inTactics);
        var charge = policy.Charge(political.Balance, military.Balance, write.Pool, write.Cost);
        if (!charge.Accepted) throw new InvalidOperationException(charge.ErrorCode ?? "COMMAND_POINTS_INSUFFICIENT");
        var militaryPool = write.Pool == OriginalCommandPointPool.Military;
        var politicalDirect = row.PoliticalDirect + (militaryPool ? 0 : charge.Direct);
        var militaryDirect = row.MilitaryDirect + (militaryPool ? charge.Direct : 0);
        var politicalSubstitute = row.PoliticalSubstitute + (militaryPool ? charge.Substitute : 0);
        var militarySubstitute = row.MilitarySubstitute + (militaryPool ? 0 : charge.Substitute);
        await using (var update = new NpgsqlCommand("""
            UPDATE character SET pcp = $3, mcp = $4, points_accrued_at = $5,
                pcp_spent_direct = $6, mcp_spent_direct = $7,
                pcp_spent_substitute = $8, mcp_spent_substitute = $9
            WHERE account_id = $1 AND character_id = $2
            """, connection, transaction))
        {
            update.Parameters.AddWithValue(accountId);
            update.Parameters.AddWithValue(write.CharacterId);
            update.Parameters.AddWithValue((long)charge.PoliticalAfter);
            update.Parameters.AddWithValue((long)charge.MilitaryAfter);
            update.Parameters.AddWithValue(political.AccruedAt);
            update.Parameters.AddWithValue(politicalDirect);
            update.Parameters.AddWithValue(militaryDirect);
            update.Parameters.AddWithValue(politicalSubstitute);
            update.Parameters.AddWithValue(militarySubstitute);
            if (await update.ExecuteNonQueryAsync(cancellationToken) != 1)
                throw new InvalidOperationException("COMMAND_POINTS_UPDATE_FAILED");
        }
        await using (var ledger = new NpgsqlCommand("""
            INSERT INTO original_command_point_charge(account_id, request_fingerprint, character_id, pool,
                cost, direct, substitute, pcp_after, mcp_after, authority_version)
            VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)
            """, connection, transaction))
        {
            ledger.Parameters.AddWithValue(accountId);
            ledger.Parameters.AddWithValue(write.RequestFingerprint);
            ledger.Parameters.AddWithValue(write.CharacterId);
            ledger.Parameters.AddWithValue((short)write.Pool);
            ledger.Parameters.AddWithValue((long)charge.Cost);
            ledger.Parameters.AddWithValue((long)charge.Direct);
            ledger.Parameters.AddWithValue((long)charge.Substitute);
            ledger.Parameters.AddWithValue((long)charge.PoliticalAfter);
            ledger.Parameters.AddWithValue((long)charge.MilitaryAfter);
            ledger.Parameters.AddWithValue(version);
            await ledger.ExecuteNonQueryAsync(cancellationToken);
        }
        var payload = JsonSerializer.Serialize(new
        {
            characterId = write.CharacterId,
            pool = write.Pool.ToString(),
            cost = charge.Cost,
            direct = charge.Direct,
            substitute = charge.Substitute,
            pcp = charge.PoliticalAfter,
            mcp = charge.MilitaryAfter,
            requestFingerprint = write.RequestFingerprint,
            policy = policy.Approval,
            design = "new",
        });
        // One player action is one authority version, and the event log allows
        // one event per version. A command that already writes its own event at
        // this version carries the charge in that event instead.
        if (emitDomainEvent)
        await using (var domainEvent = new NpgsqlCommand("""
            INSERT INTO domain_event(account_id,aggregate_type,aggregate_id,event_type,payload,authority_version)
            VALUES($1,'character',$2,'OriginalCommandPointsCharged',$3::jsonb,$4)
            """, connection, transaction))
        {
            domainEvent.Parameters.AddWithValue(accountId);
            domainEvent.Parameters.AddWithValue(
                write.CharacterId.ToString(System.Globalization.CultureInfo.InvariantCulture));
            domainEvent.Parameters.AddWithValue(payload);
            domainEvent.Parameters.AddWithValue(version);
            await domainEvent.ExecuteNonQueryAsync(cancellationToken);
        }
        return new(charge.PoliticalAfter, charge.MilitaryAfter, charge.Direct, charge.Substitute, version, true)
        {
            Payload = payload,
        };
    }
}
