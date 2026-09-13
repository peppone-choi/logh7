using Npgsql;

namespace Logh7.Server.Storage;

/// <summary>
/// 陸戦 (0x040F) and 陸戦解除 (0x0410) - a unit's landing force going ashore and
/// coming back.
/// </summary>
/// <remarks>
/// constmsg group 0 row 17 is 「[ 陸戦 ] 碇泊状態から陸戦を投下する 実行待機時間48G秒
/// 実行所要時間240G秒」 and row 18 「[ 陸戦解除 ] 陸戦ユニットを帰還させる 実行待機時間48G秒
/// 実行所要時間240G秒」. Both schedules are the client's own numbers.
///
/// The troop stock concept is recovered; where a *unit's* carried troops live is
/// not, so these two tables are NEW_DESIGN, and so is the complement. It is
/// deliberately the same 100 the authority already uses for a unit's full stores
/// rather than a second invented magnitude. What a landed force then does - hold
/// ground, fight, take a base - is not modelled and is not pretended here: the
/// landing moves the force and records where it is.
/// </remarks>
public sealed partial class PostgresAccountStore
{
    /// <summary>A unit's full landing force, the same full value its stores use.</summary>
    public const long FullUnitTroops = 100;

    public async Task<OriginalTroopMoveResult> LandOriginalTroopsAsync(
        Guid accountId, OriginalTroopMoveWrite write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.CharacterId <= 0 || write.UnitId == 0 || write.BaseId == 0)
            throw new ArgumentException("TROOP_MOVE_INVALID_REQUEST");
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        var currentVersion = await LockAccountVersionAsync(connection, transaction, accountId, cancellationToken);

        // The unit must be this character's own and standing on this grid.
        await using (var owned = new NpgsqlCommand("""
            SELECT 1 FROM original_grid_unit
            WHERE account_id=$1 AND character_id=$2 AND unit_id=$3 AND current_cell_id=$4
            """, connection, transaction))
        {
            owned.Parameters.AddWithValue(accountId);
            owned.Parameters.AddWithValue(write.CharacterId);
            owned.Parameters.AddWithValue((long)write.UnitId);
            owned.Parameters.AddWithValue((long)write.GridId);
            if (await owned.ExecuteScalarAsync(cancellationToken) is null)
                return new(false, 0, 0, currentVersion, "TROOP_UNIT_NOT_HERE");
        }

        var carried = await ReadCarriedTroopsAsync(connection, transaction, accountId, write, cancellationToken);
        if (carried <= 0)
            return new(false, 0, 0, currentVersion, "TROOP_NONE_CARRIED");

