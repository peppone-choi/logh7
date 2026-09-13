using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Npgsql;

namespace Logh7.Server.Storage;

public sealed record OriginalBaseTravelWrite(string RequestFingerprint, OriginalGridUnitRecord Source,
    uint DestinationGridId, uint DestinationBaseId, DateTimeOffset DueAt, OriginalMoveGridPointCharge Points);
public enum OriginalBaseTravelStatus { Scheduled, Replayed, Rejected }
public readonly record struct OriginalBaseTravelResult(OriginalBaseTravelStatus Status,
    DateTimeOffset? DueAt, long AuthorityVersion, string? ErrorCode);
public readonly record struct OriginalBaseTravelCompletion(string Outcome, bool Updated,
    OriginalGridUnitRecord? Unit, long AuthorityVersion, string? RequestFingerprint = null);
public readonly record struct OriginalBaseTravelDue(Guid AccountId, string RequestFingerprint);

public interface IOriginalBaseTravelStore
{
    Task<OriginalBaseTravelResult> ScheduleOriginalBaseTravelAsync(Guid accountId,
        OriginalBaseTravelWrite write, CancellationToken cancellationToken);
    Task<IReadOnlyList<OriginalBaseTravelDue>> ReadDueOriginalBaseTravelAsync(
        DateTimeOffset now, int limit, CancellationToken cancellationToken);
    Task<OriginalBaseTravelCompletion> CompleteOriginalBaseTravelAsync(Guid accountId,
        string fingerprint, DateTimeOffset now, CancellationToken cancellationToken,
        Func<uint,uint,uint,bool>? destinationIsAvailable = null);
}

