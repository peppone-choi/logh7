namespace Logh7.Server.OriginalGateway;

/// <summary>
/// The three attack modes 攻撃 (0x0405) chooses between, in the client's own words.
/// </summary>
/// <remarks>
/// ORIGINAL_OBSERVED. Pressing 攻撃 opens a sub-panel - the palette's base moves to
/// 30 and its page to 1 - and its three cells name themselves when hovered, with a
/// one-line statement of what each mode trades. Read live 2026-09-10 with a
/// cursor-only sweep, no command issued
/// (evidence/palette-named-by-the-client-v405.md):
///
/// <code>
/// 一斉攻撃  破壊力大、連続攻撃性低し
/// 連続攻撃  破壊力小、連続攻撃性高し
/// 攻撃停止  攻撃を停止
/// </code>
///
/// The wire kinds were already recovered (0x0050D230 maps submenu indices
/// 0x1E/0x1F/0x20 to UI modes 1/2/3 and 0x004B4110 serializes them as 1/2/0), so
/// the mapping below joins two independently recovered facts rather than guessing.
///
/// **What is deliberately NOT done here.** The client states an ordering - 一斉 does
/// more damage per hit and fires less often, 連続 the reverse - but neither
/// magnitude is recovered, and this authority models no firing cadence at all: a
/// shot's damage is a flat authored 25 and the only rate limit is the weapon
/// recharge gate. Implementing the damage half alone would make 一斉攻撃 strictly
/// dominant, which is worse than leaving both alike. So the mode is carried and
/// named, and the ordering is recorded here as the constraint any future damage or
/// cadence model must satisfy.
/// </remarks>
public static class OriginalAttackModeCatalog
{
    public const byte CeaseFire = 0;
    public const byte Salvo = 1;
    public const byte Continuous = 2;

    /// <summary>The client's own name for an attack kind, or null when unknown.</summary>
    public static string? NameOf(byte kind) => kind switch
    {
        CeaseFire => "攻撃停止",
        Salvo => "一斉攻撃",
        Continuous => "連続攻撃",
        _ => null,
    };

    /// <summary>The trade the client states for a mode, or null when unknown.</summary>
    public static string? TradeOf(byte kind) => kind switch
    {
        CeaseFire => "攻撃を停止",
        Salvo => "破壊力大、連続攻撃性低し",
        Continuous => "破壊力小、連続攻撃性高し",
        _ => null,
    };

    public static bool IsKnown(byte kind) => NameOf(kind) is not null;
}
