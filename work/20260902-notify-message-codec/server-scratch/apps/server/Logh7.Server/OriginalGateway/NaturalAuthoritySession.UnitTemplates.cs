namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    private string? ResolveSceneUnitComplements(out IReadOnlyDictionary<ushort, ushort> numbers)
    {
        var requested = new List<(ushort Kind, ushort Number)>();
        var encounter = _battles.GetEncounter(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number);
        if (_createdCharacter is { } character)
            requested.Add((character.FlagshipKind, _persistedGridUnit?.UnitNumber ??
                encounter.UnitNumber(_worldGridUnitId)));
        if (HasPrimaryTacticalNpc)
            requested.Add((CurrentEnemyRegularShipKind(),
                encounter.UnitNumber(OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId)));
        requested.AddRange(OtherBattleParticipants().Select(p => (p.Unit.Kind, encounter.UnitNumber(p.Unit.Id))));
        // Hull count is per kind, not one number for every subordinate ship.
        // Asserting the ordinary 300 here contradicted the flagship-class
        // picket's own template, and the conflict left the client hanging on
        // NOW LOADING because the whole grid bootstrap was refused.
        requested.AddRange((CurrentBattlefieldTemplate().Fleets ?? []).SelectMany(f => f.Ships)
            .Select(s => (s.Kind, OriginalSubordinateShipCatalog.ComplementFor(s.Kind))));
        numbers = new Dictionary<ushort, ushort>();
        if (requested.GroupBy(r => r.Kind).Any(g => g.Select(r => r.Number).Distinct().Count() != 1))
            return "original.unit-template.complement-conflict";
        if (requested.Any(r => r.Kind is not (0 or 3 or 89 or 93) && !OriginalSubordinateShipCatalog.Supports(r.Kind)))
            return "original.unit-template.missing-kind";
        numbers = requested.Distinct().ToDictionary(r => r.Kind, r => r.Number);
        return null;
    }
}
