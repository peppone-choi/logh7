namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    private bool HasUnsafeBattlefield(uint grid, uint ownUnit, byte power)
    {
        var field = ActiveBattlefieldCatalog.Resolve(grid);
        // Retain the existing primary-NPC recovery restriction. Authored fleet
        // presence additionally accounts for camps and not-yet-instantiated rows.
        if (field.SpawnEnemy ||
            _battles.NpcSnapshot(grid,OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId) is not null)
            return true;
        var encounter = _battles.GetEncounter(grid,OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number);
        if (_battles.OtherParticipants(grid,ownUnit).Any(p =>
            p.IsHostileTo(power,0) && encounter.HasSurvivors(p.Unit.Id)))
            return true;
        return (field.Fleets ?? []).Where(f => f.Power != power || f.Camp != 0)
            .SelectMany(f => f.Ships).Any(s =>
                _battles.NpcSnapshot(grid,s.Id) is null || encounter.HasSurvivors(s.Id));
    }
}
