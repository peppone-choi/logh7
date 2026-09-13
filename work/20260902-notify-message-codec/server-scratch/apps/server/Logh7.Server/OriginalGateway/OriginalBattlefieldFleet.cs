namespace Logh7.Server.OriginalGateway;

// NEW_DESIGN placement/IDs. Ordinary ships share the fleet command identity;
// no fictional InformationCharacter row is manufactured per ship.
// Complement is the number of hulls in this unit. The manual's unit section
// gives 1 hull for a flagship unit and 300 for an ordinary vessel unit, so an
// authored escort or flagship-class opponent states its own count instead of
// silently inheriting the ordinary-unit default.
public sealed record OriginalBattlefieldFleetShip(uint Id, ushort Kind, OriginalBattlefieldSpawn Spawn)
{
    public ushort? Complement { get; init; }
}
public sealed record OriginalBattlefieldFleet(uint Id, byte Power, byte Camp, byte Kind, byte Index,
    IReadOnlyList<OriginalBattlefieldFleetShip> Ships)
{
    // NEW_DESIGN posture, not a recovered original stance. "defend" holds the
    // outfit in place until one of its ships is actually hit in this
    // incarnation, so a player can choose when an engagement starts. Anything
    // else keeps the existing always-engage behaviour.
    public string? Posture { get; init; }
    public bool Defensive => string.Equals(Posture, "defend", StringComparison.Ordinal);

    // NEW_DESIGN support role, not a recovered original ship class. constmsg
    // group 0 says 修理 is 「工作艦による修理を行う 注）旗艦の右」 and 補給 is
    // 「補給艦による補給を行う 注）旗艦の左」 - both name the vessel that performs the
    // command and where it stands relative to the flagship. This authority does
    // not know which ship kind id is a 工作艦 or a 補給艦, so it does not pretend
    // to: the battlefield states the role in words, and the two commands require
    // an outfit that carries it. Recovering the real ship classes later replaces
    // this field; it does not change the commands.
    public string? Role { get; init; }
    public bool Repairs => string.Equals(Role, "repair", StringComparison.Ordinal);
    public bool Supplies => string.Equals(Role, "supply", StringComparison.Ordinal);

    // AUTHORED commander name. The tactical scene carries no unit name at all -
    // the ship record holds only a character id, and 0x0337 answers with ids -
    // so the client labels a fleet with its commander's name, taken from its own
    // character table. Without a character record for an authored fleet the
    // client has nothing to draw and the fleet goes unlabelled, which is what
    // this battlefield showed. The name is authored content, not recovered.
    public string? Commander { get; init; }
    // AUTHORED initial NPC merit, not inherited from whichever player views it.
    // Missing legacy content uses the existing starting rank and zero merit.
    public byte CommanderRank { get; init; } = OriginalAuthoredPlayableCatalog.StartingRank;
    public uint CommanderAchievement { get; init; }

    public OriginalInformationOutfit Information => new(Id, Kind, Power, Camp, Index, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public IEnumerable<OriginalTacticalParticipantSnapshot> Project(uint grid)
    {
        foreach (var row in Ships)
        {
            var spawn = row.Spawn;
            // Search must match the player's and the legacy enemy's scene record.
            // v270: with Search 0 the client builds the ship's pick node with
            // kind 0, and 004EF95B skips a kind-0 node before any flag, range or
            // arc test, so the unit can never be clicked as a target.
            var ship = OriginalSystemSceneCodec.CreateAuthoredTacticalUnitShip(row.Id, Id) with
            {
                X = spawn.X, Y = spawn.Y, Z = spawn.Z, Direction = spawn.Direction, Morale = 100,
                Search = 1
            };
            yield return new(new(row.Id, grid, 0, 100, 0, 0, 100, 100, 10, row.Kind,
                Outfit: Id), ship, OriginalSystemSceneCodec.CreatePlayableTacticalCorps(Id),
                [], Power, outfit: Information,
                commanderMerit: new(Id, CommanderRank, CommanderAchievement));
        }
    }
}
