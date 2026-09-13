using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Npgsql;

namespace Logh7.Server.Storage;

/// <summary>
/// 補給 (0x0414) - a 補給艦 refilling another unit's stores in the field.
/// </summary>
/// <remarks>
/// constmsg group 0 row 26 is 「[ 補給 ] 補給艦による補給を行う 注）旗艦の左
/// 実行待機時間48G秒 実行所要時間1800G秒」. The original states no point price for it -
/// the manual's 160 belongs to 完全修復, a different command - so none is charged.
/// The cost the original does state is the 1800 G秒 the unit is occupied, which
/// the gateway enforces from the same row.
/// </remarks>
public sealed record OriginalTacticalSupplyWrite(
    string RequestFingerprint,
    long CharacterId,
    uint UnitId,
    uint VesselUnitId,
    long ExpectedUnitVersion,
    long ExpectedShipGeneration);

public enum OriginalTacticalSupplyStatus
{
    Supplied,
    Replayed,
    Rejected,
}

public readonly record struct OriginalTacticalSupplyResult(
    OriginalTacticalSupplyStatus Status,
    OriginalGridUnitRecord? Unit,
    long AuthorityVersion,
    string? ErrorCode);

public sealed partial class PostgresAccountStore
{
    /// <summary>
    /// A unit's full stock. Every unit this authority creates starts here and the
    /// repair primitive spends it to zero, so this is the authority's own existing
    /// full value rather than a number invented for 補給.
    /// </summary>
    public const long FullUnitSupplies = 100;

    public async Task<OriginalTacticalSupplyResult> SupplyOwnOriginalUnitAsync(
        Guid accountId, OriginalTacticalSupplyWrite write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.CharacterId <= 0 || write.UnitId == 0 || write.VesselUnitId == 0 ||
            write.ExpectedUnitVersion <= 0 || write.ExpectedShipGeneration < 0 ||
            write.RequestFingerprint is not { Length: 64 } ||
            write.RequestFingerprint.Any(c => !Uri.IsHexDigit(c)))
            throw new ArgumentException("TACTICAL_SUPPLY_INVALID_REQUEST");
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE", connection, transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken)
                ?? throw new InvalidOperationException("TACTICAL_SUPPLY_ACCOUNT_NOT_FOUND"));
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
                throw new InvalidOperationException("TACTICAL_SUPPLY_UNIT_NOT_OWNED");
            unit = ReadOriginalGridUnit(reader);
        }

        await using (var replay = new NpgsqlCommand("""
            SELECT authority_version FROM original_tactical_supply_command
            WHERE account_id=$1 AND request_fingerprint=$2
            """, connection, transaction))
        {
            replay.Parameters.AddWithValue(accountId);
            replay.Parameters.AddWithValue(write.RequestFingerprint);
            var replayed = await replay.ExecuteScalarAsync(cancellationToken);
            if (replayed is not null)
            {
                await transaction.CommitAsync(cancellationToken);
                // The current row, never the historical one.
                return new(OriginalTacticalSupplyStatus.Replayed, unit, (long)replayed, null);
            }
        }

        if (unit.AuthorityVersion != write.ExpectedUnitVersion ||
            unit.ShipGeneration != write.ExpectedShipGeneration)
            return await RejectSupplyAsync(transaction, currentVersion, "TACTICAL_SUPPLY_SOURCE_STALE",
                unit, cancellationToken);
        if (unit.InjuryReturnId is not null)
            return await RejectSupplyAsync(transaction, currentVersion, "TACTICAL_SUPPLY_UNIT_RECOVERING",
                unit, cancellationToken);
        if (unit.Destroyed >= unit.UnitNumber)
            return await RejectSupplyAsync(transaction, currentVersion, "TACTICAL_SUPPLY_UNIT_DESTROYED",
                unit, cancellationToken);
        if (unit.Supplies >= FullUnitSupplies)
            return await RejectSupplyAsync(transaction, currentVersion, "TACTICAL_SUPPLY_ALREADY_FULL",
                unit, cancellationToken);

        var nextVersion = checked(currentVersion + 1);
        var after = unit with { Supplies = checked((uint)FullUnitSupplies), AuthorityVersion = nextVersion };
        await using (var update = new NpgsqlCommand("""
            UPDATE original_grid_unit SET supplies=$4,authority_version=$5,
                updated_at=transaction_timestamp()
            WHERE account_id=$1 AND character_id=$2 AND unit_id=$3 AND authority_version=$6
            """, connection, transaction))
        {
            update.Parameters.AddWithValue(accountId);
            update.Parameters.AddWithValue(write.CharacterId);
            update.Parameters.AddWithValue((long)write.UnitId);
            update.Parameters.AddWithValue(FullUnitSupplies);
            update.Parameters.AddWithValue(nextVersion);
            update.Parameters.AddWithValue(unit.AuthorityVersion);
            if (await update.ExecuteNonQueryAsync(cancellationToken) != 1)
                throw new InvalidOperationException("TACTICAL_SUPPLY_UPDATE_FAILED");
        }

        await using (var history = new NpgsqlCommand("""
            INSERT INTO original_tactical_supply_command(account_id,request_fingerprint,character_id,unit_id,
                vessel_unit_id,grid_id,ship_generation,source_supplies,result_supplies,authority_version)
            VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)
            """, connection, transaction))
        {
            history.Parameters.AddWithValue(accountId);
            history.Parameters.AddWithValue(write.RequestFingerprint);
            history.Parameters.AddWithValue(write.CharacterId);
            history.Parameters.AddWithValue((long)write.UnitId);
            history.Parameters.AddWithValue((long)write.VesselUnitId);
            history.Parameters.AddWithValue((long)unit.CurrentCellId);
            history.Parameters.AddWithValue(unit.ShipGeneration);
            history.Parameters.AddWithValue((long)unit.Supplies);
            history.Parameters.AddWithValue(FullUnitSupplies);
            history.Parameters.AddWithValue(nextVersion);
            await history.ExecuteNonQueryAsync(cancellationToken);
        }

        var payload = JsonSerializer.Serialize(new
        {
            characterId = write.CharacterId,
            unitId = write.UnitId,
            vesselUnitId = write.VesselUnitId,
            grid = unit.CurrentCellId,
            shipGeneration = unit.ShipGeneration,
            sourceSupplies = unit.Supplies,
            supplies = FullUnitSupplies,
            requestFingerprint = write.RequestFingerprint,
        });
        await using (var domainEvent = new NpgsqlCommand("""
            INSERT INTO domain_event(account_id,aggregate_type,aggregate_id,event_type,payload,authority_version)
            VALUES($1,'original-grid-unit',$2,'OriginalTacticalUnitSupplied',$3::jsonb,$4)
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
            "logh7-authority-state/tactical-supply-v1|" + accountId.ToString("D") + "|" + payload)));
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
                throw new InvalidOperationException("TACTICAL_SUPPLY_ACCOUNT_UPDATE_FAILED");
        }

        await transaction.CommitAsync(cancellationToken);
        return new(OriginalTacticalSupplyStatus.Supplied, after, nextVersion, null);
    }

    private static async Task<OriginalTacticalSupplyResult> RejectSupplyAsync(NpgsqlTransaction transaction,
        long version, string errorCode, OriginalGridUnitRecord unit, CancellationToken cancellationToken)
    {
        // Roll back rather than commit: a refused supply must leave no trace.
        await transaction.RollbackAsync(cancellationToken);
        return new(OriginalTacticalSupplyStatus.Rejected, unit, version, errorCode);
    }
}
