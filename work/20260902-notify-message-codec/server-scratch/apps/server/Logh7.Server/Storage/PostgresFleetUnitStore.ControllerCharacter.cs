using Logh7.Server.OriginalGateway;

namespace Logh7.Server.Storage;

public sealed record OriginalFleetControllerCharacter(CharacterReadRecord Character, uint UnitId, ushort? CardId);

public sealed partial class PostgresFleetUnitStore
{
    // Public tactical projection only. Requires this exact persisted fleet
    // assignment/incarnation; no arbitrary cross-account character lookup.
    // Private account fields and command-point balances are not selected.
    public async Task<OriginalFleetControllerCharacter?> ReadControllerCharacterAsync(
        OriginalFleetUnitRecord row, CancellationToken cancellationToken)
    {
        await using var command=dataSource.CreateCommand("""
            SELECT c.character_id,c.slot,c.faction,c.blood,c.sex,c.last_name,c.first_name,
                c.flagship_name,c.face,c.ability_values,c.rank,c.flagship_type,c.flagship_kind,
                c.return_base_id,c.achievement,g.unit_id,k.card_id
            FROM original_fleet_unit f
            JOIN character c ON c.character_id=f.controller_character_id AND c.faction=f.power
            JOIN original_grid_unit g ON g.character_id=c.character_id AND g.account_id=c.account_id
                AND g.unit_id=f.controller_unit_id AND g.ship_generation=f.controller_ship_generation
                AND g.injury_return_id IS NULL
            LEFT JOIN original_character_card k ON k.character_id=c.character_id AND k.account_id=c.account_id
            WHERE f.unit_id=$1 AND f.grid_id=$2 AND f.ship_generation=$3 AND f.revision=$4
                AND f.controller_character_id=$5
            """);
        command.Parameters.AddWithValue((long)row.UnitId);
        command.Parameters.AddWithValue((long)row.GridId);
        command.Parameters.AddWithValue(row.Generation);
        command.Parameters.AddWithValue(row.Revision);
        command.Parameters.AddWithValue(row.ControllerCharacterId ?? 0);
        await using var reader=await command.ExecuteReaderAsync(cancellationToken);
        if(!await reader.ReadAsync(cancellationToken)) return null;
        return new(new CharacterReadRecord(reader.GetInt64(0),reader.GetInt16(1),reader.GetInt16(2),
            reader.GetInt16(3),reader.GetInt16(4),reader.GetString(5),reader.GetString(6),reader.GetString(7),
            reader.GetInt32(8),reader.GetFieldValue<short[]>(9),reader.GetInt16(10),
            reader.IsDBNull(11)?null:checked((byte)reader.GetInt16(11)),
            reader.IsDBNull(12)?null:checked((ushort)reader.GetInt32(12)),checked((uint)reader.GetInt64(13)),
            Achievement:checked((uint)reader.GetInt64(14))),checked((uint)reader.GetInt64(15)),
            reader.IsDBNull(16)?null:checked((ushort)reader.GetInt32(16)));
    }
}
