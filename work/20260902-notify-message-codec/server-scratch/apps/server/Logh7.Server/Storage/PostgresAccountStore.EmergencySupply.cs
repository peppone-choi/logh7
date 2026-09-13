using Npgsql;

namespace Logh7.Server.Storage;

/// <summary>
/// 緊急補給 (0x0422) - a base making a unit resupplyable.
/// </summary>
/// <remarks>
/// constmsg group 0 row 27 is 「[ 緊急補給 ] 緊急補給可能にする 実行待機時間48G秒
/// 実行所要時間0G秒」. The row is explicit that the command makes an emergency
/// resupply *possible* rather than performing one, so this writes a grant and
/// refills nothing. What consumes the grant is 補給 (0x0414), whose own row
/// requires a 補給艦: a unit a base has granted may be resupplied without one while
/// it is in that base's grid.
///
/// Granting the same (character, unit, base) twice is the same grant, so the
/// insert is idempotent on that key. The duration the original states is 0 G秒,
/// so nothing is occupied by it either.
/// </remarks>
public sealed partial class PostgresAccountStore
{
    public async Task<OriginalEmergencySupplyGrantResult> GrantOriginalEmergencySupplyAsync(
        Guid accountId, OriginalEmergencySupplyGrantWrite write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.CharacterId <= 0 || write.UnitId == 0 || write.BaseId == 0)
            throw new ArgumentException("EMERGENCY_SUPPLY_INVALID_REQUEST");
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE", connection, transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken)
                ?? throw new InvalidOperationException("EMERGENCY_SUPPLY_ACCOUNT_NOT_FOUND"));
        }

        // The unit has to be this character's own and standing on this grid; the
        // grant is about a unit a base can reach, not any unit id at all.
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
                return new(false, false, currentVersion, "EMERGENCY_SUPPLY_UNIT_NOT_HERE");
        }

        var nextVersion = checked(currentVersion + 1);
        bool inserted;
        await using (var insert = new NpgsqlCommand("""
            INSERT INTO original_emergency_supply_grant
                (account_id,character_id,unit_id,base_id,grid_id,authority_version)
            VALUES ($1,$2,$3,$4,$5,$6)
            ON CONFLICT (account_id,character_id,unit_id,base_id) DO NOTHING
            """, connection, transaction))
        {
            insert.Parameters.AddWithValue(accountId);
            insert.Parameters.AddWithValue(write.CharacterId);
            insert.Parameters.AddWithValue((long)write.UnitId);
            insert.Parameters.AddWithValue((long)write.BaseId);
            insert.Parameters.AddWithValue((long)write.GridId);
            insert.Parameters.AddWithValue(nextVersion);
            inserted = await insert.ExecuteNonQueryAsync(cancellationToken) == 1;
        }

        if (!inserted)
        {
            // The same grant twice is the same grant: nothing changed, so the
            // authority version does not move either.
            await transaction.CommitAsync(cancellationToken);
            return new(true, true, currentVersion, null);
        }

        await using (var bump = new NpgsqlCommand(
            "UPDATE account SET authority_version=$2 WHERE account_id=$1 AND authority_version=$3",
            connection, transaction))
        {
            bump.Parameters.AddWithValue(accountId);
            bump.Parameters.AddWithValue(nextVersion);
            bump.Parameters.AddWithValue(currentVersion);
            if (await bump.ExecuteNonQueryAsync(cancellationToken) != 1)
                return new(false, false, currentVersion, "EMERGENCY_SUPPLY_VERSION_CONFLICT");
        }
        await transaction.CommitAsync(cancellationToken);
        return new(true, false, nextVersion, null);
    }

    public async Task<bool> HasOriginalEmergencySupplyGrantAsync(
        Guid accountId, long characterId, uint unitId, uint gridId, CancellationToken cancellationToken)
    {
        if (characterId <= 0 || unitId == 0) return false;
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var select = new NpgsqlCommand("""
            SELECT 1 FROM original_emergency_supply_grant
            WHERE account_id=$1 AND character_id=$2 AND unit_id=$3 AND grid_id=$4 LIMIT 1
            """, connection);
        select.Parameters.AddWithValue(accountId);
        select.Parameters.AddWithValue(characterId);
        select.Parameters.AddWithValue((long)unitId);
        select.Parameters.AddWithValue((long)gridId);
        return await select.ExecuteScalarAsync(cancellationToken) is not null;
    }
}