        var nextVersion = checked(currentVersion + 1);
        await UpsertCarriedAsync(connection, transaction, accountId, write, 0, nextVersion, cancellationToken);
        await using (var land = new NpgsqlCommand("""
            INSERT INTO original_landed_troop
                (account_id,character_id,base_id,unit_id,grid_id,troops,authority_version)
            VALUES ($1,$2,$3,$4,$5,$6,$7)
            ON CONFLICT (account_id,character_id,base_id,unit_id) DO UPDATE
                SET troops=original_landed_troop.troops+EXCLUDED.troops,
                    authority_version=EXCLUDED.authority_version,updated_at=now()
            """, connection, transaction))
        {
            land.Parameters.AddWithValue(accountId);
            land.Parameters.AddWithValue(write.CharacterId);
            land.Parameters.AddWithValue((long)write.BaseId);
            land.Parameters.AddWithValue((long)write.UnitId);
            land.Parameters.AddWithValue((long)write.GridId);
            land.Parameters.AddWithValue(carried);
            land.Parameters.AddWithValue(nextVersion);
            await land.ExecuteNonQueryAsync(cancellationToken);
        }
        if (!await BumpAccountVersionAsync(connection, transaction, accountId, currentVersion, nextVersion, cancellationToken))
            return new(false, 0, 0, currentVersion, "TROOP_VERSION_CONFLICT");
        await transaction.CommitAsync(cancellationToken);
        return new(true, 0, carried, nextVersion, null);
    }

    public async Task<OriginalTroopMoveResult> RecallOriginalTroopsAsync(
        Guid accountId, OriginalTroopMoveWrite write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.CharacterId <= 0 || write.UnitId == 0 || write.BaseId == 0)
            throw new ArgumentException("TROOP_MOVE_INVALID_REQUEST");
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        var currentVersion = await LockAccountVersionAsync(connection, transaction, accountId, cancellationToken);

        long landed;
        await using (var select = new NpgsqlCommand("""
            SELECT troops FROM original_landed_troop
            WHERE account_id=$1 AND character_id=$2 AND base_id=$3 AND unit_id=$4 FOR UPDATE
            """, connection, transaction))
        {
            select.Parameters.AddWithValue(accountId);
            select.Parameters.AddWithValue(write.CharacterId);
            select.Parameters.AddWithValue((long)write.BaseId);
            select.Parameters.AddWithValue((long)write.UnitId);
            if (await select.ExecuteScalarAsync(cancellationToken) is not long value)
                return new(false, 0, 0, currentVersion, "TROOP_NONE_LANDED");
            landed = value;
        }

        var carried = await ReadCarriedTroopsAsync(connection, transaction, accountId, write, cancellationToken);
        var nextVersion = checked(currentVersion + 1);
        await UpsertCarriedAsync(connection, transaction, accountId, write,
            checked(carried + landed), nextVersion, cancellationToken);
        await using (var clear = new NpgsqlCommand("""
            DELETE FROM original_landed_troop
            WHERE account_id=$1 AND character_id=$2 AND base_id=$3 AND unit_id=$4
            """, connection, transaction))
        {
            clear.Parameters.AddWithValue(accountId);
            clear.Parameters.AddWithValue(write.CharacterId);
            clear.Parameters.AddWithValue((long)write.BaseId);
            clear.Parameters.AddWithValue((long)write.UnitId);
            await clear.ExecuteNonQueryAsync(cancellationToken);
        }
        if (!await BumpAccountVersionAsync(connection, transaction, accountId, currentVersion, nextVersion, cancellationToken))
            return new(false, 0, 0, currentVersion, "TROOP_VERSION_CONFLICT");
        await transaction.CommitAsync(cancellationToken);
        return new(true, checked(carried + landed), 0, nextVersion, null);
    }

    /// <summary>
    /// MoveTroop (0x0416) - a landing force already ashore marches to another area.
    /// </summary>
    /// <remarks>
    /// The client's logger calls the command's one byte <c>area</c>, and the areas
    /// this authority's tactical grid has are its bases, so the byte names the base
    /// the force marches to. A value that is not a base in this grid is refused by
    /// the caller rather than guessed at, and the force keeps its strength: this
    /// moves where it stands, nothing else.
    /// </remarks>
    public async Task<OriginalTroopMoveResult> RelocateOriginalTroopsAsync(
        Guid accountId, OriginalTroopMoveWrite write, uint destinationBaseId,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.CharacterId <= 0 || write.UnitId == 0 || write.BaseId == 0 || destinationBaseId == 0)
            throw new ArgumentException("TROOP_MOVE_INVALID_REQUEST");
        if (destinationBaseId == write.BaseId)
            return new(false, 0, 0, 0, "TROOP_ALREADY_THERE");
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        var currentVersion = await LockAccountVersionAsync(connection, transaction, accountId, cancellationToken);

        long landed;
        await using (var select = new NpgsqlCommand(
            "SELECT troops FROM original_landed_troop " +
            "WHERE account_id=$1 AND character_id=$2 AND base_id=$3 AND unit_id=$4 FOR UPDATE",
            connection, transaction))
        {
            select.Parameters.AddWithValue(accountId);
            select.Parameters.AddWithValue(write.CharacterId);
            select.Parameters.AddWithValue((long)write.BaseId);
            select.Parameters.AddWithValue((long)write.UnitId);
            if (await select.ExecuteScalarAsync(cancellationToken) is not long value)
                return new(false, 0, 0, currentVersion, "TROOP_NONE_LANDED");
            landed = value;
        }

        var nextVersion = checked(currentVersion + 1);
        await using (var clear = new NpgsqlCommand(
            "DELETE FROM original_landed_troop " +
            "WHERE account_id=$1 AND character_id=$2 AND base_id=$3 AND unit_id=$4",
            connection, transaction))
        {
            clear.Parameters.AddWithValue(accountId);
            clear.Parameters.AddWithValue(write.CharacterId);
            clear.Parameters.AddWithValue((long)write.BaseId);
            clear.Parameters.AddWithValue((long)write.UnitId);
            await clear.ExecuteNonQueryAsync(cancellationToken);
        }
        await using (var arrive = new NpgsqlCommand(
            "INSERT INTO original_landed_troop " +
            "(account_id,character_id,base_id,unit_id,grid_id,troops,authority_version) " +
            "VALUES ($1,$2,$3,$4,$5,$6,$7) " +
            "ON CONFLICT (account_id,character_id,base_id,unit_id) DO UPDATE " +
            "SET troops=original_landed_troop.troops+EXCLUDED.troops," +
            "authority_version=EXCLUDED.authority_version,updated_at=now()", connection, transaction))
        {
            arrive.Parameters.AddWithValue(accountId);
            arrive.Parameters.AddWithValue(write.CharacterId);
            arrive.Parameters.AddWithValue((long)destinationBaseId);
            arrive.Parameters.AddWithValue((long)write.UnitId);
            arrive.Parameters.AddWithValue((long)write.GridId);
            arrive.Parameters.AddWithValue(landed);
            arrive.Parameters.AddWithValue(nextVersion);
            await arrive.ExecuteNonQueryAsync(cancellationToken);
        }
        if (!await BumpAccountVersionAsync(connection, transaction, accountId, currentVersion, nextVersion, cancellationToken))
            return new(false, 0, 0, currentVersion, "TROOP_VERSION_CONFLICT");
        await transaction.CommitAsync(cancellationToken);
        return new(true, 0, landed, nextVersion, null);
    }

    /// <summary>
    /// 白兵戦 (0x0407) - a boarding party costs the unit part of its complement.
    /// </summary>
    /// <remarks>
    /// The carried complement is the same one 陸戦 puts ashore, so a boarding party
    /// and a landing force come out of one pool - a unit cannot spend the same men
    /// twice. The amount is the caller's, and the caller uses the authority's
    /// existing authored step rather than a magnitude of its own.
    /// </remarks>
    public async Task<OriginalTroopMoveResult> SpendOriginalTroopsAsync(
        Guid accountId, OriginalTroopMoveWrite write, long amount, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.CharacterId <= 0 || write.UnitId == 0 || amount <= 0)
            throw new ArgumentException("TROOP_SPEND_INVALID_REQUEST");
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        var currentVersion = await LockAccountVersionAsync(connection, transaction, accountId, cancellationToken);
        var carried = await ReadCarriedTroopsAsync(connection, transaction, accountId, write, cancellationToken);
        if (carried <= 0)
            return new(false, 0, 0, currentVersion, "TROOP_NONE_CARRIED");
        var next = Math.Max(0, carried - amount);
        var nextVersion = checked(currentVersion + 1);
        await UpsertCarriedAsync(connection, transaction, accountId, write, next, nextVersion, cancellationToken);
        if (!await BumpAccountVersionAsync(connection, transaction, accountId, currentVersion, nextVersion, cancellationToken))
            return new(false, 0, 0, currentVersion, "TROOP_VERSION_CONFLICT");
        await transaction.CommitAsync(cancellationToken);
        return new(true, next, 0, nextVersion, null);
    }

    /// <summary>Where a unit's landing force stands right now.</summary>
    public async Task<OriginalTroopState> ReadOriginalTroopStateAsync(
        Guid accountId, long characterId, uint unitId, CancellationToken cancellationToken)
    {
        if (characterId <= 0 || unitId == 0) return new(0, 0, 0);
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        long carried = FullUnitTroops;
        await using (var select = new NpgsqlCommand("""
            SELECT troops FROM original_unit_troop
            WHERE account_id=$1 AND character_id=$2 AND unit_id=$3
            """, connection))
        {
            select.Parameters.AddWithValue(accountId);
            select.Parameters.AddWithValue(characterId);
            select.Parameters.AddWithValue((long)unitId);
            if (await select.ExecuteScalarAsync(cancellationToken) is long value) carried = value;
        }
        await using var landedCommand = new NpgsqlCommand("""
            SELECT COALESCE(SUM(troops),0), COALESCE(MAX(base_id),0) FROM original_landed_troop
            WHERE account_id=$1 AND character_id=$2 AND unit_id=$3
            """, connection);
        landedCommand.Parameters.AddWithValue(accountId);
        landedCommand.Parameters.AddWithValue(characterId);
        landedCommand.Parameters.AddWithValue((long)unitId);
        await using var reader = await landedCommand.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken)) return new(carried, 0, 0);
        return new(carried, reader.GetInt64(0), checked((uint)reader.GetInt64(1)));
    }

    private static async Task<long> LockAccountVersionAsync(NpgsqlConnection connection,
        NpgsqlTransaction transaction, Guid accountId, CancellationToken cancellationToken)
    {
        await using var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE", connection, transaction);
        account.Parameters.AddWithValue(accountId);
        return (long)(await account.ExecuteScalarAsync(cancellationToken)
            ?? throw new InvalidOperationException("TROOP_ACCOUNT_NOT_FOUND"));
    }

    private static async Task<bool> BumpAccountVersionAsync(NpgsqlConnection connection,
        NpgsqlTransaction transaction, Guid accountId, long currentVersion, long nextVersion,
        CancellationToken cancellationToken)
    {
        await using var bump = new NpgsqlCommand(
            "UPDATE account SET authority_version=$2 WHERE account_id=$1 AND authority_version=$3",
            connection, transaction);
        bump.Parameters.AddWithValue(accountId);
        bump.Parameters.AddWithValue(nextVersion);
        bump.Parameters.AddWithValue(currentVersion);
        return await bump.ExecuteNonQueryAsync(cancellationToken) == 1;
    }

    private static async Task<long> ReadCarriedTroopsAsync(NpgsqlConnection connection,
        NpgsqlTransaction transaction, Guid accountId, OriginalTroopMoveWrite write,
        CancellationToken cancellationToken)
    {
        await using var select = new NpgsqlCommand("""
            SELECT troops FROM original_unit_troop
            WHERE account_id=$1 AND character_id=$2 AND unit_id=$3 FOR UPDATE
            """, connection, transaction);
        select.Parameters.AddWithValue(accountId);
        select.Parameters.AddWithValue(write.CharacterId);
        select.Parameters.AddWithValue((long)write.UnitId);
        // No row yet means the unit is still carrying its full complement; the row
        // is materialised on the first move rather than backfilled.
        return await select.ExecuteScalarAsync(cancellationToken) is long value ? value : FullUnitTroops;
    }

    private static async Task UpsertCarriedAsync(NpgsqlConnection connection,
        NpgsqlTransaction transaction, Guid accountId, OriginalTroopMoveWrite write,
        long troops, long version, CancellationToken cancellationToken)
    {
        await using var upsert = new NpgsqlCommand("""
            INSERT INTO original_unit_troop (account_id,character_id,unit_id,troops,authority_version)
            VALUES ($1,$2,$3,$4,$5)
            ON CONFLICT (account_id,character_id,unit_id) DO UPDATE
                SET troops=EXCLUDED.troops,authority_version=EXCLUDED.authority_version,updated_at=now()
            """, connection, transaction);
        upsert.Parameters.AddWithValue(accountId);
        upsert.Parameters.AddWithValue(write.CharacterId);
        upsert.Parameters.AddWithValue((long)write.UnitId);
        upsert.Parameters.AddWithValue(troops);
        upsert.Parameters.AddWithValue(version);
        await upsert.ExecuteNonQueryAsync(cancellationToken);
    }
}
