using Npgsql;

namespace Logh7.Server.Storage;

public sealed partial class PostgresFleetUnitStore
{
    // NEW DESIGN: confirmed injury/replacement releases control to authored AI.
    // Missing/corrupt controller data is not evidence of a loss.
    public async Task<OriginalFleetUnitRecord> ReleaseUnavailableControllerAsync(
        OriginalFleetUnitRecord row,CancellationToken cancellationToken)
    {
        if(row.ControllerCharacterId is not { } character) return row;
        if(row.ControllerUnitId is not { } unit || row.ControllerGeneration is not { } generation)
            throw new InvalidDataException("FLEET_CONTROLLER_INCARNATION_MISSING");
        await using var connection=await dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction=await connection.BeginTransactionAsync(cancellationToken);
        bool unavailable;
        await using(var query=new NpgsqlCommand("""
            SELECT unit_id,ship_generation,injury_return_id IS NOT NULL
            FROM original_grid_unit WHERE character_id=$1 FOR UPDATE
            """,connection,transaction))
        {
            query.Parameters.AddWithValue(character);
            await using var reader=await query.ExecuteReaderAsync(cancellationToken);
            if(!await reader.ReadAsync(cancellationToken))
                throw new InvalidDataException("FLEET_CONTROLLER_CHARACTER_MISSING");
            unavailable=reader.GetInt64(0)!=unit || reader.GetInt64(1)!=generation || reader.GetBoolean(2);
        }
        if(!unavailable) return row;
        await using var update=new NpgsqlCommand("""
            UPDATE original_fleet_unit SET controller_character_id=NULL,autonomous=true,
                controller_unit_id=NULL,controller_ship_generation=NULL,
                revision=revision+1,updated_at=transaction_timestamp()
            WHERE unit_id=$1 AND grid_id=$2 AND ship_generation=$3 AND revision=$4
              AND controller_character_id=$5 AND controller_unit_id=$6 AND controller_ship_generation=$7
            """,connection,transaction);
        update.Parameters.AddWithValue((long)row.UnitId);
        update.Parameters.AddWithValue((long)row.GridId);
        update.Parameters.AddWithValue(row.Generation);
        update.Parameters.AddWithValue(row.Revision);
        update.Parameters.AddWithValue(character);
        update.Parameters.AddWithValue((long)unit);
        update.Parameters.AddWithValue(generation);
        if(await update.ExecuteNonQueryAsync(cancellationToken)!=1)
            throw new InvalidOperationException("FLEET_UNIT_SAVE_CONFLICT");
        await transaction.CommitAsync(cancellationToken);
        return row with { ControllerCharacterId=null,ControllerUnitId=null,ControllerGeneration=null,
            Autonomous=true,Revision=checked(row.Revision+1) };
    }
}
