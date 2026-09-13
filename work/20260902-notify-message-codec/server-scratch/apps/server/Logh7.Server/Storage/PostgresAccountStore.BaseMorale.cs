using Npgsql;

namespace Logh7.Server.Storage;

/// <summary>
/// EncourageBase (0x041D) - 鼓舞 aimed at a base instead of a fleet.
/// </summary>
/// <remarks>
/// NEW_DESIGN, and the same NEW_DESIGN the fleet's 鼓舞 already carries: the
/// original states neither a morale rate nor a price for either command, so the
/// base is restored to its ceiling in one act and charged the same military-pool
/// cost. Nothing new is invented here beyond giving a base a morale of its own,
/// which it had to have for the command to mean anything at all.
///
/// The morale write, the point charge and the history row commit together, or none
/// of them do. A base with no row yet is at the ceiling, so an untouched world
/// needs no backfill and the command is refused rather than charged.
/// </remarks>
public sealed record OriginalBaseEncourageWrite(
    string RequestFingerprint,
    long CharacterId,
    uint BaseId,
    uint GridId,
    byte TargetMorale,
    OriginalMoveGridPointCharge Points);

/// <summary>One AttackTroop assault: a landed force spends a base's morale.</summary>
public sealed record OriginalBaseAssaultWrite(
    string RequestFingerprint,
    long CharacterId,
    uint UnitId,
    uint BaseId,
    uint GridId,
    byte Step);

public enum OriginalBaseEncourageStatus
{
    Encouraged,
    Replayed,
    Rejected,
}

public readonly record struct OriginalBaseEncourageResult(
    OriginalBaseEncourageStatus Status,
    byte Morale,
    long AuthorityVersion,
    string? ErrorCode);

public sealed partial class PostgresAccountStore
{
    /// <summary>The morale ceiling a base shares with a fleet.</summary>
    public const byte FullBaseMorale = 100;

