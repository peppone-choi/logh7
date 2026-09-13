using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Logh7.Server.OriginalGateway;
using Npgsql;

namespace Logh7.Server.Storage;

/// <summary>
/// 完全修復 for the player's own flagship. The manual's command table prices the
/// command at 160 points; the pool it is drawn from is INFERRED (a fleet repair
/// is a military act) and the supplies it consumes follow the ordinary-unit
/// repair primitive, which spends the unit's whole stock.
/// </summary>
public sealed record OriginalFlagshipRepairWrite(
    string RequestFingerprint,
    long CharacterId,
    uint UnitId,
    long ExpectedUnitVersion,
    long ExpectedShipGeneration,
    OriginalMoveGridPointCharge Points,
    IReadOnlyList<OriginalFleetUnitRecord>? Escorts = null,
    bool IncludeFlagship = true);

public enum OriginalFlagshipRepairStatus
{
    Repaired,
    Replayed,
    Rejected,
}

public readonly record struct OriginalFlagshipRepairResult(
    OriginalFlagshipRepairStatus Status,
    OriginalGridUnitRecord? Unit,
    long AuthorityVersion,
    string? ErrorCode,
    IReadOnlyList<OriginalFleetUnitRecord>? Escorts = null);

public sealed partial class PostgresAccountStore
{
    public async Task<OriginalFlagshipRepairResult> RepairOwnOriginalFlagshipAsync(
        Guid accountId, OriginalFlagshipRepairWrite write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        var escorts = (write.Escorts ?? []).OrderBy(row => row.UnitId).ToArray();
        if (escorts.Length + (write.IncludeFlagship ? 1 : 0) > OriginalCompletenessRepairCodec.MaximumShips ||
            (!write.IncludeFlagship && escorts.Length == 0) ||
            escorts.Select(row => row.UnitId).Distinct().Count() != escorts.Length ||
            escorts.Any(row => row.UnitId == 0 || row.UnitId == write.UnitId))
            throw new ArgumentException("FLEET_REPAIR_INVALID_SELECTION");
        if (write.CharacterId <= 0 || write.UnitId == 0 || write.ExpectedUnitVersion <= 0 ||
            write.ExpectedShipGeneration < 0 || write.RequestFingerprint is not { Length: 64 } ||
            write.RequestFingerprint.Any(c => !Uri.IsHexDigit(c)))
            throw new ArgumentException("FLAGSHIP_REPAIR_INVALID_REQUEST");
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE", connection, transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken)
                ?? throw new InvalidOperationException("FLAGSHIP_REPAIR_ACCOUNT_NOT_FOUND"));
        }

        OriginalGridUnitRecord unit;
        await using (var select = new NpgsqlCommand("""
            SELECT character_id,unit_id,authority_card_id,current_cell_id,authority_version,
                base_id,damaged,destroyed,injury_return_id,ship_generation,cruising,mode,unit_number,supplies,morale
            FROM original_grid_unit WHERE account_id=$1 AND character_id=$2 AND unit_id=$3 FOR UPDATE
            """, connection, transaction))
        {
            select.Parameters.AddWithValue(accountId);
            select.Parameters.AddWithValue(write.CharacterId);
            select.Parameters.AddWithValue((long)write.UnitId);
            await using var reader = await select.ExecuteReaderAsync(cancellationToken);
            if (!await reader.ReadAsync(cancellationToken))
                throw new InvalidOperationException("FLAGSHIP_REPAIR_UNIT_NOT_OWNED");
            unit = ReadOriginalGridUnit(reader);
        }

        await using (var replay = new NpgsqlCommand("""
            SELECT authority_version FROM original_flagship_repair_command
            WHERE account_id=$1 AND request_fingerprint=$2
            """, connection, transaction))
        {
            replay.Parameters.AddWithValue(accountId);
            replay.Parameters.AddWithValue(write.RequestFingerprint);
            var replayed = await replay.ExecuteScalarAsync(cancellationToken);
            if (replayed is not null)
            {
                await using var selection = new NpgsqlCommand("""
                    SELECT h.character_id,h.unit_id,h.ship_generation,h.include_flagship,e.payload::text
                    FROM original_flagship_repair_command h
                    LEFT JOIN domain_event e ON e.account_id=h.account_id AND e.authority_version=h.authority_version
                        AND e.event_type='OriginalFlagshipRepaired'
                    WHERE h.account_id=$1 AND h.request_fingerprint=$2
                    """,connection,transaction);
                selection.Parameters.AddWithValue(accountId);
                selection.Parameters.AddWithValue(write.RequestFingerprint);
                bool matches;
                await using(var reader=await selection.ExecuteReaderAsync(cancellationToken))
                {
                    matches=await reader.ReadAsync(cancellationToken) &&
                        reader.GetInt64(0)==write.CharacterId && reader.GetInt64(1)==write.UnitId &&
                        reader.GetInt64(2)==write.ExpectedShipGeneration && reader.GetBoolean(3)==write.IncludeFlagship &&
                        !reader.IsDBNull(4);
                    if(matches)
                    {
                        using var receipt=JsonDocument.Parse(reader.GetString(4));
                        // Pre-fleet history had no escorts property and means an empty selection.
                        var savedIds=receipt.RootElement.TryGetProperty("escorts",out var saved)
                            ? saved.EnumerateArray().Select(row=>row.GetProperty("UnitId").GetUInt32()).Order().ToArray()
                            : Array.Empty<uint>();
                        matches=savedIds.SequenceEqual(escorts.Select(row=>row.UnitId));
                    }
                }
                if(!matches)
                    return await RejectAsync(transaction,currentVersion,"FLEET_REPAIR_REPLAY_SELECTION_MISMATCH",unit,cancellationToken);
                await transaction.CommitAsync(cancellationToken);
                // The current row, never the historical one: a later incarnation
                // must not be dragged back to an old repair's outcome.
                return new(OriginalFlagshipRepairStatus.Replayed, unit, (long)replayed, null);
            }
        }

        if (unit.AuthorityVersion != write.ExpectedUnitVersion ||
            unit.ShipGeneration != write.ExpectedShipGeneration)
            return await RejectAsync(transaction, currentVersion, "FLAGSHIP_REPAIR_SOURCE_STALE", unit,
                cancellationToken);
        // A repair happens in a port. Garrisoned at a base is the only stance
        // this authority can express for that today.
        if (unit.BaseId == 0 || unit.Mode != 4)
            return await RejectAsync(transaction, currentVersion, "FLAGSHIP_REPAIR_NOT_IN_PORT", unit,
                cancellationToken);
        if (unit.InjuryReturnId is not null)
            return await RejectAsync(transaction, currentVersion, "FLAGSHIP_REPAIR_UNIT_RECOVERING", unit,
                cancellationToken);
        var repairFlagship = write.IncludeFlagship && unit.Damaged > unit.Destroyed;
        if (!repairFlagship && escorts.Length == 0)
            return await RejectAsync(transaction, currentVersion, "FLAGSHIP_REPAIR_NOTHING_DAMAGED", unit,
                cancellationToken);
        if (repairFlagship && unit.Supplies == 0)
            return await RejectAsync(transaction, currentVersion, "FLAGSHIP_REPAIR_NO_SUPPLIES", unit,
                cancellationToken);

        var nextVersion = checked(currentVersion + 1);
        OriginalCommandPointState charged;
        try
        {
            charged = await ApplyCommandPointChargeAsync(connection, transaction, accountId,
                new OriginalCommandPointWrite(write.CharacterId, write.Points.Pool, write.Points.Cost,
                    write.RequestFingerprint),
                write.Points.Policy, write.Points.Now, write.Points.InTactics, nextVersion, cancellationToken,
                emitDomainEvent: false);
        }
        catch (InvalidOperationException error) when (error.Message == "COMMAND_POINTS_INSUFFICIENT")
        {
            return await RejectAsync(transaction, currentVersion,
                "FLAGSHIP_REPAIR_COMMAND_POINTS_INSUFFICIENT", unit, cancellationToken);
        }
        if (!charged.Applied)
            return await RejectAsync(transaction, currentVersion, "FLAGSHIP_REPAIR_COMMAND_POINTS_REPLAYED",
                unit, cancellationToken);

        // Only the surviving damaged hulls come back; destroyed ones stay
        // destroyed, and the repair spends the unit's whole supply stock, which
        // is what the ordinary-unit repair primitive already does.
        var after = unit with
        {
            Damaged = repairFlagship ? unit.Destroyed : unit.Damaged,
            Supplies = repairFlagship ? 0 : unit.Supplies,
            AuthorityVersion = nextVersion,
        };
        await using (var update = new NpgsqlCommand("""
            UPDATE original_grid_unit SET damaged=$4,supplies=$7,authority_version=$5,
                updated_at=transaction_timestamp()
            WHERE account_id=$1 AND character_id=$2 AND unit_id=$3 AND authority_version=$6
            """, connection, transaction))
        {
            update.Parameters.AddWithValue(accountId);
            update.Parameters.AddWithValue(write.CharacterId);
            update.Parameters.AddWithValue((long)write.UnitId);
            update.Parameters.AddWithValue((int)after.Damaged);
            update.Parameters.AddWithValue(nextVersion);
            update.Parameters.AddWithValue(unit.AuthorityVersion);
            update.Parameters.AddWithValue((long)after.Supplies);
            if (await update.ExecuteNonQueryAsync(cancellationToken) != 1)
                throw new InvalidOperationException("FLAGSHIP_REPAIR_UPDATE_FAILED");
        }

        await using (var history = new NpgsqlCommand("""
            INSERT INTO original_flagship_repair_command(account_id,request_fingerprint,character_id,unit_id,
                grid_id,base_id,ship_generation,source_damaged,result_damaged,destroyed,
                source_supplies,result_supplies,authority_version,include_flagship)
            VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$13,$12,$14)
            """, connection, transaction))
        {
            history.Parameters.AddWithValue(accountId);
            history.Parameters.AddWithValue(write.RequestFingerprint);
            history.Parameters.AddWithValue(write.CharacterId);
            history.Parameters.AddWithValue((long)write.UnitId);
            history.Parameters.AddWithValue((long)unit.CurrentCellId);
            history.Parameters.AddWithValue((long)unit.BaseId);
            history.Parameters.AddWithValue(unit.ShipGeneration);
            history.Parameters.AddWithValue((int)unit.Damaged);
            history.Parameters.AddWithValue((int)after.Damaged);
            history.Parameters.AddWithValue((int)unit.Destroyed);
            history.Parameters.AddWithValue((long)unit.Supplies);
            history.Parameters.AddWithValue(nextVersion);
            history.Parameters.AddWithValue((long)after.Supplies);
            history.Parameters.AddWithValue(write.IncludeFlagship);
            await history.ExecuteNonQueryAsync(cancellationToken);
        }

        // Same transaction as the flagship, charge and history: a late escort
        // conflict rolls every preceding write back. Never accept a stale
        // controller incarnation merely because outfit and grid still match.
        var repairedEscorts = new List<OriginalFleetUnitRecord>();
        foreach (var escort in escorts)
        {
            if (escort.ControllerCharacterId != unit.CharacterId ||
                escort.ControllerUnitId != unit.UnitId || escort.ControllerGeneration != unit.ShipGeneration ||
                escort.GridId != unit.CurrentCellId || escort.Autonomous ||
                escort.Damaged <= escort.Destroyed || escort.Supplies == 0)
                return await RejectAsync(transaction, currentVersion, "FLEET_REPAIR_ESCORT_REJECTED", unit, cancellationToken);
            await using var repair = new NpgsqlCommand("""
                UPDATE original_fleet_unit SET damaged=destroyed,supplies=0,
                    revision=revision+1,updated_at=transaction_timestamp()
                WHERE unit_id=$1 AND outfit_id=$2 AND grid_id=$3 AND ship_generation=$4 AND revision=$5
                    AND controller_character_id=$6 AND controller_unit_id=$7 AND controller_ship_generation=$8
                    AND autonomous=false AND damaged=$9 AND destroyed=$10 AND supplies=$11
                    AND kind=$12 AND power=$13 AND camp=$14
                RETURNING damaged,destroyed,supplies,revision,unit_number,x,y,z,direction,cruising
                """, connection, transaction);
            repair.Parameters.AddWithValue((long)escort.UnitId);
            repair.Parameters.AddWithValue((long)escort.OutfitId);
            repair.Parameters.AddWithValue((long)escort.GridId);
            repair.Parameters.AddWithValue(escort.Generation);
            repair.Parameters.AddWithValue(escort.Revision);
            repair.Parameters.AddWithValue(unit.CharacterId);
            repair.Parameters.AddWithValue((long)unit.UnitId);
            repair.Parameters.AddWithValue(unit.ShipGeneration);
            repair.Parameters.AddWithValue((int)escort.Damaged);
            repair.Parameters.AddWithValue((int)escort.Destroyed);
            repair.Parameters.AddWithValue((long)escort.Supplies);
            repair.Parameters.AddWithValue((int)escort.Kind);
            repair.Parameters.AddWithValue((int)escort.Power);
            repair.Parameters.AddWithValue((int)escort.Camp);
            await using var repairedReader = await repair.ExecuteReaderAsync(cancellationToken);
            if (!await repairedReader.ReadAsync(cancellationToken))
            {
                await repairedReader.DisposeAsync();
                return await RejectAsync(transaction, currentVersion, "FLEET_REPAIR_ESCORT_STALE", unit, cancellationToken);
            }
            repairedEscorts.Add(escort with
            {
                Damaged = checked((ushort)repairedReader.GetInt32(0)),
                Destroyed = checked((ushort)repairedReader.GetInt32(1)),
                Supplies = checked((uint)repairedReader.GetInt64(2)),
                Revision = repairedReader.GetInt64(3),
                Number = checked((ushort)repairedReader.GetInt32(4)),
                X = repairedReader.GetFloat(5), Y = repairedReader.GetFloat(6), Z = repairedReader.GetFloat(7),
                Direction = repairedReader.GetFloat(8), Cruising = repairedReader.GetFloat(9),
            });
        }

        var payload = JsonSerializer.Serialize(new
        {
            includeFlagship = write.IncludeFlagship,
            escorts = escorts.Select(row => new { row.UnitId, row.Generation,
                sourceRevision = row.Revision, revision = checked(row.Revision + 1),
                sourceDamaged = row.Damaged, damaged = row.Destroyed, destroyed = row.Destroyed,
                sourceSupplies = row.Supplies, supplies = 0 }),
            characterId = write.CharacterId,
            unitId = write.UnitId,
            grid = unit.CurrentCellId,
            baseId = unit.BaseId,
            shipGeneration = unit.ShipGeneration,
            sourceDamaged = unit.Damaged,
            damaged = after.Damaged,
            destroyed = unit.Destroyed,
            sourceSupplies = unit.Supplies,
            supplies = after.Supplies,
            commandPointPool = write.Points.Pool.ToString(),
            commandPointCost = write.Points.Cost,
            pcp = charged.Political,
            mcp = charged.Military,
            requestFingerprint = write.RequestFingerprint,
        });
        await using (var domainEvent = new NpgsqlCommand("""
            INSERT INTO domain_event(account_id,aggregate_type,aggregate_id,event_type,payload,authority_version)
            VALUES($1,'original-grid-unit',$2,'OriginalFlagshipRepaired',$3::jsonb,$4)
            """, connection, transaction))
        {
            domainEvent.Parameters.AddWithValue(accountId);
            domainEvent.Parameters.AddWithValue(
                write.UnitId.ToString(System.Globalization.CultureInfo.InvariantCulture));
            domainEvent.Parameters.AddWithValue(payload);
            domainEvent.Parameters.AddWithValue(nextVersion);
            await domainEvent.ExecuteNonQueryAsync(cancellationToken);
        }

        var stateHash = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            "logh7-authority-state/flagship-repair-v1|" + accountId.ToString("D") + "|" + payload)));
        await using (var account = new NpgsqlCommand("""
            UPDATE account SET authority_version=$2,authority_state_hash=$3,updated_at=transaction_timestamp()
            WHERE account_id=$1 AND authority_version=$4
            """, connection, transaction))
        {
            account.Parameters.AddWithValue(accountId);
            account.Parameters.AddWithValue(nextVersion);
            account.Parameters.AddWithValue(stateHash);
            account.Parameters.AddWithValue(currentVersion);
            if (await account.ExecuteNonQueryAsync(cancellationToken) != 1)
                throw new InvalidOperationException("FLAGSHIP_REPAIR_ACCOUNT_UPDATE_FAILED");
        }

        await transaction.CommitAsync(cancellationToken);
        return new(OriginalFlagshipRepairStatus.Repaired, after, nextVersion, null, repairedEscorts);
    }

    private static async Task<OriginalFlagshipRepairResult> RejectAsync(NpgsqlTransaction transaction,
        long version, string errorCode, OriginalGridUnitRecord unit, CancellationToken cancellationToken)
    {
        // Roll back rather than commit: a refused repair must leave no trace,
        // including any points the charge attempt already touched.
        await transaction.RollbackAsync(cancellationToken);
        return new(OriginalFlagshipRepairStatus.Rejected, unit, version, errorCode);
    }
}
