namespace Logh7.Server.OriginalGateway;

/// <summary>
/// Minimal editable data used to replace otherwise empty original-service
/// records. These values are AUTHORED_PLACEHOLDER, not original game facts.
/// </summary>
public static class OriginalAuthoredPlayableCatalog
{
    // Existing 0309 shield[11][9].fillup.time default, shared with 0341
    // initial remaining intervals. AUTHORED_PLACEHOLDER, not original balance.
    public const uint TacticalShieldRecoveryPeriod = 100;
    public const ushort AuthorityCardId = 39;
    public const ushort TacticalTotalPower = 100;

    // AUTHORED_PLACEHOLDER, explicitly approved 2026-09-06 (E072).
    // These 0..100 relative hit scores are NOT recovered original percentages.
    // Manual PDF page50 supports close/mid beam+gun vs long missile roles,
    // while pages79/90 contain SHIP power, not these eight distance-bin values.
    // Row labels follow constmsg group116; numeric curves remain new design.
    // This supplies client range/score data, not a server hit/damage simulation.
    public static OriginalStaticArmsTable TacticalArms { get; } = new(
    [
        [80, 80, 70, 55, 35, 15,  0,  0], // 00 photon
        [65, 65, 55, 40, 20,  0,  0,  0], // 01 old photon (current authored beam)
        [90, 85, 65, 30,  0,  0,  0,  0], // 02 photon pulse
        [75, 70, 50, 20,  0,  0,  0,  0], // 03 old photon pulse
        [85, 80, 60, 40, 20,  0,  0,  0], // 04 neutron beam
        [70, 65, 45, 25,  0,  0,  0,  0], // 05 old neutron beam
        [90, 80, 50,  0,  0,  0,  0,  0], // 06 neutron pulse
        [75, 65, 35,  0,  0,  0,  0,  0], // 07 old neutron pulse
        [95, 70, 30,  0,  0,  0,  0,  0], // 08 railgun
        [80, 55, 20,  0,  0,  0,  0,  0], // 09 old railgun
        [95, 90, 55, 20,  0,  0,  0,  0], // 10 rail cannon
        [80, 75, 40, 10,  0,  0,  0,  0], // 11 old rail cannon
        [10, 25, 50, 70, 80, 80, 70, 50], // 12 laser fusion missile
        [ 5, 15, 35, 55, 65, 65, 50, 30], // 13 old laser fusion missile
        [20, 45, 70, 80, 70, 45,  0,  0], // 14 neutron missile
        [10, 30, 55, 65, 55, 30,  0,  0], // 15 old neutron missile
        [90, 60, 20,  0,  0,  0,  0,  0], // 16 anti-air
        [80, 75, 60, 40, 20,  0,  0,  0], // 17 combat satellite
        [30, 40, 55, 70, 85, 90, 90, 80], // 18 Thor long
        [90, 90, 85, 75, 60, 40,  0,  0], // 19 Thor normal
        [90, 85, 70, 50,  0,  0,  0,  0], // 20 Thor wide
        [30, 40, 55, 70, 85, 90, 90, 80], // 21 Geiershaken long
        [90, 90, 85, 75, 60, 40,  0,  0], // 22 Geiershaken normal
        [90, 85, 70, 50,  0,  0,  0,  0], // 23 Geiershaken wide
        [20, 30, 45, 60, 75, 80, 80, 70], // 24 fortress long
        [80, 80, 75, 65, 50, 30,  0,  0], // 25 fortress normal
        [80, 75, 60, 40,  0,  0,  0,  0], // 26 fortress wide
    ]);

    // Shared authored template, not recovered named-ship balance. The wire
    // catalog and the firing authority must use the same equipped arms IDs.
    public static OriginalStaticUnitShipCapabilities TacticalShipCapabilities { get; } = new(
        Navigation: 100, Speed: 1, Turn: 1,
        ArmorFront: 100, ArmorBack: 100, ArmorSide: 100,
        // 004C1700 indexes nine shield recovery columns by (Shield-10)/10.
        // NEW DESIGN: top authored grade90 replaces out-of-range100; capacity is independent.
        Shield: 90, ShieldCapacity: 100,
        // Preserve the six active bits of former B4, not an invented 180-degree arc.
        BeamArms: 1, BeamPower: 25, BeamAngleMask: 0x34,
        Number: 100, Existence: 100, TotalPower: TacticalTotalPower,
        CommunicationRange: 100, SearchingRange: 100);

    // PROBE (2026-09-03): the card id carried by the world-entry character record. constmsg group 3 row 0 is 個人
    // ("private individual"), i.e. the ORIGINAL's own name for "holds no post" — card 0 is a defined state, not an
    // undefined one, and EncodeStaticCards already serves card 0 with zero commands. LOGH7_WORLD_CARD_ID serves an
    // arbitrary card so the unmodified client can be observed rendering a post-less character, which is the target
    // state of 辞任 (0x0709). Default = the authored AuthorityCardId.
    public static ushort WorldCardId =>
        ushort.TryParse(Environment.GetEnvironmentVariable("LOGH7_WORLD_CARD_ID"), out var id) ? id : AuthorityCardId;
    // NEW DESIGN: bind the original static command identity for strategic
    // WARP to the authored authority card. This is not evidence that the
    // original service assigned command 0x2B to original card 39.
    public const ushort StrategicWarpCommandId = 0x002b;
    public const uint ResolvedAuthorityCardMailId = 0x7f000027;
    public const byte StartingRank = 20;
    public const uint BaseId = 1;
    public const string BaseName = "第1拠点";
    public const uint TacticalEnemyUnitId = 0x7f000001;
    public const uint TacticalEnemyCharacterId = 0x7f000002;

    // AUTHORED_PLACEHOLDER / NEW DESIGN: this is one deliberately small
    // selected-system scene, not a recovered member of the original 80/281/6
    // catalogs. Marker indexes the 0x0313 palette. The content ID has no
    // proven join to BaseId and is an independent authored palette value.
    public const ushort CurrentGridCell = 101;
    // NEW DESIGN: a second adjacent scene cell provides a distinct authored
    // destination only. It does not assert a route, distance, or movement rule.
    public const ushort DestinationGridCell = 102;
    public const byte PlanetMarker = 3;
    public const byte PlanetContentId = 1;
    public const byte PlanetKlass = 3;
    public const byte PlanetVariant = 0;
    public const byte BaseKlass = 1;
    public const float BaseRevolutionRadius = 1.0f;
    public const uint BaseRevolutionCycle = 1;
    public const byte BaseRevolutionDirection = 0;
    public const float BaseRevolutionInitAngle = 0.5f;
    //031D final field is diameter; native renderer divides by2 (E013).
    public const float BaseDiameter = 1.0f;
}
