using Npgsql;

namespace Logh7.Server.Storage;

public sealed partial class PostgresFleetUnitStore
{
    // Trusted ordinary-unit persistence primitive, NOT full-repair command authority.
    // Caller must authorize the full command and coordinate flagship/points/live
    // registry separately before exposing this primitive to a network request.
    // Only casualties and supplies change. Client result fields are never inputs.
    public async Task<bool> RepairOrdinaryUnitsAsync(IReadOnlyList<OriginalFleetUnitRecord> expected,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(expected);
        var rows = expected.OrderBy(row => row.UnitId).ToArray();
        if (rows.Length == 0 || rows.Select(row => row.UnitId).Distinct().Count() != rows.Length ||
            rows.Any(row => row.OutfitId != rows[0].OutfitId || row.GridId != rows[0].GridId))
            return false;
        await using var connection = await dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        foreach (var row in rows)
        {
            await using var command = new NpgsqlCommand("""
                UPDATE original_fleet_unit SET damaged=destroyed,supplies=0,
                    revision=revision+1,updated_at=transaction_timestamp()
                WHERE unit_id=$1 AND outfit_id=$2 AND grid_id=$3
                    AND ship_generation=$4 AND revision=$5
                    AND kind=$6 AND power=$7 AND camp=$8
                """, connection, transaction);
            command.Parameters.AddWithValue((long)row.UnitId);
            command.Parameters.AddWithValue((long)row.OutfitId);
            command.Parameters.AddWithValue((long)row.GridId);
            command.Parameters.AddWithValue(row.Generation);
            command.Parameters.AddWithValue(row.Revision);
            command.Parameters.AddWithValue((int)row.Kind);
            command.Parameters.AddWithValue((int)row.Power);
            command.Parameters.AddWithValue((int)row.Camp);
            if (await command.ExecuteNonQueryAsync(cancellationToken) != 1) return false;
        }
        await transaction.CommitAsync(cancellationToken);
        return true;
    }
}
