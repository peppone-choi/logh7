using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public static class OriginalFleetRosterRestorer
{
    // Caller holds the target grid lease; no session/account is required.
    public static async Task RestoreAsync(PostgresFleetUnitStore store,
        OriginalTacticalBattleRegistry battles, OriginalBattlefieldCatalog catalog,
        uint targetGrid, OriginalStaticArmsTable arms,
        Func<OriginalBattlefieldFleet?, uint, uint, byte[]?> commanderFrame,
        CancellationToken cancellationToken,
        Func<uint, OriginalTacticalCorpsRecord, OriginalTacticalCorpsRecord>? corpsOverride = null)
    {
        var caps = OriginalSubordinateShipCatalog.Capabilities;
        // Each authored ship states its own hull count; only an unauthored one
        // falls back to the ordinary-unit default.
        ushort Complement(uint unit) => catalog.ShipComplement(unit, caps.Number);
        foreach (var fleet in catalog.Resolve(targetGrid).Fleets ?? [])
            foreach (var participant in fleet.Project(targetGrid))
                await store.EnsureCreatedAsync(new(participant.Unit.Id, fleet.Id, participant.Unit.Kind,
                    fleet.Power, fleet.Camp, targetGrid, Complement(participant.Unit.Id), 0, 0,
                    participant.Ship.X, participant.Ship.Y, participant.Ship.Z, participant.Ship.Direction,
                    participant.Unit.Cruising, Supplies: participant.Unit.Supplies), cancellationToken);

        // An authored template change reaches a unit that has never fought,
        // including one the content has moved to a different grid.
        foreach (var (ship, kind, grid) in catalog.AuthoredShips())
            await store.ReconcileUntouchedTemplateAsync(ship, kind, Complement(ship), grid, cancellationToken);
        var rows = await store.ReadGridAsync(targetGrid, cancellationToken);
        var prepared = new List<(OriginalFleetUnitRecord Row, OriginalTacticalParticipantSnapshot Snapshot)>();
        var controllers=new Dictionary<uint,(byte Power,OriginalTacticalCorpsRecord Corps)>();
        foreach (var savedRow in rows)
        {
            var row=savedRow;
            var source = catalog.ProjectFleetUnit(row.UnitId, row.GridId)
                ?? throw new InvalidDataException("FLEET_UNIT_CATALOG_MISSING");
            if (source.Unit.Outfit != row.OutfitId || source.Unit.Kind != row.Kind ||
                source.Power != row.Power || source.Camp != row.Camp ||
                row.Number != Complement(row.UnitId))
                throw new InvalidDataException("FLEET_UNIT_CATALOG_CONFLICT");
            battles.ValidateFleetControllerReconciliation(row);
            row=await store.ReleaseUnavailableControllerAsync(row,cancellationToken);
            if(row!=savedRow) battles.ApplyReleasedFleetController(row,source);
            var corps=source.Corps;
            var character=source.Ship.Character;
            byte[]? controllerFrame=null;
            OriginalTacticalCommanderMerit? controllerMerit=null;
            if(row.ControllerCharacterId is { } controller)
            {
                character=checked((uint)controller);
                if(!controllers.TryGetValue(character,out var saved))
                {
                    saved=await store.ReadControllerCorpsAsync(character,cancellationToken)
                        ?? throw new InvalidDataException("FLEET_CONTROLLER_CORPS_MISSING");
                    controllers.Add(character,saved);
                }
                if(saved.Power!=row.Power) throw new InvalidDataException("FLEET_CONTROLLER_POWER_CONFLICT");
                corps=corpsOverride?.Invoke(character, saved.Corps) ?? saved.Corps;
                var publicController=await store.ReadControllerCharacterAsync(row,cancellationToken)
                    ?? throw new InvalidDataException("FLEET_CONTROLLER_CHARACTER_UNAVAILABLE");
                var publicCharacter=OriginalStoredCharacterProjection.Create(publicController.Character);
                controllerFrame=OriginalWorldEntryCodec.EncodeCharacter(character,publicController.UnitId,
                    publicController.CardId ?? OriginalAuthoredPlayableCatalog.WorldCardId,publicCharacter);
                controllerMerit=new(character,publicCharacter.Rank,publicCharacter.Achievement);
            }
            else if (!row.Autonomous)
                throw new InvalidDataException("FLEET_CONTROLLER_RESTORE_UNAVAILABLE");
            prepared.Add((row, new(source.Unit with { Damaged = row.Damaged, Destroyed = row.Destroyed,
                    Cruising = row.Cruising, Supplies = row.Supplies },
                source.Ship with { Character=character,X = row.X, Y = row.Y, Z = row.Z, Direction = row.Direction },
                corps,
                // A fleet still flying under its own commander is labelled by the
                // content's name; one a player has taken over keeps that
                // controller's own character record.
                (row.ControllerCharacterId is null
                    ? commanderFrame(catalog.FindOutfit(row.OutfitId), row.UnitId, character)
                    : controllerFrame) ?? source.CharacterFrame.ToArray(),
                source.Power, row.Generation, source.Outfit,
                row.ControllerCharacterId is null ? source.CommanderMerit : controllerMerit)));
        }
        foreach (var (row, snapshot) in prepared)
        {
            // Scene refresh must not roll ongoing combat back to saved state.
            if (battles.NpcSnapshot(targetGrid, row.UnitId) is not null)
            {
                if(!battles.RefreshNpcCharacter(targetGrid,snapshot))
                    throw new InvalidDataException("FLEET_CONTROLLER_PROFILE_CONFLICT");
                continue;
            }
            if (!battles.ObserveShipGeneration(row.UnitId, row.Generation))
                throw new InvalidDataException("FLEET_UNIT_GENERATION_STALE");
            battles.RegisterNpc(snapshot, OriginalSubordinateShipCatalog.CapabilitiesFor(row.Kind),
                arms,
                catalog.ProjectBaseObjectives(targetGrid),
                catalog.IsDefensiveOutfit(row.OutfitId));
            if(row.ControllerCharacterId is not null)
                battles.ApplyNpcControlAssignment(targetGrid,row.UnitId,snapshot.Ship.Character,
                    snapshot.Corps,row.Autonomous);
            battles.GetEncounter(targetGrid, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number)
                .RecordUnitDamage(row.UnitId, new(row.Damaged, row.Destroyed));
            battles.BindFleetUnitPersistence(row,store,catalog.ProjectFleetUnit(row.UnitId,row.GridId));
        }
    }
}
