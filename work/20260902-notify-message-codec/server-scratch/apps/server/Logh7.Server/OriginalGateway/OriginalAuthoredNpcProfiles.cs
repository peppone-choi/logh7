namespace Logh7.Server.OriginalGateway;

// Authored neutral values for unrecovered biography/ability fields. Not an
// original-server balance claim. No account/session/viewer data is accepted.
public static class OriginalAuthoredNpcProfiles
{
    public static OriginalCreateCharacterCommand Defaults() =>
        new(0, 0, 0, 0, 0, string.Empty, string.Empty, 0, 0, 0, 0,
            new byte[8], 0, 0, 0, OriginalAuthoredPlayableCatalog.StartingRank,
            0, 0, string.Empty, 0, []);

    public static OriginalCreateCharacterCommand FleetCommander(OriginalBattlefieldFleet fleet, string commander) =>
        Defaults() with
        {
            CharacterId = fleet.Id,
            Power = fleet.Power,
            Rank = fleet.CommanderRank,
            Achievement = fleet.CommanderAchievement,
            LastName = commander,
            FlagshipKind = fleet.Ships.Count > 0 ? fleet.Ships[0].Kind : (ushort)0,
        };
}
