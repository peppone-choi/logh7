using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Logh7.Server.OriginalGateway;
using Npgsql;

namespace Logh7.Server.Storage;

/// <summary>
/// 鼓舞 (0x0409) for the player's own flagship. NEW_DESIGN: the original rate is
/// not recovered, so the authority restores morale to its maximum in one act and
/// charges the command-point pool for it, exactly as 完全修復 does.
/// </summary>
public sealed record OriginalFlagshipEncourageWrite(
    string RequestFingerprint,
    long CharacterId,
    uint UnitId,
    long ExpectedUnitVersion,
    long ExpectedShipGeneration,
    byte TargetMorale,
    OriginalMoveGridPointCharge Points);

public enum OriginalFlagshipEncourageStatus
{
    Encouraged,
    Replayed,
    Rejected,
}

public readonly record struct OriginalFlagshipEncourageResult(
    OriginalFlagshipEncourageStatus Status,
    OriginalGridUnitRecord? Unit,
    long AuthorityVersion,
    string? ErrorCode);

public sealed partial class PostgresAccountStore
{
    public async Task<OriginalFlagshipEncourageResult> EncourageOwnOriginalFlagshipAsync(
        Guid accountId, OriginalFlagshipEncourageWrite write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.CharacterId <= 0 || write.UnitId == 0 || write.ExpectedUnitVersion <= 0 ||
            write.ExpectedShipGeneration < 0 || write.TargetMorale is 0 or > 100 ||
            write.RequestFingerprint is not { Length: 64 } ||
            write.RequestFingerprint.Any(c => !Uri.IsHexDigit(c)))
            throw new ArgumentException("FLAGSHIP_ENCOURAGE_INVALID_REQUEST");
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE", connection, transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken)
                ?? throw new InvalidOperationException("FLAGSHIP_ENCOURAGE_ACCOUNT_NOT_FOUND"));
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
                throw new InvalidOperationException("FLAGSHIP_ENCOURAGE_UNIT_NOT_OWNED");
            unit = ReadOriginalGridUnit(reader);
        }

        await using (var replay = new NpgsqlCommand("""
            SELECT authority_version FROM original_flagship_encourage_command
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
                return new(OriginalFlagshipEncourageStatus.Replayed, unit, (long)replayed, null);
            }
        }

        if (unit.AuthorityVersion != write.ExpectedUnitVersion ||
            unit.ShipGeneration != write.ExpectedShipGeneration)
            return await RejectEncourageAsync(transaction, currentVersion, "FLAGSHIP_ENCOURAGE_SOURCE_STALE",
                unit, cancellationToken);
        if (unit.InjuryReturnId is not null)
            return await RejectEncourageAsync(transaction, currentVersion, "FLAGSHIP_ENCOURAGE_UNIT_RECOVERING",
                unit, cancellationToken);
        if (unit.Destroyed >= unit.UnitNumber)
            return await RejectEncourageAsync(transaction, currentVersion, "FLAGSHIP_ENCOURAGE_UNIT_DESTROYED",
                unit, cancellationToken);
        if (unit.Morale >= write.TargetMorale)
            return await RejectEncourageAsync(transaction, currentVersion, "FLAGSHIP_ENCOURAGE_MORALE_FULL",
                unit, cancellationToken);

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
            return await RejectEncourageAsync(transaction, currentVersion,
                "FLAGSHIP_ENCOURAGE_COMMAND_POINTS_INSUFFICIENT", unit, cancellationToken);
        }
        if (!charged.Applied)
            return await RejectEncourageAsync(transaction, currentVersion,
                "FLAGSHIP_ENCOURAGE_COMMAND_POINTS_REPLAYED", unit, cancellationToken);

        var after = unit with { Morale = write.TargetMorale, AuthorityVersion = nextVersion };
        await using (var update = new NpgsqlCommand("""
            UPDATE original_grid_unit SET morale=$4,authority_version=$5,
                updated_at=transaction_timestamp()
            WHERE account_id=$1 AND character_id=$2 AND unit_id=$3 AND authority_version=$6
            """, connection, transaction))
        {
            update.Parameters.AddWithValue(accountId);
            update.Parameters.AddWithValue(write.CharacterId);
            update.Parameters.AddWithValue((long)write.UnitId);
            update.Parameters.AddWithValue((int)after.Morale);
            update.Parameters.AddWithValue(nextVersion);
            update.Parameters.AddWithValue(unit.AuthorityVersion);
            if (await update.ExecuteNonQueryAsync(cancellationToken) != 1)
                throw new InvalidOperationException("FLAGSHIP_ENCOURAGE_UPDATE_FAILED");
        }

        await using (var history = new NpgsqlCommand("""
            INSERT INTO original_flagship_encourage_command(account_id,request_fingerprint,character_id,unit_id,
                grid_id,ship_generation,source_morale,result_morale,authority_version)
            VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)
            """, connection, transaction))
        {
            history.Parameters.AddWithValue(accountId);
            history.Parameters.AddWithValue(write.RequestFingerprint);
            history.Parameters.AddWithValue(write.CharacterId);
            history.Parameters.AddWithValue((long)write.UnitId);
            history.Parameters.AddWithValue((long)unit.CurrentCellId);
            history.Parameters.AddWithValue(unit.ShipGeneration);
            history.Parameters.AddWithValue((int)unit.Morale);
            history.Parameters.AddWithValue((int)after.Morale);
            history.Parameters.AddWithValue(nextVersion);
            await history.ExecuteNonQueryAsync(cancellationToken);
        }

        var payload = JsonSerializer.Serialize(new
        {
            characterId = write.CharacterId,
            unitId = write.UnitId,
            grid = unit.CurrentCellId,
            shipGeneration = unit.ShipGeneration,
            sourceMorale = unit.Morale,
            morale = after.Morale,
            commandPointPool = write.Points.Pool.ToString(),
            commandPointCost = write.Points.Cost,
            pcp = charged.Political,
            mcp = charged.Military,
            requestFingerprint = write.RequestFingerprint,
        });
        await using (var domainEvent = new NpgsqlCommand("""
            INSERT INTO domain_event(account_id,aggregate_type,aggregate_id,event_type,payload,authority_version)
            VALUES($1,'original-grid-unit',$2,'OriginalFlagshipEncouraged',$3::jsonb,$4)
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
            "logh7-authority-state/flagship-encourage-v1|" + accountId.ToString("D") + "|" + payload)));
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
                throw new InvalidOperationException("FLAGSHIP_ENCOURAGE_ACCOUNT_UPDATE_FAILED");
        }

        await transaction.CommitAsync(cancellationToken);
        return new(OriginalFlagshipEncourageStatus.Encouraged, after, nextVersion, null);
    }

    private static async Task<OriginalFlagshipEncourageResult> RejectEncourageAsync(
        NpgsqlTransaction transaction, long version, string errorCode, OriginalGridUnitRecord unit,
        CancellationToken cancellationToken)
    {
        // Roll back rather than commit: a refused encouragement must leave no
        // trace, including any points the charge attempt already touched.
        await transaction.RollbackAsync(cancellationToken);
        return new(OriginalFlagshipEncourageStatus.Rejected, unit, version, errorCode);
    }
}