public sealed partial class PostgresAccountStore : IOriginalBaseTravelStore
{
    public async Task<IReadOnlyList<OriginalBaseTravelDue>> ReadDueOriginalBaseTravelAsync(
        DateTimeOffset now, int limit, CancellationToken cancellationToken)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(limit, 1);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(limit, 1024);
        await using var query = _dataSource.CreateCommand("""
            SELECT account_id,request_fingerprint FROM original_base_travel_command
            WHERE outcome='pending' AND due_at <= $1
            ORDER BY due_at,account_id,request_fingerprint LIMIT $2
            """);
        query.Parameters.AddWithValue(now.ToUniversalTime());
        query.Parameters.AddWithValue(limit);
        await using var reader = await query.ExecuteReaderAsync(cancellationToken);
        var due = new List<OriginalBaseTravelDue>();
        while (await reader.ReadAsync(cancellationToken))
            due.Add(new(reader.GetGuid(0), reader.GetString(1)));
        return due;
    }

    public async Task<OriginalBaseTravelCompletion> CompleteOriginalBaseTravelAsync(Guid accountId,
        string fingerprint, DateTimeOffset now, CancellationToken cancellationToken,
        Func<uint,uint,uint,bool>? destinationIsAvailable = null)
    {
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        NpgsqlCommand Command(string sql, params object[] values)
        {
            var command = new NpgsqlCommand(sql, connection, transaction);
            foreach (var value in values) command.Parameters.AddWithValue(value);
            return command;
        }
        long version;
        await using (var account = Command(
            "SELECT authority_version FROM account WHERE account_id=$1 FOR UPDATE", accountId))
        {
            var found = await account.ExecuteScalarAsync(cancellationToken);
            if (found is null) return new("not-found", false, null, 0);
            version = (long)found;
        }
        long character, unitId, generation, sourceVersion, grid, sourceBase, destination, resultVersion;
        string outcome;
        DateTimeOffset due;
        await using (var trip = Command("""
            SELECT character_id,unit_id,ship_generation,source_unit_version,grid_id,source_base_id,
                destination_base_id,due_at,outcome,COALESCE(completion_authority_version,authority_version)
            FROM original_base_travel_command WHERE account_id=$1 AND request_fingerprint=$2 FOR UPDATE
            """, accountId, fingerprint))
        {
            await using var reader = await trip.ExecuteReaderAsync(cancellationToken);
            if (!await reader.ReadAsync(cancellationToken)) return new("not-found", false, null, version);
            character = reader.GetInt64(0); unitId = reader.GetInt64(1); generation = reader.GetInt64(2);
            sourceVersion = reader.GetInt64(3); grid = reader.GetInt64(4); sourceBase = reader.GetInt64(5);
            destination = reader.GetInt64(6); due = new DateTimeOffset(reader.GetDateTime(7));
            outcome = reader.GetString(8); resultVersion = reader.GetInt64(9);
        }
        OriginalGridUnitRecord? unit = null;
        await using (var select = Command("""
            SELECT character_id,unit_id,authority_card_id,current_cell_id,authority_version,
                base_id,damaged,destroyed,injury_return_id,ship_generation,cruising,mode,unit_number,supplies,morale
            FROM original_grid_unit WHERE account_id=$1 AND character_id=$2 AND unit_id=$3 FOR UPDATE
            """, accountId, character, unitId))
        {
            await using var reader = await select.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken)) unit = ReadOriginalGridUnit(reader);
        }
        if (outcome != "pending" || now < due)
        {
            await transaction.CommitAsync(cancellationToken);
            return new(outcome, false, unit, resultVersion, fingerprint);
        }
        // Do not resurrect a destroyed/replaced unit or pull back a ship moved by
        // another command. Cancellation preserves casualties, points and location.
        // Any refund policy is separate, unrecovered, and not silently invented.
        var eligible = unit is not null && unit.ShipGeneration == generation &&
            unit.AuthorityVersion == sourceVersion && unit.CurrentCellId == grid && unit.BaseId == sourceBase &&
            unit.InjuryReturnId is null && unit.Destroyed < unit.UnitNumber;
        if (eligible && destinationIsAvailable is not null)
        {
            // Read current faction under the same authority transaction, not from
            // a scheduler snapshot. The host supplies its current immutable catalog.
            await using var actor = Command("SELECT faction FROM character WHERE account_id=$1 AND character_id=$2 FOR UPDATE",
                accountId,character);
            var faction = await actor.ExecuteScalarAsync(cancellationToken);
            eligible = faction is not null && destinationIsAvailable(checked((uint)grid),
                checked((uint)destination),Convert.ToUInt32(faction));
        }
        outcome = eligible ? "completed" : "cancelled";
        var nextVersion = checked(version + 1);
        if (eligible)
        {
            await using var move = Command("""
                UPDATE original_grid_unit SET base_id=$4,mode=4,authority_version=$5,
                    updated_at=transaction_timestamp()
                WHERE account_id=$1 AND character_id=$2 AND unit_id=$3 AND authority_version=$6
                """, accountId, character, unitId, destination, nextVersion, sourceVersion);
            if (await move.ExecuteNonQueryAsync(cancellationToken) != 1)
                throw new InvalidOperationException("BASE_TRAVEL_COMPLETION_UPDATE_FAILED");
            unit = unit! with { BaseId = checked((uint)destination), Mode = 4, AuthorityVersion = nextVersion };
        }
        await using (var finish = Command("""
            UPDATE original_base_travel_command SET outcome=$3,finished_at=$4,completion_authority_version=$5
            WHERE account_id=$1 AND request_fingerprint=$2 AND outcome='pending'
            """, accountId, fingerprint, outcome, now.ToUniversalTime(), nextVersion))
            if (await finish.ExecuteNonQueryAsync(cancellationToken) != 1)
                throw new InvalidOperationException("BASE_TRAVEL_COMPLETION_RECEIPT_FAILED");
        var payload = JsonSerializer.Serialize(new
        {
            characterId = character, unitId, sourceGeneration = generation, sourceUnitVersion = sourceVersion,
            grid, sourceBase, destinationBase = destination, dueAt = due, finishedAt = now,
            outcome, unit, requestFingerprint = fingerprint,
        });
        await using (var domainEvent = Command("""
            INSERT INTO domain_event(account_id,aggregate_type,aggregate_id,event_type,payload,authority_version)
            VALUES($1,'original-grid-unit',$2,'OriginalBaseTravelResolved',$3::jsonb,$4)
            """, accountId, unitId.ToString(System.Globalization.CultureInfo.InvariantCulture), payload, nextVersion))
            await domainEvent.ExecuteNonQueryAsync(cancellationToken);
        var hash = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            "logh7-authority-state/base-travel-resolved-v1|" + accountId.ToString("D") + "|" + payload)));
        await using (var account = Command("""
            UPDATE account SET authority_version=$2,authority_state_hash=$3,updated_at=transaction_timestamp()
            WHERE account_id=$1 AND authority_version=$4
            """, accountId, nextVersion, hash, version))
            if (await account.ExecuteNonQueryAsync(cancellationToken) != 1)
                throw new InvalidOperationException("BASE_TRAVEL_COMPLETION_ACCOUNT_FAILED");
        await transaction.CommitAsync(cancellationToken);
        return new(outcome, true, unit, nextVersion, fingerprint);
    }

    // Internal authority primitive. The wire handler must resolve the destination
    // from the authoritative base catalog and check captain/card/port permission.
    // Neither a raw client balance nor a client deadline is an authority policy.
    public async Task<OriginalBaseTravelResult> ScheduleOriginalBaseTravelAsync(Guid accountId,
        OriginalBaseTravelWrite write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        ArgumentNullException.ThrowIfNull(write.Source);
        ArgumentNullException.ThrowIfNull(write.Points);
        var source = write.Source;
        if (write.RequestFingerprint is not { Length: 64 } ||
            write.RequestFingerprint.Any(c => !(c is >= '0' and <= '9' or >= 'a' and <= 'f')) ||
            source.CharacterId <= 0 || source.UnitId == 0 || source.AuthorityVersion <= 0 ||
            source.ShipGeneration < 0 || write.DestinationBaseId == 0 ||
            write.DestinationGridId == 0 || write.Points.Cost == 0)
            throw new ArgumentException("BASE_TRAVEL_INVALID_REQUEST");

        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        NpgsqlCommand Command(string sql, params object[] values)
        {
            var command = new NpgsqlCommand(sql, connection, transaction);
            foreach (var value in values) command.Parameters.AddWithValue(value);
            return command;
        }
        long version;
        await using (var account = Command(
            "SELECT authority_version FROM account WHERE account_id=$1 FOR UPDATE", accountId))
        {
            var found = await account.ExecuteScalarAsync(cancellationToken);
            if (found is null) return new(OriginalBaseTravelStatus.Rejected, null, 0,
                "BASE_TRAVEL_ACCOUNT_NOT_FOUND");
            version = (long)found;
        }
        async Task<OriginalBaseTravelResult> Reject(string error)
        {
            await transaction.RollbackAsync(cancellationToken);
            return new(OriginalBaseTravelStatus.Rejected, null, version, error);
        }
        OriginalGridUnitRecord unit;
        await using (var select = Command("""
            SELECT character_id,unit_id,authority_card_id,current_cell_id,authority_version,
                base_id,damaged,destroyed,injury_return_id,ship_generation,cruising,mode,unit_number,supplies,morale
            FROM original_grid_unit WHERE account_id=$1 AND character_id=$2 AND unit_id=$3 FOR UPDATE
            """, accountId, source.CharacterId, (long)source.UnitId))
        {
            await using var reader = await select.ExecuteReaderAsync(cancellationToken);
            if (!await reader.ReadAsync(cancellationToken))
            {
                await reader.DisposeAsync();
                return await Reject("BASE_TRAVEL_UNIT_NOT_OWNED");
            }
            unit = ReadOriginalGridUnit(reader);
        }
        // Replay precedes source freshness and candidate deadline checks: a
        // reconnect must neither recharge nor drag the current unit backwards.
        await using (var replay = Command("""
            SELECT t.character_id,t.unit_id,t.grid_id,t.destination_base_id,t.due_at,t.authority_version,
                c.pool,c.cost
            FROM original_base_travel_command t
            LEFT JOIN original_command_point_charge c USING(account_id,request_fingerprint)
            WHERE t.account_id=$1 AND t.request_fingerprint=$2
            """, accountId, write.RequestFingerprint))
        {
            await using var reader = await replay.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken))
            {
                var matches = reader.GetInt64(0) == source.CharacterId &&
                    reader.GetInt64(1) == source.UnitId && reader.GetInt64(2) == write.DestinationGridId &&
                    reader.GetInt64(3) == write.DestinationBaseId && !reader.IsDBNull(6) &&
                    reader.GetInt16(6) == (short)write.Points.Pool && reader.GetInt64(7) == write.Points.Cost;
                var due = new DateTimeOffset(reader.GetDateTime(4));
                var acceptedVersion = reader.GetInt64(5);
                await reader.DisposeAsync();
                if (!matches) return await Reject("BASE_TRAVEL_REPLAY_CONFLICT");
                await transaction.CommitAsync(cancellationToken);
                return new(OriginalBaseTravelStatus.Replayed, due, acceptedVersion, null);
            }
        }
        if (unit != source) return await Reject("BASE_TRAVEL_SOURCE_STALE");
        if (write.DestinationGridId != unit.CurrentCellId || write.DestinationBaseId == unit.BaseId)
            return await Reject("BASE_TRAVEL_DESTINATION_INVALID");
        if (unit.InjuryReturnId is not null || unit.Destroyed >= unit.UnitNumber || write.Points.InTactics)
            return await Reject("BASE_TRAVEL_UNIT_UNAVAILABLE");
        if (write.DueAt < write.Points.Now) return await Reject("BASE_TRAVEL_DEADLINE_INVALID");
        await using (var pending = Command("""
            SELECT 1 FROM original_base_travel_command
            WHERE account_id=$1 AND unit_id=$2 AND outcome='pending'
            """, accountId, (long)unit.UnitId))
            if (await pending.ExecuteScalarAsync(cancellationToken) is not null)
                return await Reject("BASE_TRAVEL_ALREADY_PENDING");

        var nextVersion = checked(version + 1);
        OriginalCommandPointState charged;
        try
        {
            charged = await ApplyCommandPointChargeAsync(connection, transaction, accountId,
                new OriginalCommandPointWrite(unit.CharacterId, write.Points.Pool, write.Points.Cost,
                    write.RequestFingerprint), write.Points.Policy, write.Points.Now, false,
                nextVersion, cancellationToken, emitDomainEvent: false);
        }
        catch (InvalidOperationException error) when (error.Message == "COMMAND_POINTS_INSUFFICIENT")
        {
            return await Reject("BASE_TRAVEL_COMMAND_POINTS_INSUFFICIENT");
        }
        if (!charged.Applied) return await Reject("BASE_TRAVEL_COMMAND_POINTS_REPLAYED");
        await using (var insert = Command("""
            INSERT INTO original_base_travel_command(account_id,request_fingerprint,character_id,unit_id,
                ship_generation,source_unit_version,grid_id,source_base_id,destination_base_id,
                accepted_at,due_at,authority_version)
            VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
            """, accountId, write.RequestFingerprint, unit.CharacterId, (long)unit.UnitId,
            unit.ShipGeneration, unit.AuthorityVersion, (long)unit.CurrentCellId, (long)unit.BaseId,
            (long)write.DestinationBaseId, write.Points.Now.ToUniversalTime(), write.DueAt.ToUniversalTime(), nextVersion))
            await insert.ExecuteNonQueryAsync(cancellationToken);

        var payload = JsonSerializer.Serialize(new
        {
            characterId = unit.CharacterId, unitId = unit.UnitId, shipGeneration = unit.ShipGeneration,
            sourceUnitVersion = unit.AuthorityVersion, grid = unit.CurrentCellId, sourceBase = unit.BaseId,
            destinationBase = write.DestinationBaseId, acceptedAt = write.Points.Now, dueAt = write.DueAt,
            commandPointPool = write.Points.Pool.ToString(), commandPointCost = write.Points.Cost,
            pcp = charged.Political, mcp = charged.Military, requestFingerprint = write.RequestFingerprint,
        });
        await using (var domainEvent = Command("""
            INSERT INTO domain_event(account_id,aggregate_type,aggregate_id,event_type,payload,authority_version)
            VALUES($1,'original-grid-unit',$2,'OriginalBaseTravelScheduled',$3::jsonb,$4)
            """, accountId, unit.UnitId.ToString(System.Globalization.CultureInfo.InvariantCulture), payload, nextVersion))
            await domainEvent.ExecuteNonQueryAsync(cancellationToken);
        var stateHash = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            "logh7-authority-state/base-travel-scheduled-v1|" + accountId.ToString("D") + "|" + payload)));
        await using (var account = Command("""
            UPDATE account SET authority_version=$2,authority_state_hash=$3,updated_at=transaction_timestamp()
            WHERE account_id=$1 AND authority_version=$4
            """, accountId, nextVersion, stateHash, version))
            if (await account.ExecuteNonQueryAsync(cancellationToken) != 1)
                throw new InvalidOperationException("BASE_TRAVEL_ACCOUNT_UPDATE_FAILED");
        await transaction.CommitAsync(cancellationToken);
        return new(OriginalBaseTravelStatus.Scheduled, write.DueAt.ToUniversalTime(), nextVersion, null);
    }
}
