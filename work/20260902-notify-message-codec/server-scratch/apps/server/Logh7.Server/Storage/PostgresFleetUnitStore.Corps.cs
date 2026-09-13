using System.Text.Json;
using Logh7.Server.OriginalGateway;
using Npgsql;
using NpgsqlTypes;

namespace Logh7.Server.Storage;

public sealed partial class PostgresFleetUnitStore
{
    // Called under the grid lease. The database row lock also excludes a
    // concurrent flagship replacement; compare old contents before overwriting.
    public async Task<bool> SavePlayerCorpsAsync(Guid account,uint unit,long generation,
        OriginalTacticalCorpsRecord before,OriginalTacticalCorpsRecord after,CancellationToken cancellationToken)
    {
        if(before.Id!=after.Id) return false;
        var expected=SerializeCorps(before);
        var next=SerializeCorps(after);
        await using var connection=await dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction=await connection.BeginTransactionAsync(cancellationToken);
        if(!await LockControllerAsync(connection,transaction,after.Id,unit,generation,cancellationToken,account))
            return false;
        await using var command=new NpgsqlCommand("""
            INSERT INTO original_tactical_corps(character_id,payload,controller_unit_id,ship_generation)
            VALUES($1,$2,$3,$4)
            ON CONFLICT(character_id) DO UPDATE SET payload=EXCLUDED.payload,
                controller_unit_id=EXCLUDED.controller_unit_id,ship_generation=EXCLUDED.ship_generation,
                revision=original_tactical_corps.revision+1
            WHERE original_tactical_corps.controller_unit_id IS DISTINCT FROM EXCLUDED.controller_unit_id
               OR original_tactical_corps.ship_generation IS DISTINCT FROM EXCLUDED.ship_generation
               OR original_tactical_corps.payload=$5
            """,connection,transaction);
        command.Parameters.AddWithValue((long)after.Id);
        command.Parameters.AddWithValue(NpgsqlDbType.Jsonb,next);
        command.Parameters.AddWithValue((long)unit);
        command.Parameters.AddWithValue(generation);
        command.Parameters.AddWithValue(NpgsqlDbType.Jsonb,expected);
        if(await command.ExecuteNonQueryAsync(cancellationToken)!=1) return false;
        await transaction.CommitAsync(cancellationToken);
        return true;
    }

    // Internal state lookup, not a client-facing read or grant of command rights.
    public async Task<(byte Power,OriginalTacticalCorpsRecord Corps)?> ReadControllerCorpsAsync(
        uint character,CancellationToken cancellationToken)
    {
        await using var command=dataSource.CreateCommand("""
            SELECT c.faction,t.payload::text FROM original_tactical_corps t
            JOIN character c ON c.character_id=t.character_id
            JOIN original_grid_unit g ON g.character_id=t.character_id
                AND g.unit_id=t.controller_unit_id AND g.ship_generation=t.ship_generation
            WHERE t.character_id=$1 AND t.format_version=1
            """);
        command.Parameters.AddWithValue((long)character);
        await using var reader=await command.ExecuteReaderAsync(cancellationToken);
        if(!await reader.ReadAsync(cancellationToken)) return null;
        var corps=JsonSerializer.Deserialize<OriginalTacticalCorpsRecord>(reader.GetString(1));
        if(corps.Id!=character) throw new InvalidDataException("TACTICAL_CORPS_ID_CONFLICT");
        _=SerializeCorps(corps);
        return (checked((byte)reader.GetInt16(0)),corps);
    }

    private static string SerializeCorps(OriginalTacticalCorpsRecord corps)
    {
        if(corps.Id==0 || !float.IsFinite(corps.CommandRange) || corps.CommandRange<0)
            throw new InvalidDataException("TACTICAL_CORPS_INVALID");
        // Validates all three six-element shield arrays against the wire schema.
        _=OriginalSystemSceneCodec.EncodeTacticalCorps(new([corps]));
        return JsonSerializer.Serialize(corps);
    }

    private static async Task<bool> LockControllerAsync(NpgsqlConnection connection,NpgsqlTransaction transaction,
        uint character,uint unit,long generation,CancellationToken cancellationToken,Guid? account=null)
    {
        await using var command=new NpgsqlCommand("""
            SELECT unit_id FROM original_grid_unit
            WHERE character_id=$1 AND unit_id=$2 AND ship_generation=$3
                AND injury_return_id IS NULL
                AND ($4::uuid IS NULL OR account_id=$4) FOR UPDATE
            """,connection,transaction);
        command.Parameters.AddWithValue((long)character);
        command.Parameters.AddWithValue((long)unit);
        command.Parameters.AddWithValue(generation);
        command.Parameters.AddWithValue(NpgsqlDbType.Uuid,(object?)account ?? DBNull.Value);
        return await command.ExecuteScalarAsync(cancellationToken) is not null;
    }

    private static async Task WriteCorpsAsync(NpgsqlConnection connection,NpgsqlTransaction transaction,
        uint character,string json,uint unit,long generation,CancellationToken cancellationToken)
    {
        await using var command=new NpgsqlCommand("""
            INSERT INTO original_tactical_corps(character_id,payload,controller_unit_id,ship_generation) VALUES($1,$2,$3,$4)
            ON CONFLICT(character_id) DO UPDATE SET payload=EXCLUDED.payload,
                controller_unit_id=EXCLUDED.controller_unit_id,ship_generation=EXCLUDED.ship_generation,
                revision=original_tactical_corps.revision+1
            """,connection,transaction);
        command.Parameters.AddWithValue((long)character);
        command.Parameters.AddWithValue(NpgsqlDbType.Jsonb,json);
        command.Parameters.AddWithValue((long)unit);
        command.Parameters.AddWithValue(generation);
        await command.ExecuteNonQueryAsync(cancellationToken);
    }
}
