namespace Logh7.Server.OriginalGateway;

/// <summary>
/// The formations 隊列変更 (0x040D) chooses between, in the client's own words.
/// </summary>
/// <remarks>
/// ORIGINAL_OBSERVED. Pressing 隊列変更 opens a sub-panel - the palette's base moves
/// to 45 and its page to 5 - whose eight cells the client names itself when they
/// are hovered. Read live 2026-09-10 with a cursor-only sweep, no command issued
/// (evidence/palette-named-by-the-client-v405.md):
///
/// <code>
/// 0 防御      1 紡錘      2 艦種１    3 艦種２
/// 4 混成１    5 混成２    6 散開(隊列解除)  7 三列
/// </code>
///
/// The cell order is the sub-panel's own order, and the live 隊列変更 this lane
/// captured carried kind = 7, the last cell. What each formation *does* is still
/// not recovered - nothing here turns a formation into a combat modifier or a
/// placement. This catalogue only lets the authority name what it was told and
/// refuse a value the client's own panel cannot produce.
/// </remarks>
public static class OriginalFleetFormationCatalog
{
    private static readonly string[] Names =
    [
        "防御", "紡錘", "艦種１", "艦種２", "混成１", "混成２", "散開", "三列",
    ];

    public static int Count => Names.Length;

    /// <summary>The client's own name for a formation byte, or null when out of range.</summary>
    public static string? NameOf(byte kind) => kind < Names.Length ? Names[kind] : null;

    public static bool IsKnown(byte kind) => kind < Names.Length;
}