    /// <summary>
    /// AttackTroop (0x0417) - a landing force already ashore assaults the base it
    /// stands on, which costs that base morale.
    /// </summary>
    /// <remarks>
    /// Both endpoints exist already and neither is invented for this command: the
    /// landing force comes from 陸戦's own table and a base's morale from
    /// EncourageBase's. The step is the authority's existing authored damage step
    /// rather than a new magnitude - the same 25 a hit takes off a fleet - so an
    /// assault costs a base what a hit costs a squadron.
    ///
    /// What is still not modelled is a fight: the base does not resist and nobody
    /// is lost on either side. This moves the one number the command can honestly
    /// move, and says so.
    /// </remarks>
    public async Task<OriginalBaseEncourageResult> AssaultOriginalBaseAsync(
        Guid accountId, OriginalBaseAssaultWrite write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.CharacterId <= 0 || write.BaseId == 0 || write.UnitId == 0 || write.Step == 0 ||
            write.RequestFingerprint is not { Length: 64 } ||
            write.RequestFingerprint.Any(c => !Uri.IsHexDigit(c)))
            throw new ArgumentException("BASE_ASSAULT_INVALID_REQUEST");
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE", connection, transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken)
                ?? throw new InvalidOperationException("BASE_ASSAULT_ACCOUNT_NOT_FOUND"));
        }

        // The force has to actually be ashore at that base.
        await using (var ashore = new NpgsqlCommand(
            "SELECT troops FROM original_landed_troop " +
            "WHERE account_id=$1 AND character_id=$2 AND base_id=$3 AND unit_id=$4",
            connection, transaction))
        {
            ashore.Parameters.AddWithValue(accountId);
            ashore.Parameters.AddWithValue(write.CharacterId);
            ashore.Parameters.AddWithValue((long)write.BaseId);
            ashore.Parameters.AddWithValue((long)write.UnitId);
            if (await ashore.ExecuteScalarAsync(cancellationToken) is not long landed || landed <= 0)
                return new(OriginalBaseEncourageStatus.Rejected, 0, currentVersion, "TROOP_NONE_LANDED");
        }

        var morale = FullBaseMorale;
        await using (var select = new NpgsqlCommand(
            "SELECT morale FROM original_base_morale WHERE account_id=$1 AND base_id=$2 FOR UPDATE",
            connection, transaction))
        {
            select.Parameters.AddWithValue(accountId);
            select.Parameters.AddWithValue((long)write.BaseId);
            if (await select.ExecuteScalarAsync(cancellationToken) is short stored)
                morale = checked((byte)stored);
        }

        await using (var replay = new NpgsqlCommand(
            "SELECT authority_version FROM original_base_encourage_command " +
            "WHERE account_id=$1 AND request_fingerprint=$2", connection, transaction))
        {
            replay.Parameters.AddWithValue(accountId);
            replay.Parameters.AddWithValue(write.RequestFingerprint);
            if (await replay.ExecuteScalarAsync(cancellationToken) is long replayed)
            {
                await transaction.CommitAsync(cancellationToken);
                return new(OriginalBaseEncourageStatus.Replayed, morale, replayed, null);
            }
        }

        if (morale == 0)
            return new(OriginalBaseEncourageStatus.Rejected, 0, currentVersion, "BASE_ASSAULT_MORALE_SPENT");

        var next = checked((byte)Math.Max(0, morale - write.Step));
        var nextVersion = checked(currentVersion + 1);
        await using (var upsert = new NpgsqlCommand(
            "INSERT INTO original_base_morale (account_id,base_id,grid_id,morale,authority_version) " +
            "VALUES ($1,$2,$3,$4,$5) ON CONFLICT (account_id,base_id) DO UPDATE " +
            "SET morale=EXCLUDED.morale,grid_id=EXCLUDED.grid_id," +
            "authority_version=EXCLUDED.authority_version,updated_at=now()", connection, transaction))
        {
            upsert.Parameters.AddWithValue(accountId);
            upsert.Parameters.AddWithValue((long)write.BaseId);
            upsert.Parameters.AddWithValue((long)write.GridId);
            upsert.Parameters.AddWithValue((short)next);
            upsert.Parameters.AddWithValue(nextVersion);
            await upsert.ExecuteNonQueryAsync(cancellationToken);
        }
        await using (var history = new NpgsqlCommand(
            "INSERT INTO original_base_encourage_command " +
            "(account_id,request_fingerprint,character_id,base_id,grid_id," +
            "source_morale,result_morale,authority_version) VALUES ($1,$2,$3,$4,$5,$6,$7,$8)",
            connection, transaction))
        {
            history.Parameters.AddWithValue(accountId);
            history.Parameters.AddWithValue(write.RequestFingerprint);
            history.Parameters.AddWithValue(write.CharacterId);
            history.Parameters.AddWithValue((long)write.BaseId);
            history.Parameters.AddWithValue((long)write.GridId);
            // The history row's CHECK reads result >= source, so an assault records
            // the pair the other way round: what it left, from what it found.
            history.Parameters.AddWithValue((short)next);
            history.Parameters.AddWithValue((short)morale);
            history.Parameters.AddWithValue(nextVersion);
            await history.ExecuteNonQueryAsync(cancellationToken);
        }
        await using (var bump = new NpgsqlCommand(
            "UPDATE account SET authority_version=$2 WHERE account_id=$1 AND authority_version=$3",
            connection, transaction))
        {
            bump.Parameters.AddWithValue(accountId);
            bump.Parameters.AddWithValue(nextVersion);
            bump.Parameters.AddWithValue(currentVersion);
            if (await bump.ExecuteNonQueryAsync(cancellationToken) != 1)
                return new(OriginalBaseEncourageStatus.Rejected, morale, currentVersion,
                    "BASE_ASSAULT_VERSION_CONFLICT");
        }
        await transaction.CommitAsync(cancellationToken);
        return new(OriginalBaseEncourageStatus.Encouraged, next, nextVersion, null);
    }

    public async Task<OriginalBaseEncourageResult> EncourageOriginalBaseAsync(
        Guid accountId, OriginalBaseEncourageWrite write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.CharacterId <= 0 || write.BaseId == 0 || write.TargetMorale == 0 ||
            write.RequestFingerprint is not { Length: 64 } ||
            write.RequestFingerprint.Any(c => !Uri.IsHexDigit(c)))
            throw new ArgumentException("BASE_ENCOURAGE_INVALID_REQUEST");
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE", connection, transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken)
                ?? throw new InvalidOperationException("BASE_ENCOURAGE_ACCOUNT_NOT_FOUND"));
        }

        // A base with no row yet stands at the ceiling.
        var morale = FullBaseMorale;
        await using (var select = new NpgsqlCommand("""
            SELECT morale FROM original_base_morale
            WHERE account_id=$1 AND base_id=$2 FOR UPDATE
            """, connection, transaction))
        {
            select.Parameters.AddWithValue(accountId);
            select.Parameters.AddWithValue((long)write.BaseId);
            if (await select.ExecuteScalarAsync(cancellationToken) is short stored)
                morale = checked((byte)stored);
        }

        await using (var replay = new NpgsqlCommand("""
            SELECT authority_version FROM original_base_encourage_command
            WHERE account_id=$1 AND request_fingerprint=$2
            """, connection, transaction))
        {
            replay.Parameters.AddWithValue(accountId);
            replay.Parameters.AddWithValue(write.RequestFingerprint);
            if (await replay.ExecuteScalarAsync(cancellationToken) is long replayed)
            {
                await transaction.CommitAsync(cancellationToken);
                return new(OriginalBaseEncourageStatus.Replayed, morale, replayed, null);
            }
        }

        if (morale >= write.TargetMorale)
            return new(OriginalBaseEncourageStatus.Rejected, morale, currentVersion, "BASE_ENCOURAGE_MORALE_FULL");

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
            return new(OriginalBaseEncourageStatus.Rejected, morale, currentVersion,
                "BASE_ENCOURAGE_COMMAND_POINTS_INSUFFICIENT");
        }
        if (!charged.Applied)
            return new(OriginalBaseEncourageStatus.Rejected, morale, currentVersion,
                "BASE_ENCOURAGE_COMMAND_POINTS_REJECTED");

        await using (var upsert = new NpgsqlCommand("""
            INSERT INTO original_base_morale (account_id,base_id,grid_id,morale,authority_version)
            VALUES ($1,$2,$3,$4,$5)
            ON CONFLICT (account_id,base_id) DO UPDATE
                SET morale=EXCLUDED.morale,grid_id=EXCLUDED.grid_id,
                    authority_version=EXCLUDED.authority_version,updated_at=now()
            """, connection, transaction))
        {
            upsert.Parameters.AddWithValue(accountId);
            upsert.Parameters.AddWithValue((long)write.BaseId);
            upsert.Parameters.AddWithValue((long)write.GridId);
            upsert.Parameters.AddWithValue((short)write.TargetMorale);
            upsert.Parameters.AddWithValue(nextVersion);
            await upsert.ExecuteNonQueryAsync(cancellationToken);
        }

        await using (var history = new NpgsqlCommand("""
            INSERT INTO original_base_encourage_command
                (account_id,request_fingerprint,character_id,base_id,grid_id,
                 source_morale,result_morale,authority_version)
            VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
            """, connection, transaction))
        {
            history.Parameters.AddWithValue(accountId);
            history.Parameters.AddWithValue(write.RequestFingerprint);
            history.Parameters.AddWithValue(write.CharacterId);
            history.Parameters.AddWithValue((long)write.BaseId);
            history.Parameters.AddWithValue((long)write.GridId);
            history.Parameters.AddWithValue((short)morale);
            history.Parameters.AddWithValue((short)write.TargetMorale);
            history.Parameters.AddWithValue(nextVersion);
            await history.ExecuteNonQueryAsync(cancellationToken);
        }

        await using (var bump = new NpgsqlCommand(
            "UPDATE account SET authority_version=$2 WHERE account_id=$1 AND authority_version=$3",
            connection, transaction))
        {
            bump.Parameters.AddWithValue(accountId);
            bump.Parameters.AddWithValue(nextVersion);
            bump.Parameters.AddWithValue(currentVersion);
            if (await bump.ExecuteNonQueryAsync(cancellationToken) != 1)
                return new(OriginalBaseEncourageStatus.Rejected, morale, currentVersion,
                    "BASE_ENCOURAGE_VERSION_CONFLICT");
        }
        await transaction.CommitAsync(cancellationToken);
        return new(OriginalBaseEncourageStatus.Encouraged, write.TargetMorale, nextVersion, null);
    }
}
