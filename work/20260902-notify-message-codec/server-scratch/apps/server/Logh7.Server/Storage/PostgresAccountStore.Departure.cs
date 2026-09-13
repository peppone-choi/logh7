using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Npgsql;

namespace Logh7.Server.Storage;

public sealed partial class PostgresAccountStore
{
    public async Task<OriginalDepartureStoreResult> DepartOwnOriginalUnitAsync(
        Guid accountId, OriginalDepartureWrite write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if(write.CharacterId<=0 || write.UnitId==0 || write.ExpectedUnitVersion<=0 ||
            write.ExpectedShipGeneration<0 || write.RequestFingerprint is not { Length:64 } ||
            write.RequestFingerprint.Any(c=>!Uri.IsHexDigit(c)) || write.TargetMode is not (4 or 5))
            throw new ArgumentException("DEPARTURE_INVALID_REQUEST");
        await using var connection=await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction=await connection.BeginTransactionAsync(cancellationToken);
        long version;
        await using(var account=new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id=$1 FOR UPDATE",connection,transaction))
        {
            account.Parameters.AddWithValue(accountId);
            version=(long)(await account.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("DEPARTURE_ACCOUNT_NOT_FOUND"));
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
                throw new InvalidOperationException("DEPARTURE_UNIT_NOT_OWNED");
            unit=ReadOriginalGridUnit(reader);
        }
        await using(var replay=new NpgsqlCommand("""
            SELECT character_id,unit_id FROM original_departure_request
            WHERE account_id=$1 AND request_fingerprint=$2
            """,connection,transaction))
        {
            replay.Parameters.AddWithValue(accountId);
            replay.Parameters.AddWithValue(write.RequestFingerprint);
            await using var reader=await replay.ExecuteReaderAsync(cancellationToken);
            if(await reader.ReadAsync(cancellationToken))
            {
                if(reader.GetInt64(0)!=write.CharacterId || reader.GetInt64(1)!=write.UnitId)
                    throw new InvalidOperationException("DEPARTURE_REPLAY_CONFLICT");
                await reader.DisposeAsync();
                await transaction.CommitAsync(cancellationToken);
                // Return current state, never teleport a later ship/life back
                // to the outcome of an old request. Expected-state fields are
                // preconditions for a NEW intent, not part of its action.
                return new(unit,false);
            }
        }
        if(unit.AuthorityVersion!=write.ExpectedUnitVersion ||
            unit.ShipGeneration!=write.ExpectedShipGeneration || unit.BaseId!=write.ExpectedBaseId)
            throw new InvalidOperationException("DEPARTURE_SOURCE_STALE");
        if(unit.BaseId==0)throw new InvalidOperationException("DEPARTURE_NOT_AT_BASE");
        if(unit.Mode==write.TargetMode || (write.TargetMode==5 && unit.Mode!=4))
            throw new InvalidOperationException("DEPARTURE_STANCE_UNAVAILABLE");
        if(unit.InjuryReturnId is not null || unit.Destroyed>=unit.UnitNumber)
            throw new InvalidOperationException("DEPARTURE_UNIT_UNAVAILABLE");
        // ORIGINAL_STATIC E121/E122: mode4 is garrison, not open-space travel.
        // Keep the owning base; clearing it creates an invisible ship with no
        // valid base and prevents the native destruction lifecycle.
        version=checked(version+1);
        // NEW_DESIGN: cruising is an authored fuel counter that only a warp
        // spends. Without a replenishment the world is a one-way trip, so
        // garrisoning at the unit's own base refills it. The refill is part of
        // the same transaction as the stance change; a refused stance change
        // refuels nothing.
        var cruising=write.TargetMode==4
            ? OriginalGateway.OriginalMoveGridAuthority.MinimalWorldStartingCruising
            : unit.Cruising;
        var after=unit with { Mode=write.TargetMode,Cruising=cruising,AuthorityVersion=version };
        var eventPayload=JsonSerializer.Serialize(new {
            characterId=write.CharacterId,unitId=write.UnitId,grid=unit.CurrentCellId,
            sourceBase=unit.BaseId,destinationBase=unit.BaseId,sourceMode=unit.Mode,mode=write.TargetMode,
            sourceCruising=unit.Cruising,cruising,damaged=unit.Damaged,destroyed=unit.Destroyed,
            shipGeneration=unit.ShipGeneration,requestFingerprint=write.RequestFingerprint
        });
        await using(var update=new NpgsqlCommand("""
            UPDATE original_grid_unit SET mode=$5,cruising=$6,authority_version=$4,
                updated_at=transaction_timestamp()
            WHERE account_id=$1 AND character_id=$2 AND unit_id=$3
            """,connection,transaction))
        {
            update.Parameters.AddWithValue(accountId); update.Parameters.AddWithValue(write.CharacterId);
            update.Parameters.AddWithValue((long)write.UnitId); update.Parameters.AddWithValue(version);
            update.Parameters.AddWithValue((int)write.TargetMode);
            update.Parameters.AddWithValue(cruising);
            if(await update.ExecuteNonQueryAsync(cancellationToken)!=1)
                throw new InvalidOperationException("DEPARTURE_UPDATE_FAILED");
        }
        await using(var history=new NpgsqlCommand("""
            INSERT INTO original_departure_request(account_id,request_fingerprint,character_id,unit_id,
                source_base_id,ship_generation,authority_version) VALUES($1,$2,$3,$4,$5,$6,$7)
            """,connection,transaction))
        {
            history.Parameters.AddWithValue(accountId); history.Parameters.AddWithValue(write.RequestFingerprint);
            history.Parameters.AddWithValue(write.CharacterId); history.Parameters.AddWithValue((long)write.UnitId);
            history.Parameters.AddWithValue((long)unit.BaseId); history.Parameters.AddWithValue(unit.ShipGeneration);
            history.Parameters.AddWithValue(version);
            await history.ExecuteNonQueryAsync(cancellationToken);
        }
        await using(var domainEvent=new NpgsqlCommand("""
            INSERT INTO domain_event(account_id,aggregate_type,aggregate_id,event_type,payload,authority_version)
            VALUES($1,'original-grid-unit',$2,'OriginalUnitStanceChanged',$3::jsonb,$4)
            """,connection,transaction))
        {
            domainEvent.Parameters.AddWithValue(accountId);
            domainEvent.Parameters.AddWithValue(write.UnitId.ToString(System.Globalization.CultureInfo.InvariantCulture));
            domainEvent.Parameters.AddWithValue(eventPayload); domainEvent.Parameters.AddWithValue(version);
            await domainEvent.ExecuteNonQueryAsync(cancellationToken);
        }
        var stateHash=Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            "logh7-authority-state/departure-v1|"+accountId.ToString("D")+"|"+JsonSerializer.Serialize(after))));
        await using(var account=new NpgsqlCommand("""
            UPDATE account SET authority_version=$2,authority_state_hash=$3,updated_at=transaction_timestamp()
            WHERE account_id=$1
            """,connection,transaction))
        {
            account.Parameters.AddWithValue(accountId); account.Parameters.AddWithValue(version);
            account.Parameters.AddWithValue(stateHash);
            if(await account.ExecuteNonQueryAsync(cancellationToken)!=1)
                throw new InvalidOperationException("DEPARTURE_ACCOUNT_UPDATE_FAILED");
        }
        await transaction.CommitAsync(cancellationToken);
        return new(after,true);
    }
}
