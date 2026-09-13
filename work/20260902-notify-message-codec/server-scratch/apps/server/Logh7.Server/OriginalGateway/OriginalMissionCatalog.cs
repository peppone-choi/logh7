namespace Logh7.Server.OriginalGateway;

/// <summary>
/// The six missions 任務 (0x0421) and 具申 (0x0408) choose between, in the client's
/// own words.
/// </summary>
/// <remarks>
/// Two independently recovered readings meet here.
///
/// ORIGINAL_STATIC (already held): the mission sub-panel is palette items
/// 0x25..0x2A - six icons - and case 26 at 0x00511821 writes
/// <c>[0x0077A900] = 0..5</c>, one value per icon, before moving the palette to
/// command state 0x19. Mission 5 is the only one that carries no target: its branch
/// (0x0050F14E) calls the frame builder directly and skips the pick every other
/// mission must pass.
///
/// ORIGINAL_OBSERVED (2026-09-10): pressing 具申 opens that sub-panel - the palette
/// base moves to 37, page 3 - and hovering its six cells makes the client name them,
/// with no command issued (evidence/palette-named-by-the-client-v405.md):
///
/// <code>
/// 0 遊撃   1 防衛   2 占領   3 迎撃   4 偵察   5 撤退
/// </code>
///
/// The two readings agree exactly, including the one that needs no target: mission 5
/// is 撤退, and a retreat has nothing to point at.
///
/// What a mission makes a fleet *do* is still not recovered. This catalogue names
/// the value and rejects one the client's own panel cannot produce; it does not
/// give a mission behaviour.
/// </remarks>
public static class OriginalMissionCatalog
{
    private static readonly string[] Names = ["遊撃", "防衛", "占領", "迎撃", "偵察", "撤退"];

    public static int Count => Names.Length;

    /// <summary>The client's own name for a mission value, or null when out of range.</summary>
    public static string? NameOf(byte mission) => mission < Names.Length ? Names[mission] : null;

    public static bool IsKnown(byte mission) => mission < Names.Length;
}
