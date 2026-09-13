using Npgsql;

namespace Logh7.Server.Storage;

/// <summary>One 空戦 sortie: it costs the unit part of its carried craft.</summary>
public sealed record OriginalBoatSpendWrite(long CharacterId, uint UnitId, uint GridId, long Amount);

/// <summary>One admission: a host - a unit or a base - admits a unit.</summary>
public sealed record OriginalAdmissionWrite(
    long CharacterId, bool HostIsBase, uint HostId, uint TargetUnitId, uint GridId);

/// <summary>Where a fortress stands after MoveFortress moved it.</summary>
public sealed record OriginalBasePositionWrite(
    uint BaseId, uint GridId, double X, double Y, double Z);

public sealed record OriginalSimpleStoreResult(
    bool Applied, long Value, long AuthorityVersion, string? ErrorCode = null);

/// <summary>
/// The state the last four tactical commands needed: carried craft for 空戦, an
/// admission grant for Admission / AdmissionBase, and a moved fortress's position
/// for MoveFortress.
/// </summary>
/// <remarks>
/// Each reuses a shape this authority already has. The boat complement is the same
/// 100 a unit's stores and its landing force use; the admission grant is the row
/// 緊急補給's grant already showed the way to; the fortress position is the catalog's
/// own x/y/z, persisted only once something moves it.
/// </remarks>
public sealed partial class PostgresAccountStore
{
    /// <summary>A unit's full complement of carried craft.</summary>
    public const long FullUnitBoats = 100;

    public async Task<OriginalSimpleStoreResult> SpendOriginalBoatsAsync(
        Guid accountId, OriginalBoatSpendWrite write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.CharacterId <= 0 || write.UnitId == 0 || write.Amount <= 0)
            throw new ArgumentException("BOAT_SPEND_INVALID_REQUEST");
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        var currentVersion = await LockAccountVersionAsync(connection, transaction, accountId, cancellationToken);

        var carried = FullUnitBoats;
        await using (var select = new NpgsqlCommand(
            "SELECT boats FROM original_unit_boat " +
            "WHERE account_id=$1 AND character_id=$2 AND unit_id=$3 FOR UPDATE", connection, transaction))
        {
            select.Parameters.AddWithValue(accountId);
            select.Parameters.AddWithValue(write.CharacterId);
            select.Parameters.AddWithValue((long)write.UnitId);
            if (await select.ExecuteScalarAsync(cancellationToken) is long stored) carried = stored;
        }
        if (carried <= 0)
            return new(false, 0, currentVersion, "BOAT_NONE_CARRIED");

        var next = Math.Max(0, carried - write.Amount);
        var nextVersion = checked(currentVersion + 1);
        await using (var upsert = new NpgsqlCommand(
            "INSERT INTO original_unit_boat (account_id,character_id,unit_id,boats,authority_version) " +
            "VALUES ($1,$2,$3,$4,$5) ON CONFLICT (account_id,character_id,unit_id) DO UPDATE " +
            "SET boats=EXCLUDED.boats,authority_version=EXCLUDED.authority_version,updated_at=now()",
            connection, transaction))
        {
            upsert.Parameters.AddWithValue(accountId);
            upsert.Parameters.AddWithValue(write.CharacterId);
            upsert.Parameters.AddWithValue((long)write.UnitId);
            upsert.Parameters.AddWithValue(next);
            upsert.Parameters.AddWithValue(nextVersion);
            await upsert.ExecuteNonQueryAsync(cancellationToken);
        }
        if (!await BumpAccountVersionAsync(connection, transaction, accountId, currentVersion, nextVersion, cancellationToken))
            return new(false, 0, currentVersion, "BOAT_VERSION_CONFLICT");
        await transaction.CommitAsync(cancellationToken);
        return new(true, next, nextVersion, null);
    }

    public async Task<OriginalSimpleStoreResult> GrantOriginalAdmissionAsync(
        Guid accountId, OriginalAdmissionWrite write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.CharacterId <= 0 || write.HostId == 0 || write.TargetUnitId == 0)
            throw new ArgumentException("ADMISSION_INVALID_REQUEST");
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        var currentVersion = await LockAccountVersionAsync(connection, transaction, accountId, cancellationToken);
        var nextVersion = checked(currentVersion + 1);
        bool inserted;
        await using (var insert = new NpgsqlCommand(
            "INSERT INTO original_admission_grant " +
            "(account_id,character_id,host_kind,host_id,target_unit_id,grid_id,authority_version) " +
            "VALUES ($1,$2,$3,$4,$5,$6,$7) " +
            "ON CONFLICT (account_id,character_id,host_kind,host_id,target_unit_id) DO NOTHING",
            connection, transaction))
        {
            insert.Parameters.AddWithValue(accountId);
            insert.Parameters.AddWithValue(write.CharacterId);
            insert.Parameters.AddWithValue((short)(write.HostIsBase ? 1 : 0));
            insert.Parameters.AddWithValue((long)write.HostId);
            insert.Parameters.AddWithValue((long)write.TargetUnitId);
            insert.Parameters.AddWithValue((long)write.GridId);
            insert.Parameters.AddWithValue(nextVersion);
            inserted = await insert.ExecuteNonQueryAsync(cancellationToken) == 1;
        }
        if (!inserted)
        {
            // The same admission twice is the same admission.
            await transaction.CommitAsync(cancellationToken);
            return new(true, 0, currentVersion, null);
        }
        if (!await BumpAccountVersionAsync(connection, transaction, accountId, currentVersion, nextVersion, cancellationToken))
            return new(false, 0, currentVersion, "ADMISSION_VERSION_CONFLICT");
        await transaction.CommitAsync(cancellationToken);
        return new(true, 1, nextVersion, null);
    }

    public async Task<OriginalSimpleStoreResult> MoveOriginalBaseAsync(
        Guid accountId, OriginalBasePositionWrite write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.BaseId == 0 || !double.IsFinite(write.X) || !double.IsFinite(write.Y) ||
            !double.IsFinite(write.Z))
            throw new ArgumentException("BASE_MOVE_INVALID_REQUEST");
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        var currentVersion = await LockAccountVersionAsync(connection, transaction, accountId, cancellationToken);
        var nextVersion = checked(currentVersion + 1);
        await using (var upsert = new NpgsqlCommand(
            "INSERT INTO original_base_position (account_id,base_id,grid_id,x,y,z,authority_version) " +
            "VALUES ($1,$2,$3,$4,$5,$6,$7) ON CONFLICT (account_id,base_id) DO UPDATE " +
            "SET grid_id=EXCLUDED.grid_id,x=EXCLUDED.x,y=EXCLUDED.y,z=EXCLUDED.z," +
            "authority_version=EXCLUDED.authority_version,updated_at=now()", connection, transaction))
        {
            upsert.Parameters.AddWithValue(accountId);
            upsert.Parameters.AddWithValue((long)write.BaseId);
            upsert.Parameters.AddWithValue((long)write.GridId);
            upsert.Parameters.AddWithValue(write.X);
            upsert.Parameters.AddWithValue(write.Y);
            upsert.Parameters.AddWithValue(write.Z);
            upsert.Parameters.AddWithValue(nextVersion);
            await upsert.ExecuteNonQueryAsync(cancellationToken);
        }
        if (!await BumpAccountVersionAsync(connection, transaction, accountId, currentVersion, nextVersion, cancellationToken))
            return new(false, 0, currentVersion, "BASE_MOVE_VERSION_CONFLICT");
        await transaction.CommitAsync(cancellationToken);
        return new(true, 1, nextVersion, null);
    }
}
