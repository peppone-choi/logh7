namespace Logh7.Server.OriginalGateway;

/// <summary>
/// How this authority stands against the client's own tactical command table:
/// which of the 35 types it handles itself, and which it answers visibly without
/// handling.
/// </summary>
/// <remarks>
/// This exists so coverage is a checked fact rather than prose in a report.
/// <see cref="OriginalTacticalCommandCoverageIsEnforced"/> in the protocol tests
/// drives every type in <see cref="OriginalTacticalCommandCatalog"/> through a
/// real session and asserts that exactly the types named here are handled and
/// every other one answers with
/// <see cref="OriginalTacticalCommandCatalog.NotImplementedErrorCode"/>. Adding a
/// handler without adding its type here, or the reverse, fails the suite.
///
/// "Handled" means the authority decodes the body and answers on its own terms -
/// accepted, or refused for a reason of its own. It does not mean the command has
/// a live receipt from the shipped client; that is tracked per command in
/// <c>work/20260904-warp-state-reverse/evidence</c>, and most do not have one.
/// </remarks>
public static class OriginalTacticalCommandCoverage
{
    /// <summary>
    /// The types this authority decodes and answers on its own terms. Each is
    /// annotated with the constmsg group 0 row that gives its name and schedule,
    /// which is the client's own in-game statement of the command.
    /// </summary>
    public static readonly IReadOnlySet<ushort> Handled = new HashSet<ushort>
    {
        OriginalTacticalCommandCodec.MoveShipCommandType,          // 0x0400 row 4  移動
        OriginalTacticalCommandCodec.TurnShipCommandType,          // 0x0401 row 5  旋回
        OriginalTacticalCommandCodec.ParallelMoveShipCommandType,  // 0x0402 row 12 平行移動
        OriginalTacticalCommandCodec.ReverseShipCommandType,       // 0x0403 row 14 反転
        OriginalTacticalCommandCodec.WarpCommandType,              // 0x0404 row 23 撤退
        OriginalTacticalCommandCodec.AttackShipCommandType,        // 0x0405 row 6  攻撃
        OriginalTacticalCommandCodec.ShootShipCommandType,         // 0x0406 row 7  射撃
        OriginalSuggestionCodec.CommandType,                       // 0x0408 row 11 具申
        OriginalEncourageFlagshipCodec.CommandType,                // 0x0409 row 10 鼓舞
        OriginalTacticalCommandCodec.StopCommandType,              // 0x040A row 13 停止
        OriginalTacticalControlCodec.CommandType,                  // 0x040C        出力配分
        OriginalTacticalCommandCodec.FileFleetCommandType,         // 0x040D row 16 隊列変更
        OriginalTacticalCommandCodec.ChangeModeCommandType,        // 0x0411 row 20 態勢変更
        OriginalTacticalCommandCodec.SortieCommandType,            // 0x0412 row 21 出撃
        OriginalTacticalCommandCodec.RepairFleetCommandType,       // 0x0413 row 25 修理
        OriginalTacticalCommandCodec.SupplyFleetCommandType,       // 0x0414 row 26 補給
        OriginalTacticalCommandCodec.StopFleetCommandType,         // 0x0415        StopFleet
        OriginalTacticalCommandCodec.ChangeAuthorityCommandType,   // 0x0420 row 22 所属変更
        OriginalTacticalCommandCodec.MissionCommandType,           // 0x0421 row 24 任務
        OriginalTacticalCommandCodec.EmergencySupplyCommandType,   // 0x0422 row 27 緊急補給
        OriginalTacticalCommandCodec.SortieTroopsCommandType,      // 0x040F row 17 陸戦
        OriginalTacticalCommandCodec.EvacuateTroopsCommandType,    // 0x0410 row 18 陸戦解除
        OriginalTacticalCommandCodec.EncourageBaseCommandType,     // 0x041D        EncourageBase
        OriginalTacticalCommandCodec.AttackTroopCommandType,       // 0x0417        AttackTroop
        OriginalTacticalCommandCodec.StopTroopCommandType,         // 0x0418        StopTroop
        OriginalTacticalCommandCodec.RepairBaseCommandType,        // 0x041B        RepairBase
        OriginalTacticalCommandCodec.SupplyBaseCommandType,        // 0x041C        SupplyBase
        OriginalTacticalCommandCodec.MoveTroopCommandType,         // 0x0416        MoveTroop
        OriginalTacticalCommandCodec.StopBaseCommandType,          // 0x041E        StopBase
        OriginalTacticalCommandCodec.ShootFortressCommandType,     // 0x0419 row 19 要塞砲
        OriginalTacticalCommandCodec.FightCommandType,             // 0x0407 row 8  白兵戦
        OriginalTacticalCommandCodec.AirBattleCommandType,         // 0x040E row 15 空戦
        OriginalTacticalCommandCodec.AdmissionCommandType,         // 0x040B        Admission
        OriginalTacticalCommandCodec.AdmissionBaseCommandType,     // 0x041A        AdmissionBase
        OriginalTacticalCommandCodec.MoveFortressCommandType,      // 0x041F        MoveFortress
    };

    /// <summary>Whether this authority decodes and answers the type itself.</summary>
    /// <summary>
    /// For every type this authority does not handle, what is actually missing.
    /// </summary>
    /// <remarks>
    /// The point of this table is that "unhandled" should never mean "unknown".
    /// For each of these the client's own statement is recovered - constmsg group 0
    /// gives the name and the schedule for the ones the tactical palette shows, and
    /// the per-command loggers give the body, which
    /// <c>OriginalUnhandledCommandShapeTests</c> exercises. What is missing in every
    /// case is a *game model* this authority does not have and that no recovered
    /// data defines: troops, fighters, boarding parties, base state. Inventing one
    /// would be worse than refusing visibly, so the refusal stands and the gap is
    /// named here instead.
    /// </remarks>
    public static string? MissingModelFor(ushort applicationType) => applicationType switch
    {
        // The troop model is PARTLY recovered, so these say what is left rather
        // than asking for the whole thing. Already held: OriginalStockKind.Troops,
        // the transactional warehouse transfer primitive keyed by (base, outfit),
        // and the party record's TroopPackages / TroopTransportPackageEmpty /
        // Carrying fields. Missing: where a unit's *carried* troops live. Stock is
        // held per base and outfit, not per unit, so 投下する and 帰還させる have no
        // endpoint on the unit side yet.
        _ => null,
    };

    public static bool IsHandled(ushort applicationType) => Handled.Contains(applicationType);

    /// <summary>
    /// The catalogued types this authority does not handle. They are answered
    /// visibly rather than dropped, and their bodies are recorded on the wire so a
    /// live press recovers the native shape.
    /// </summary>
    public static IEnumerable<ushort> Unhandled()
    {
        for (var type = OriginalTacticalCommandCatalog.FirstType;
             type <= OriginalTacticalCommandCatalog.LastType;
             type++)
        {
            if (!IsHandled(type)) yield return type;
        }
    }
}
