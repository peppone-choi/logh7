using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    // Called under the scene's grid lease before publishing any scene records.
    private async Task RestoreFleetRosterAsync(CancellationToken cancellationToken)
    {
        if (IsRecoveringFromInjury || _store is not IOriginalFleetUnitStoreProvider provider) return;
        var store = provider.FleetUnits;
        var ownCorps=await store.ReadControllerCorpsAsync(_worldCharacterId,cancellationToken);
        if(ownCorps is { } own)
        {
            if(own.Power!=_createdCharacter?.Power)
                throw new InvalidDataException("PLAYER_CORPS_POWER_CONFLICT");
            // An accepted in-process distribution remains newer than this
            // restart snapshot. A fresh session restores the durable value.
            _tacticalPlayerCorps ??= own.Corps;
        }
        await OriginalFleetRosterRestorer.RestoreAsync(store, _battles, ActiveBattlefieldCatalog,
            _worldGridCellId, _staticArms ?? OriginalAuthoredPlayableCatalog.TacticalArms,
            CommanderFrame, cancellationToken,
            (character, saved) => character == _worldCharacterId ? CurrentPlayerCorps : saved);
    }
}
