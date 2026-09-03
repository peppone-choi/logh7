namespace Logh7.Server.OriginalGateway;

/// <summary>
/// Minimal editable data used to replace otherwise empty original-service
/// records. These values are AUTHORED_PLACEHOLDER, not original game facts.
/// </summary>
public static class OriginalAuthoredPlayableCatalog
{
    public const ushort AuthorityCardId = 39;
    // NEW DESIGN: bind the original static command identity for strategic
    // WARP to the authored authority card. This is not evidence that the
    // original service assigned command 0x2B to original card 39.
    public const ushort StrategicWarpCommandId = 0x002b;
    public const uint ResolvedAuthorityCardMailId = 0x7f000027;
    public const byte StartingRank = 20;
    public const uint BaseId = 1;
    public const string BaseName = "第1拠点";

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
    public const float BaseRadius = 1.0f;
}
