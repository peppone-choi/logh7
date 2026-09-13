namespace Logh7.Server.OriginalGateway;

/// <summary>
/// Every tactical command application type the shipped client can send, with the
/// client's own name for it and the field list its own logger prints.
/// </summary>
/// <remarks>
/// ORIGINAL_STATIC 2026-09-10. The client carries one <c>_INF:Command&lt;Name&gt;#</c>
/// logger per tactical command, each a small function that prints every field
/// with its name and reads it at a fixed offset. Those functions sit in one block
/// at 0x00492C10..0x0049A500 and their name strings in one block at
/// 0x00769888..0x0076AC08, both in the same order, and that order is the
/// application type starting at 0x0400.
///
/// The ordering is not assumed - it is checked against the nine types this
/// authority had already recovered from captures long before this table existed:
/// 0x0400 MoveShip, 0x0401 TurnShip, 0x0403 ReverseShip, 0x0404 WarpShip,
/// 0x0405 AttackShip, 0x0406 ShootShip, 0x0409 EncourageFlagship, 0x040A Stop and
/// 0x040C Control all land on their own names, and each printer's field list
/// matches the codec this authority already had. 0x0421 was then confirmed a
/// tenth time by capture: <c>_INF:CommandMission#</c> prints
/// <c>time/wait/id/unit[n]/mission/target_kind/target</c> and the client sent
/// exactly that.
///
/// A type in this table is a command the original client can put on the wire. A
/// type this authority has not implemented must therefore be answered, not
/// treated as a protocol violation: an unknown type drops the connection, and
/// dropping the connection when the player presses a palette button he can see is
/// worse than telling him the command is not available yet.
/// </remarks>
public static class OriginalTacticalCommandCatalog
{
    /// <summary>The first and last tactical command type in the client's block.</summary>
    public const ushort FirstType = 0x0400;

    /// <summary>The last tactical command type in the client's block.</summary>
    public const ushort LastType = 0x0422;

    private static readonly string[] Names =
    [
        "MoveShip",             // 0x0400 移動
        "TurnShip",             // 0x0401 旋回
        "ParallelMoveShip",     // 0x0402 平行移動
        "ReverseShip",          // 0x0403 反転
        "WarpShip",             // 0x0404 撤退
        "AttackShip",           // 0x0405 攻撃
        "ShootShip",            // 0x0406 射撃
        "Fight",                // 0x0407 白兵戦
        "Suggestion",           // 0x0408 具申
        "EncourageFlagship",    // 0x0409 鼓舞
        "Stop",                 // 0x040A 停止
        "Admission",            // 0x040B
        "Control",              // 0x040C 出力配分
        "FileFleet",            // 0x040D 隊列変更
        "AirBattle",            // 0x040E 空戦
        "SortieTroops",         // 0x040F 陸戦
        "EvacuateTroops",       // 0x0410 陸戦解除
        "ChangeMode",           // 0x0411 態勢変更
        "Sortie",               // 0x0412 出撃
        "RepairFleet",          // 0x0413 修理
        "SupplyFleet",          // 0x0414 補給
        "StopFleet",            // 0x0415
        "MoveTroop",            // 0x0416
        "AttackTroop",          // 0x0417
        "StopTroop",            // 0x0418
        "ShootFortress",        // 0x0419 要塞砲
        "AdmissionBase",        // 0x041A
        "RepairBase",           // 0x041B
        "SupplyBase",           // 0x041C
        "EncourageBase",        // 0x041D
        "StopBase",             // 0x041E
        "MoveFortress",         // 0x041F
        "ChangeAuthority",      // 0x0420 所属変更
        "Mission",              // 0x0421 任務
        "EmergencySupply",      // 0x0422 緊急補給
    ];

    /// <summary>
    /// The client's own name for a tactical command type, or null when the type is
    /// not one of the client's tactical commands at all.
    /// </summary>
    public static string? NameOf(ushort applicationType) =>
        applicationType >= FirstType && applicationType <= LastType
            ? Names[applicationType - FirstType]
            : null;

    /// <summary>Whether this type is one of the client's tactical commands.</summary>
    public static bool IsTacticalCommand(ushort applicationType) => NameOf(applicationType) is not null;

    /// <summary>
    /// The request-dispatcher selector that produces each command, or 0 when the
    /// shipped client has no arm for it and therefore cannot send it at all.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC 2026-09-10. FUN_004B78A0 indexes the table at 0x004B864C
    /// with <c>selector - 1</c>; each arm sets <c>esi</c> to the request type
    /// (some via <c>ebx</c>, e.g. selector 127 does <c>mov ebx,0x421 / mov
    /// esi,ebx</c>, which is why a scan that only reads <c>mov esi, imm</c> misses
    /// 任務 and 出力配分). Reading all 128 arms gives an arm for 31 of the 35
    /// commands; <c>StopFleet</c>, <c>MoveTroop</c>, <c>AttackTroop</c> and
    /// <c>StopTroop</c> have none, so this build cannot put them on the wire.
    /// </remarks>
    private static readonly byte[] Selectors =
    [
        49,  // 0x0400 MoveShip
        50,  // 0x0401 TurnShip
        51,  // 0x0402 ParallelMoveShip
        84,  // 0x0403 ReverseShip
        85,  // 0x0404 WarpShip
        52,  // 0x0405 AttackShip
        53,  // 0x0406 ShootShip
        86,  // 0x0407 Fight
        128, // 0x0408 Suggestion
        88,  // 0x0409 EncourageFlagship
        83,  // 0x040A Stop
        124, // 0x040B Admission
        54,  // 0x040C Control
        98,  // 0x040D FileFleet
        87,  // 0x040E AirBattle
        56,  // 0x040F SortieTroops
        57,  // 0x0410 EvacuateTroops
        55,  // 0x0411 ChangeMode
        58,  // 0x0412 Sortie
        89,  // 0x0413 RepairFleet
        90,  // 0x0414 SupplyFleet
        0,   // 0x0415 StopFleet     - no arm
        0,   // 0x0416 MoveTroop     - no arm
        0,   // 0x0417 AttackTroop   - no arm
        0,   // 0x0418 StopTroop     - no arm
        91,  // 0x0419 ShootFortress
        125, // 0x041A AdmissionBase
        92,  // 0x041B RepairBase
        93,  // 0x041C SupplyBase
        94,  // 0x041D EncourageBase
        95,  // 0x041E StopBase
        96,  // 0x041F MoveFortress
        126, // 0x0420 ChangeAuthority
        127, // 0x0421 Mission
        97,  // 0x0422 EmergencySupply
    ];

    /// <summary>The dispatcher selector for a command, or 0 when it has no arm.</summary>
    public static byte SelectorOf(ushort applicationType) =>
        applicationType >= FirstType && applicationType <= LastType
            ? Selectors[applicationType - FirstType]
            : (byte)0;

    /// <summary>
    /// Whether the shipped client has any way to send this command. Four
    /// catalogued types have no selector arm; they are still answered rather than
    /// dropped, but no live receipt for them is possible from this build.
    /// </summary>
    public static bool CanBeSentByClient(ushort applicationType) =>
        SelectorOf(applicationType) != 0;

    /// <summary>The metadata code for a catalogued command this authority has not implemented.</summary>
    public const string NotImplementedErrorCode = "TACTICAL_COMMAND_NOT_IMPLEMENTED";

    /// <summary>What the player is told when he presses one.</summary>
    public const string NotImplementedText = "この命令はまだ使用できません";
}
