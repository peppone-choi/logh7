namespace Logh7.Server.Storage;

public sealed partial class PostgresFleetUnitStore
{
    // Trusted persistence primitive. The command layer must authorize performer,
    // target affiliation and current grid while holding the battle lease.
    // Update supplies alone so stale pose/control/casualty fields cannot leak in.
    public async Task<bool> SupplyOrdinaryUnitAsync(OriginalFleetUnitRecord expected,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(expected);
        await using var command = dataSource.CreateCommand("""
            UPDATE original_fleet_unit SET supplies=100,revision=revision+1,
                updated_at=transaction_timestamp()
            WHERE unit_id=$1 AND outfit_id=$2 AND grid_id=$3
                AND ship_generation=$4 AND revision=$5
                AND power=$6 AND camp=$7 AND kind=$8
                AND destroyed<unit_number AND supplies<100
            """);
        command.Parameters.AddWithValue((long)expected.UnitId);
        command.Parameters.AddWithValue((long)expected.OutfitId);
        command.Parameters.AddWithValue((long)expected.GridId);
        command.Parameters.AddWithValue(expected.Generation);
        command.Parameters.AddWithValue(expected.Revision);
        command.Parameters.AddWithValue((int)expected.Power);
        command.Parameters.AddWithValue((int)expected.Camp);
        command.Parameters.AddWithValue((int)expected.Kind);
        return await command.ExecuteNonQueryAsync(cancellationToken) == 1;
    }
}
