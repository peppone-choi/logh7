namespace Logh7.Server.OriginalGateway;

/// <summary>
/// The four postures 態勢変更 (0x0411) chooses between, in the client's own words.
/// </summary>
/// <remarks>
/// ORIGINAL_OBSERVED. Pressing 態勢変更 opens a sub-panel - the palette base moves to
/// 53 and its page to 6 - and its four cells name themselves, each with what the
/// posture means. Read live 2026-09-10 with a cursor-only sweep, no command issued
/// (evidence/palette-named-by-the-client-v405.md):
///
/// <code>
/// 駐留  惑星／要塞に駐留、自陣営に限る
/// 航行  通常モード
/// 戦闘  攻撃性重視、機動性低下
/// 碇泊  惑星／要塞軌道上に碇泊
/// </code>
///
/// **The wire values are only partly known.** constmsg row 21 states 出撃's
/// transition as 「駐留状態から碇泊状態へ」, and the two values this authority has ever
/// seen on the wire are 4 and 5 - so 4 is 駐留 and 5 is 碇泊. Which values 航行 and
/// 戦闘 carry has not been observed, so they are not guessed here: the catalogue
/// names the two known ones and states that the other two exist. A refusal can then
/// say what was asked for instead of only that it is unsupported.
///
/// 戦闘 is a real gameplay posture this authority does not implement -
/// 「攻撃性重視、機動性低下」, offence traded for manoeuvre - and that is now on the
/// record with the client's own words rather than as an unknown.
/// </remarks>
public static class OriginalUnitPostureCatalog
{
    public const byte Garrison = 4;   // 駐留 - row 21's 駐留状態
    public const byte Anchored = 5;   // 碇泊 - row 21's 碇泊状態

    /// <summary>The client's own name for a posture value this lane has observed.</summary>
    public static string? NameOf(byte mode) => mode switch
    {
        Garrison => "駐留",
        Anchored => "碇泊",
        _ => null,
    };

    /// <summary>What the client's own panel says the posture means.</summary>
    public static string? MeaningOf(byte mode) => mode switch
    {
        Garrison => "惑星／要塞に駐留、自陣営に限る",
        Anchored => "惑星／要塞軌道上に碇泊",
        _ => null,
    };

    /// <summary>
    /// The panel's four postures in its own cell order, for the record. Only the
    /// two whose wire value is known are addressable by value above.
    /// </summary>
    public static IReadOnlyList<string> PanelOrder { get; } = ["駐留", "航行", "戦闘", "碇泊"];
}
