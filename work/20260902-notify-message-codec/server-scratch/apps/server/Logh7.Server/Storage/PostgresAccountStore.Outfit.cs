using Logh7.Server.OriginalGateway;

namespace Logh7.Server.Storage;

public sealed record OriginalOutfitMembership(long CharacterId,OriginalInformationOutfit Outfit,
    long MembershipRevision,long OutfitRevision);

public sealed partial class PostgresAccountStore
{
    public async Task<OriginalOutfitMembership?> FindOriginalOutfitMembershipAsync(Guid accountId,long characterId,
        CancellationToken cancellationToken)
    {
        await using var command = _dataSource.CreateCommand("""
            SELECT o.outfit_id,o.kind,o.power,o.camp,o.outfit_index,o.achievement,o.strategy_id,
                   o.practice,m.revision,o.revision
            FROM character c
            JOIN original_outfit_member m ON m.character_id=c.character_id
            JOIN original_outfit o ON o.outfit_id=m.outfit_id
            WHERE c.account_id=$1 AND c.character_id=$2
            """);
        command.Parameters.AddWithValue(accountId);
        command.Parameters.AddWithValue(characterId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken)) return null;
        var practice = reader.GetFieldValue<byte[]>(7);
        var outfit = new OriginalInformationOutfit(
            checked((uint)reader.GetInt64(0)),checked((byte)reader.GetInt16(1)),
            checked((byte)reader.GetInt16(2)),checked((byte)reader.GetInt16(3)),
            checked((byte)reader.GetInt16(4)),checked((ushort)reader.GetInt32(5)),
            checked((uint)reader.GetInt64(6)),practice[0],practice[1],practice[2],practice[3],
            practice[4],practice[5],practice[6],practice[7],practice[8],practice[9]);
        return new(characterId,outfit,reader.GetInt64(8),reader.GetInt64(9));
    }
}
