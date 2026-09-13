using Npgsql;
using NpgsqlTypes;
using Logh7.Server.OriginalGateway;

namespace Logh7.Server.Storage;

public interface IOriginalFleetUnitStoreProvider
{
    PostgresFleetUnitStore FleetUnits { get; }
}

public sealed record OriginalFleetUnitRecord(uint UnitId, uint OutfitId, ushort Kind, byte Power,
    byte Camp, uint GridId, ushort Number, ushort Damaged, ushort Destroyed,
    float X, float Y, float Z, float Direction, float Cruising,
    long? ControllerCharacterId = null, bool Autonomous = true, long Generation = 0, long Revision = 1,
    uint? ControllerUnitId=null,long? ControllerGeneration=null,uint Supplies=100);

public sealed partial class PostgresFleetUnitStore(NpgsqlDataSource dataSource)
{
    internal bool SharesDataSource(PostgresFleetUnitStore other) => ReferenceEquals(dataSource,other.DataSource);
    private NpgsqlDataSource DataSource => dataSource;
    // Caller performs command authorization. This transaction changes control
    // only; it must never overwrite pose or casualties captured before an await.
    public async Task<bool> SaveControlAssignmentsAsync(IReadOnlyList<OriginalFleetControlWrite> assignments,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(assignments);
        var writes=assignments.OrderBy(row=>row.UnitId).ToArray();
        if (writes.Length==0 || writes.Select(row=>row.UnitId).Distinct().Count()!=writes.Length) return false;
        var corpsWrites=new Dictionary<uint,(string Json,uint Unit,long Generation)>();
        foreach(var write in writes)
            if(write.Corps is { } corps)
            {
                if(write.ControllerCharacterId != corps.Id || write.ControllerUnitId is not { } unit ||
                    unit==0 || write.ControllerGeneration is not { } generation || generation<0) return false;
                var json=SerializeCorps(corps);
                var value=(json,unit,generation);
                if(corpsWrites.TryGetValue(corps.Id,out var previous) && previous!=value) return false;
                corpsWrites[corps.Id]=value;
            }
        await using var connection=await dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction=await connection.BeginTransactionAsync(cancellationToken);
        foreach(var (character,value) in corpsWrites.OrderBy(pair=>pair.Key))
            if(!await LockControllerAsync(connection,transaction,character,value.Unit,value.Generation,cancellationToken))
                return false;
        foreach (var write in writes)
        {
            await using var command=new NpgsqlCommand("""
                UPDATE original_fleet_unit SET controller_character_id=$6,autonomous=$7,
                    controller_unit_id=$8,controller_ship_generation=$9,
                    revision=revision+1,updated_at=transaction_timestamp()
                WHERE unit_id=$1 AND grid_id=$2 AND outfit_id=$3 AND ship_generation=$4 AND revision=$5
                """,connection,transaction);
            command.Parameters.AddWithValue((long)write.UnitId);
            command.Parameters.AddWithValue((long)write.GridId);
            command.Parameters.AddWithValue((long)write.OutfitId);
            command.Parameters.AddWithValue(write.ExpectedGeneration);
            command.Parameters.AddWithValue(write.ExpectedRevision);
            command.Parameters.AddWithValue(NpgsqlDbType.Bigint,(object?)write.ControllerCharacterId ?? DBNull.Value);
            command.Parameters.AddWithValue(write.Autonomous);
            command.Parameters.AddWithValue(NpgsqlDbType.Bigint,(object?)(long?)write.ControllerUnitId ?? DBNull.Value);
            command.Parameters.AddWithValue(NpgsqlDbType.Bigint,(object?)write.ControllerGeneration ?? DBNull.Value);
            if (await command.ExecuteNonQueryAsync(cancellationToken)!=1) return false;
        }
        foreach(var (character,value) in corpsWrites.OrderBy(pair=>pair.Key))
            await WriteCorpsAsync(connection,transaction,character,value.Json,value.Unit,value.Generation,cancellationToken);
        await transaction.CommitAsync(cancellationToken);
        return true;
    }
    // Trusted persistence boundary, not network authorization. Existing rows win
    // over authored startup defaults, including units that moved to another grid.
    public async Task EnsureCreatedAsync(OriginalFleetUnitRecord initial, CancellationToken cancellationToken)
    {
        await using var command = dataSource.CreateCommand("""
            INSERT INTO original_fleet_unit(unit_id,outfit_id,kind,power,camp,grid_id,unit_number,
                damaged,destroyed,x,y,z,direction,cruising,controller_character_id,autonomous,ship_generation,revision,
                controller_unit_id,controller_ship_generation,supplies)
            VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21)
            ON CONFLICT(unit_id) DO NOTHING
            """);
        Bind(command, initial);
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    /// <summary>
    /// Applies an authored template change (kind and hull count) to a unit that
    /// has never been touched: no casualties, no controller, still at its first
    /// incarnation and first revision. A unit that has already fought keeps its
    /// stored identity and the caller must reject the conflict instead.
    /// </summary>
    public async Task<bool> ReconcileUntouchedTemplateAsync(uint unit, ushort kind, ushort complement,
        uint grid, CancellationToken cancellationToken)
    {
        if (unit == 0 || complement == 0 || grid == 0) throw new ArgumentOutOfRangeException(nameof(complement));
        await using var command = dataSource.CreateCommand("""
            UPDATE original_fleet_unit SET kind=$2,unit_number=$3,grid_id=$4,revision=revision+1,
                updated_at=transaction_timestamp()
            WHERE unit_id=$1 AND damaged=0 AND destroyed=0 AND ship_generation=0
                AND revision=1
                AND controller_character_id IS NULL
                AND (kind<>$2 OR unit_number<>$3 OR grid_id<>$4)
            """);
        command.Parameters.AddWithValue((long)unit);
        command.Parameters.AddWithValue((int)kind);
        command.Parameters.AddWithValue((int)complement);
        command.Parameters.AddWithValue((long)grid);
        return await command.ExecuteNonQueryAsync(cancellationToken) == 1;
    }

    public async Task<IReadOnlyList<OriginalFleetUnitRecord>> ReadGridAsync(uint grid, CancellationToken cancellationToken)
    {
        await using var command = dataSource.CreateCommand("""
            SELECT unit_id,outfit_id,kind,power,camp,grid_id,unit_number,damaged,destroyed,
                x,y,z,direction,cruising,controller_character_id,autonomous,ship_generation,revision,
                controller_unit_id,controller_ship_generation,supplies
            FROM original_fleet_unit WHERE grid_id=$1 ORDER BY unit_id
            """);
        command.Parameters.AddWithValue((long)grid);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        var records = new List<OriginalFleetUnitRecord>();
        while (await reader.ReadAsync(cancellationToken))
            records.Add(new(checked((uint)reader.GetInt64(0)), checked((uint)reader.GetInt64(1)),
                checked((ushort)reader.GetInt32(2)), checked((byte)reader.GetInt32(3)), checked((byte)reader.GetInt32(4)),
                checked((uint)reader.GetInt64(5)), checked((ushort)reader.GetInt32(6)),
                checked((ushort)reader.GetInt32(7)), checked((ushort)reader.GetInt32(8)),
                reader.GetFloat(9), reader.GetFloat(10), reader.GetFloat(11), reader.GetFloat(12), reader.GetFloat(13),
                reader.IsDBNull(14) ? null : reader.GetInt64(14), reader.GetBoolean(15), reader.GetInt64(16), reader.GetInt64(17),
                reader.IsDBNull(18) ? null : checked((uint)reader.GetInt64(18)),reader.IsDBNull(19) ? null : reader.GetInt64(19),
                checked((uint)reader.GetInt64(20))));
        return records;
    }

    // Startup must restore moved fleets as well as authored spawn grids.
    // Include defeated rows too: restoration must preserve their casualties.
    public async Task<IReadOnlyList<uint>> ReadOccupiedGridsAsync(CancellationToken cancellationToken)
    {
        await using var command = dataSource.CreateCommand(
            "SELECT DISTINCT grid_id FROM original_fleet_unit ORDER BY grid_id");
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        var grids = new List<uint>();
        while (await reader.ReadAsync(cancellationToken))
            grids.Add(checked((uint)reader.GetInt64(0)));
        return grids;
    }

    // Revision is the caller's expected version. New revision is expected+1.
    // Generation and immutable membership must match; no implicit reincarnation.
    public async Task<bool> SaveAsync(OriginalFleetUnitRecord state, CancellationToken cancellationToken)
    {
        await using var command = dataSource.CreateCommand("""
            UPDATE original_fleet_unit SET grid_id=$6,unit_number=$7,damaged=$8,destroyed=$9,
                x=$10,y=$11,z=$12,direction=$13,cruising=$14,controller_character_id=$15,autonomous=$16,
                controller_unit_id=$19,controller_ship_generation=$20,supplies=$21,
                revision=revision+1,updated_at=transaction_timestamp()
            WHERE unit_id=$1 AND outfit_id=$2 AND kind=$3 AND power=$4 AND camp=$5
                AND ship_generation=$17 AND revision=$18
            """);
        Bind(command, state);
        return await command.ExecuteNonQueryAsync(cancellationToken) == 1;
    }

    private static void Bind(NpgsqlCommand command, OriginalFleetUnitRecord state)
    {
        command.Parameters.AddWithValue((long)state.UnitId);
        command.Parameters.AddWithValue((long)state.OutfitId);
        command.Parameters.AddWithValue((int)state.Kind);
        command.Parameters.AddWithValue((int)state.Power);
        command.Parameters.AddWithValue((int)state.Camp);
        command.Parameters.AddWithValue((long)state.GridId);
        command.Parameters.AddWithValue((int)state.Number);
        command.Parameters.AddWithValue((int)state.Damaged);
        command.Parameters.AddWithValue((int)state.Destroyed);
        command.Parameters.AddWithValue(state.X);
        command.Parameters.AddWithValue(state.Y);
        command.Parameters.AddWithValue(state.Z);
        command.Parameters.AddWithValue(state.Direction);
        command.Parameters.AddWithValue(state.Cruising);
        command.Parameters.AddWithValue(NpgsqlDbType.Bigint, (object?)state.ControllerCharacterId ?? DBNull.Value);
        command.Parameters.AddWithValue(state.Autonomous);
        command.Parameters.AddWithValue(state.Generation);
        command.Parameters.AddWithValue(state.Revision);
        command.Parameters.AddWithValue(NpgsqlDbType.Bigint,(object?)(long?)state.ControllerUnitId ?? DBNull.Value);
        command.Parameters.AddWithValue(NpgsqlDbType.Bigint,(object?)state.ControllerGeneration ?? DBNull.Value);
        command.Parameters.AddWithValue((long)state.Supplies);
    }
}

public readonly record struct OriginalFleetControlWrite(uint UnitId,uint GridId,uint OutfitId,
    long ExpectedGeneration,long ExpectedRevision,long? ControllerCharacterId,bool Autonomous,
    OriginalTacticalCorpsRecord? Corps=null,uint? ControllerUnitId=null,long? ControllerGeneration=null);
