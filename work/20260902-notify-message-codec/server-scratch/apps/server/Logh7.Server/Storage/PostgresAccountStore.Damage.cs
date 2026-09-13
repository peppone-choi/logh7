using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Npgsql;

namespace Logh7.Server.Storage;

public sealed partial class PostgresAccountStore
{
    public async Task<OriginalUnitDamageStoreResult> SaveOriginalUnitDamageAsync(
        Guid accountId,OriginalUnitDamageWrite write,CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if(write.CharacterId<=0 || write.UnitId==0 || write.Grid==0 || write.ShipGeneration<0 ||
            write.Number==0 || write.Destroyed>write.Damaged || write.Damaged>write.Number)
            throw new ArgumentException("DAMAGE_INVALID_SHAPE");
        await using var connection=await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction=await connection.BeginTransactionAsync(cancellationToken);
        long version;
        await using(var account=new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id=$1 FOR UPDATE",connection,transaction))
        {
            account.Parameters.AddWithValue(accountId);
            version=(long)(await account.ExecuteScalarAsync(cancellationToken)
                ?? throw new InvalidOperationException("DAMAGE_ACCOUNT_NOT_FOUND"));
        }
        OriginalGridUnitRecord unit;
        await using(var select=new NpgsqlCommand("""
            SELECT character_id,unit_id,authority_card_id,current_cell_id,authority_version,
                base_id,damaged,destroyed,injury_return_id,ship_generation,cruising,mode,unit_number,supplies,morale
            FROM original_grid_unit WHERE account_id=$1 AND character_id=$2 AND unit_id=$3 FOR UPDATE
            """,connection,transaction))
        {
            select.Parameters.AddWithValue(accountId);
            select.Parameters.AddWithValue(write.CharacterId);
            select.Parameters.AddWithValue((long)write.UnitId);
            await using var reader=await select.ExecuteReaderAsync(cancellationToken);
            if(!await reader.ReadAsync(cancellationToken))
                throw new InvalidOperationException("DAMAGE_UNIT_NOT_OWNED");
            unit=ReadOriginalGridUnit(reader);
        }
        // No stale shot may hit a new incarnation, relocated or returning ship.
        if (write.Number != unit.UnitNumber)
            throw new InvalidOperationException("DAMAGE_COMPLEMENT_MISMATCH");
        if(unit.CurrentCellId!=write.Grid || unit.ShipGeneration!=write.ShipGeneration ||
            unit.InjuryReturnId is not null)
            throw new InvalidOperationException("DAMAGE_SOURCE_STALE");
        if(write.Damaged<unit.Damaged || write.Destroyed<unit.Destroyed)
            throw new InvalidOperationException("DAMAGE_REGRESSION");
        if(write.Damaged==unit.Damaged && write.Destroyed==unit.Destroyed)
        {
            await transaction.CommitAsync(cancellationToken);
            return new(unit,false);
        }
        version=checked(version+1);
        // NEW_DESIGN morale: the client names both 0x0409 CommandEncourageFlagship
        // and 0x0440 NotifyMoraleDown, so morale is a value that falls with losses
        // and is raised again by 鼓舞. Only hulls actually destroyed since the last
        // write cost morale, in proportion to the unit's complement, so the fall is
        // monotone and bounded. The rate itself is authored, not recovered.
        var lost=checked(write.Destroyed-unit.Destroyed);
        var morale=lost==0
            ? unit.Morale
            : checked((byte)Math.Max(0,unit.Morale-(int)Math.Round(100.0*lost/write.Number,
                MidpointRounding.AwayFromZero)));
        var after=unit with {
            Damaged=write.Damaged,Destroyed=write.Destroyed,Morale=morale,AuthorityVersion=version };
        await using(var update=new NpgsqlCommand("""
            UPDATE original_grid_unit SET damaged=$4,destroyed=$5,authority_version=$6,morale=$7,
                updated_at=transaction_timestamp()
            WHERE account_id=$1 AND character_id=$2 AND unit_id=$3
            """,connection,transaction))
        {
            update.Parameters.AddWithValue(accountId);update.Parameters.AddWithValue(write.CharacterId);
            update.Parameters.AddWithValue((long)write.UnitId);update.Parameters.AddWithValue((int)write.Damaged);
            update.Parameters.AddWithValue((int)write.Destroyed);update.Parameters.AddWithValue(version);
            update.Parameters.AddWithValue((int)morale);
            if(await update.ExecuteNonQueryAsync(cancellationToken)!=1)
                throw new InvalidOperationException("DAMAGE_UPDATE_FAILED");
        }
        var payload=JsonSerializer.Serialize(new {
            characterId=write.CharacterId,unitId=write.UnitId,grid=write.Grid,
            shipGeneration=write.ShipGeneration,number=write.Number,
            sourceDamaged=unit.Damaged,sourceDestroyed=unit.Destroyed,
            damaged=write.Damaged,destroyed=write.Destroyed,
            sourceMorale=unit.Morale,morale=morale
        });
        await using(var domainEvent=new NpgsqlCommand("""
            INSERT INTO domain_event(account_id,aggregate_type,aggregate_id,event_type,payload,authority_version)
            VALUES($1,'original-grid-unit',$2,'OriginalUnitDamaged',$3::jsonb,$4)
            """,connection,transaction))
        {
            domainEvent.Parameters.AddWithValue(accountId);
            domainEvent.Parameters.AddWithValue(write.UnitId.ToString(System.Globalization.CultureInfo.InvariantCulture));
            domainEvent.Parameters.AddWithValue(payload);domainEvent.Parameters.AddWithValue(version);
            await domainEvent.ExecuteNonQueryAsync(cancellationToken);
        }
        var hash=Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            "logh7-authority-state/damage-v1|"+accountId.ToString("D")+"|"+JsonSerializer.Serialize(after))));
        await using(var account=new NpgsqlCommand("""
            UPDATE account SET authority_version=$2,authority_state_hash=$3,updated_at=transaction_timestamp()
            WHERE account_id=$1
            """,connection,transaction))
        {
            account.Parameters.AddWithValue(accountId);account.Parameters.AddWithValue(version);account.Parameters.AddWithValue(hash);
            await account.ExecuteNonQueryAsync(cancellationToken);
        }
        await transaction.CommitAsync(cancellationToken);
        return new(after,true);
    }
}
