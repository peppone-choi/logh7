using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Npgsql;

namespace Logh7.Server.Storage;

public sealed partial class PostgresAccountStore
{
    // Published update05: destroyed flagships return as destroyers regardless
    // of rank. Kind3/93 are constmsg group85 ship-class rows, NOT model IDs.
    // The incarnation/transaction mechanism is replacement-server NEW_DESIGN.
    public async Task<OriginalInjuryReturnStoreResult> RecoverOriginalFlagshipAsync(
        Guid accountId, long characterId, uint unitId, Guid returnId,
        long expectedUnitVersion, CancellationToken cancellationToken)
    {
        if (characterId <= 0 || unitId == 0 || returnId == Guid.Empty || expectedUnitVersion <= 0)
            throw new ArgumentException("FLAGSHIP_RECOVERY_INVALID_REQUEST");
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
        short faction;
        string? injuryHash;
        int? previousKind;
        short? previousType;
        await using (var select=new NpgsqlCommand("""
            SELECT u.character_id,u.unit_id,u.authority_card_id,u.current_cell_id,u.authority_version,
                   u.base_id,u.damaged,u.destroyed,u.injury_return_id,u.ship_generation,u.cruising,
                   c.faction,u.injury_return_request_hash,c.flagship_kind,c.flagship_type,u.mode,u.unit_number,u.supplies,u.morale
            FROM original_grid_unit u JOIN character c USING(account_id,character_id)
            WHERE u.account_id=$1 AND u.character_id=$2 AND u.unit_id=$3 FOR UPDATE OF u,c
            """,connection,transaction))
        {
            select.Parameters.AddWithValue(accountId); select.Parameters.AddWithValue(characterId);
            select.Parameters.AddWithValue((long)unitId);
            await using var reader=await select.ExecuteReaderAsync(cancellationToken);
            if (!await reader.ReadAsync(cancellationToken))
                throw new InvalidOperationException("FLAGSHIP_RECOVERY_UNIT_NOT_OWNED");
            unit=ReadOriginalGridUnit(reader);
            faction=reader.GetInt16(11);
            injuryHash=reader.IsDBNull(12)?null:reader.GetString(12);
            previousKind=reader.IsDBNull(13)?null:reader.GetInt32(13);
            previousType=reader.IsDBNull(14)?null:reader.GetInt16(14);
        }
        await using (var replay=new NpgsqlCommand("""
            SELECT expected_unit_version FROM original_flagship_recovery
            WHERE account_id=$1 AND character_id=$2 AND unit_id=$3 AND return_id=$4
            """,connection,transaction))
        {
            replay.Parameters.AddWithValue(accountId); replay.Parameters.AddWithValue(characterId);
            replay.Parameters.AddWithValue((long)unitId); replay.Parameters.AddWithValue(returnId);
            if (await replay.ExecuteScalarAsync(cancellationToken) is long previousVersion)
            {
                if (previousVersion != expectedUnitVersion)
                    throw new InvalidOperationException("FLAGSHIP_RECOVERY_REPLAY_CONFLICT");
                await transaction.CommitAsync(cancellationToken);
                return new(unit,false); // Never heal/teleport a later incarnation on retry.
            }
        }
        if (unit.InjuryReturnId != returnId || unit.AuthorityVersion != expectedUnitVersion ||
            unit.BaseId == 0 || unit.Damaged == 0 || unit.Destroyed != unit.Damaged || injuryHash is null)
            throw new InvalidOperationException("FLAGSHIP_RECOVERY_SOURCE_STALE");
        var kind=faction switch
        {
            2 => 3,
            3 => 93,
            _ => throw new InvalidOperationException("FLAGSHIP_RECOVERY_FACTION_UNSUPPORTED")
        };
        var generation=checked(unit.ShipGeneration+1);
        version=checked(version+1);
        var payload=JsonSerializer.Serialize(new {
            returnId,characterId,unitId,grid=unit.CurrentCellId,baseId=unit.BaseId,
            previousGeneration=unit.ShipGeneration,shipGeneration=generation,
            damaged=unit.Damaged,destroyed=unit.Destroyed,previousKind,previousType,
            fallbackKind=kind,fallbackType=0,
            previousCruising=unit.Cruising,cruising=10f // NEW_DESIGN fresh-ship range, not recovered balance.
        });
        await using (var archive=new NpgsqlCommand("""
            INSERT INTO original_flagship_recovery(account_id,character_id,unit_id,return_id,
                expected_unit_version,injury_request_hash,previous_generation,ship_generation,
                damaged,destroyed,fallback_kind,authority_version)
            VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
            """,connection,transaction))
        {
            archive.Parameters.AddWithValue(accountId); archive.Parameters.AddWithValue(characterId);
            archive.Parameters.AddWithValue((long)unitId); archive.Parameters.AddWithValue(returnId);
            archive.Parameters.AddWithValue(expectedUnitVersion); archive.Parameters.AddWithValue(injuryHash);
            archive.Parameters.AddWithValue(unit.ShipGeneration); archive.Parameters.AddWithValue(generation);
            archive.Parameters.AddWithValue((int)unit.Damaged); archive.Parameters.AddWithValue((int)unit.Destroyed);
            archive.Parameters.AddWithValue(kind); archive.Parameters.AddWithValue(version);
            await archive.ExecuteNonQueryAsync(cancellationToken);
        }
        await using (var update=new NpgsqlCommand("""
            UPDATE original_grid_unit SET damaged=0,destroyed=0,injury_return_id=NULL,
                injury_return_request_hash=NULL,ship_generation=$4,authority_version=$5,cruising=10,
                updated_at=transaction_timestamp()
            WHERE account_id=$1 AND character_id=$2 AND unit_id=$3
            """,connection,transaction))
        {
            update.Parameters.AddWithValue(accountId); update.Parameters.AddWithValue(characterId);
            update.Parameters.AddWithValue((long)unitId); update.Parameters.AddWithValue(generation);
            update.Parameters.AddWithValue(version);
            if (await update.ExecuteNonQueryAsync(cancellationToken)!=1)
                throw new InvalidOperationException("FLAGSHIP_RECOVERY_UNIT_NOT_OWNED");
        }
        await using (var update=new NpgsqlCommand("""
            UPDATE character SET flagship_type=0,flagship_kind=$3,authority_version=$4
            WHERE account_id=$1 AND character_id=$2
            """,connection,transaction))
        {
            update.Parameters.AddWithValue(accountId); update.Parameters.AddWithValue(characterId);
            update.Parameters.AddWithValue(kind); update.Parameters.AddWithValue(version);
            if (await update.ExecuteNonQueryAsync(cancellationToken)!=1)
                throw new InvalidOperationException("FLAGSHIP_RECOVERY_CHARACTER_NOT_OWNED");
        }
        await using (var record=new NpgsqlCommand("""
            INSERT INTO domain_event(account_id,aggregate_type,aggregate_id,event_type,payload,authority_version)
            VALUES($1,'original-grid-unit',$2,'OriginalFlagshipRecovered',$3::jsonb,$4)
            """,connection,transaction))
        {
            record.Parameters.AddWithValue(accountId);
            record.Parameters.AddWithValue(unitId.ToString(CultureInfo.InvariantCulture));
            record.Parameters.AddWithValue(payload); record.Parameters.AddWithValue(version);
            await record.ExecuteNonQueryAsync(cancellationToken);
        }
        var canonical=FormattableString.Invariant($"original-flagship-recovery/v1|{accountId:N}|{version}|{payload}");
        var hash=Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(canonical)));
        await using (var update=new NpgsqlCommand("""
            UPDATE account SET authority_version=$2,authority_state_hash=$3,updated_at=transaction_timestamp()
            WHERE account_id=$1
            """,connection,transaction))
        {
            update.Parameters.AddWithValue(accountId); update.Parameters.AddWithValue(version);
            update.Parameters.AddWithValue(hash);
            await update.ExecuteNonQueryAsync(cancellationToken);
        }
        await transaction.CommitAsync(cancellationToken);
        return new(unit with { Damaged=0,Destroyed=0,InjuryReturnId=null,
            ShipGeneration=generation,AuthorityVersion=version,Cruising=10 },true);
    }
}
