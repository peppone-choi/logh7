namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    private async Task<NaturalAuthoritySessionResult> ProcessFleetPartyQueryAsync(byte[] payload,
        CancellationToken cancellationToken)
    {
        if (!OriginalOutfitPartyCodec.TryDecodeRequest(payload, out var request))
            return Invalid("original.outfit-party.payload", OriginalOutfitPartyCodec.RequestType);
        // NEW_DESIGN read-only deployed-field view, not the unrecovered base inventory/
        // allocation service. Only mode1 was observed at original provider005685B0.
        if (request.Mode != 1 || request.BaseId != 0 || IsRecoveringFromInjury)
            return Invalid("original.outfit-party.unsupported-context", OriginalOutfitPartyCodec.RequestType);
        using var lease = await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
        var members = OtherBattleParticipants().Where(p => p.Unit.Outfit == request.OutfitId &&
            p.Outfit.HasValue).ToArray();
        if (members.Length == 0)
            return Invalid("original.outfit-party.unknown-id", OriginalOutfitPartyCodec.RequestType);
        var info = members[0].Outfit!.Value;
        var ships = members.Select(p => _tacticalEncounter.ProjectUnit(p.Unit))
            .Where(u => _tacticalEncounter.HasSurvivors(u.Id)).OrderBy(u => u.Id)
            .GroupBy(u => u.Kind).SelectMany(group => group.Chunk(OriginalOutfitPartyCodec.MaximumShipUnits)
                .Select(chunk => new OriginalOutfitPartyShip(group.Key, checked((byte)chunk.Length),
                    checked((ushort)chunk.Sum(u => _tacticalEncounter.UnitNumber(u.Id) - u.Destroyed)),
                    chunk.Select(u => u.Id).ToArray()))).ToArray();
        var response = new OriginalOutfitPartyResponse(info.Id, 0, request.Mode, info.Power, info.Camp,
            info.Kind, info.Index, [], ships, [], 0, 0, 0, [], [], 0, 0, 0, [], []);
        return EncodeApplicationResponse(OriginalOutfitPartyCodec.EncodeResponse(response),
            OriginalOutfitPartyCodec.RequestType, includeLobbyPrefix: true) with
        {
            ResponseMetadata = $"outfit-party;outfit={info.Id};policy=authored-field-membership;inventory=unrecovered"
        };
    }
}
