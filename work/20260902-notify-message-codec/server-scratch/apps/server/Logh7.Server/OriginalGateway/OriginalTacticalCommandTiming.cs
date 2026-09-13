namespace Logh7.Server.OriginalGateway;

/// <summary>
/// The original's own command schedule: every tactical command's
/// 実行待機時間 (the wait before it may be issued again) and 実行所要時間
/// (how long it then takes), in G秒.
/// </summary>
/// <remarks>
/// ORIGINAL_OBSERVED. These are not authored numbers: they are the strings the
/// shipped client draws in its own tooltips, read out of
/// <c>data/MsgDat/constmsg.dat</c> (sha256
/// 5B3FAFBA7DD7230CDEB5F2FF9ACF9BBBE20FD95ADE25C425BC0D11AE645C383C, identical
/// on the CD and on the installed guest), group 0 rows 4..27 - for example row 7
/// 「[ 射撃 ] 選択ユニットで射撃を行う 実行待機時間48G秒 実行所要時間0G秒」 and row 13
/// 「[ 停止 ] 行動をキャンセルする 実行待機時間0G秒 実行所要時間0G秒」.
///
/// The unit is the G秒, and one authority tick is one G秒: measured 2026-09-10,
/// the authority's clock answers ran 24.015 ticks per real second at
/// <c>frequency=24</c> while the client's own calendar advanced 22.5 G秒 per
/// real second (the client's clock pauses during warp cinematics, which
/// accounts for the difference). So a wait in G秒 is a wait in ticks.
///
/// 停止's wait of 0 is the table's own cross-check: it is the one tactical
/// command that never has to wait, which is exactly what the live client shows -
/// 停止 is accepted at any time and, uniquely, does not consume the command
/// permission byte at <c>entity+0x8C5</c>.
///
/// Durations that the original states as a formula rather than a number
/// (移動/平行移動 「目標地点まで継続」, 旋回 「ユニットの旋回性能による」,
/// 反転 「旋回性能+機動で算出」) are recorded as <see langword="null"/>: this
/// authority does not invent a formula for them.
/// </remarks>
public readonly record struct OriginalTacticalCommandSchedule(
    string Name,
    uint WaitGameSeconds,
    uint? DurationGameSeconds);

public static class OriginalTacticalCommandTiming
{
    /// <summary>
    /// The wait every tactical command but 停止 carries, in G秒 = ticks.
    /// constmsg group 0 rows 4..27 state 48 for all of them.
    /// </summary>
    public const uint StandardWaitGameSeconds = 48;

    private static readonly Dictionary<ushort, OriginalTacticalCommandSchedule> Schedules = new()
    {
        // constmsg group 0 row 4: 移動 - 実行待機時間48G秒 / 実行所要時間目標地点まで継続
        [OriginalTacticalCommandCodec.MoveShipCommandType] = new("移動", StandardWaitGameSeconds, null),
        // row 5: 旋回 - 実行所要時間ユニットの旋回性能による
        [OriginalTacticalCommandCodec.TurnShipCommandType] = new("旋回", StandardWaitGameSeconds, null),
        // row 12: 平行移動 - 実行所要時間目標地点まで継続
        [OriginalTacticalCommandCodec.ParallelMoveShipCommandType] = new("平行移動", StandardWaitGameSeconds, null),
        // row 14: 反転 - 実行所要時間旋回性能+機動で算出
        [OriginalTacticalCommandCodec.ReverseShipCommandType] = new("反転", StandardWaitGameSeconds, null),
        // row 23: 撤退 - 実行所要時間1800G秒
        [OriginalTacticalCommandCodec.WarpCommandType] = new("撤退", StandardWaitGameSeconds, 1800),
        // row 6: 攻撃 - 実行所要時間0G秒
        [OriginalTacticalCommandCodec.AttackShipCommandType] = new("攻撃", StandardWaitGameSeconds, 0),
        // row 7: 射撃 - 実行所要時間0G秒
        [OriginalTacticalCommandCodec.ShootShipCommandType] = new("射撃", StandardWaitGameSeconds, 0),
        // row 25: 修理 - 実行所要時間1800G秒
        [OriginalTacticalCommandCodec.RepairFleetCommandType] = new("修理", StandardWaitGameSeconds, 1800),
        // row 26: 補給 - 実行所要時間1800G秒
        [OriginalTacticalCommandCodec.SupplyFleetCommandType] = new("補給", StandardWaitGameSeconds, 1800),
        // row 16: 隊列変更 - 実行所要時間0G秒
        [OriginalTacticalCommandCodec.FileFleetCommandType] = new("隊列変更", StandardWaitGameSeconds, 0),
        // row 13: 停止 - 実行待機時間0G秒 / 実行所要時間0G秒. The one command with no wait.
        [OriginalTacticalCommandCodec.StopCommandType] = new("停止", 0, 0),
        // row 24: 任務 - 実行所要時間0G秒
        [OriginalTacticalCommandCodec.MissionCommandType] = new("任務", StandardWaitGameSeconds, 0),
        // row 27: 緊急補給 - 実行所要時間0G秒. 「緊急補給可能にする」 grants standing; it
        // occupies nothing, exactly as the row says.
        [OriginalTacticalCommandCodec.EmergencySupplyCommandType] = new("緊急補給", StandardWaitGameSeconds, 0),
        // rows 17/18: 陸戦「碇泊状態から陸戦を投下する」and 陸戦解除「陸戦ユニットを帰還させる」-
        // both 実行待機時間48G秒 / 実行所要時間240G秒.
        [OriginalTacticalCommandCodec.SortieTroopsCommandType] = new("陸戦", StandardWaitGameSeconds, 240),
        [OriginalTacticalCommandCodec.EvacuateTroopsCommandType] = new("陸戦解除", StandardWaitGameSeconds, 240),
        // row 11: 具申 - 実行所要時間0G秒
        [OriginalSuggestionCodec.CommandType] = new("具申", StandardWaitGameSeconds, 0),
        // EncourageBase is 鼓舞 aimed at a base; the palette shows no row of its own
        // for it, so it takes the same schedule as the command it mirrors.
        [OriginalTacticalCommandCodec.EncourageBaseCommandType] = new("鼓舞(拠点)", StandardWaitGameSeconds, 0),
        // row 15: 空戦 - 実行所要時間0G秒
        [OriginalTacticalCommandCodec.AirBattleCommandType] = new("空戦", StandardWaitGameSeconds, 0),
        // row 8: 白兵戦 - 実行所要時間240G秒, 「敵旗艦に白兵戦を仕掛ける 旗艦同士」
        [OriginalTacticalCommandCodec.FightCommandType] = new("白兵戦", StandardWaitGameSeconds, 240),
        // row 19: 要塞砲 - 実行所要時間1800G秒
        [OriginalTacticalCommandCodec.ShootFortressCommandType] = new("要塞砲", StandardWaitGameSeconds, 1800),
        // row 10: 鼓舞 - 実行所要時間0G秒
        [OriginalEncourageFlagshipCodec.CommandType] = new("鼓舞", StandardWaitGameSeconds, 0),
        // rows 20/21: 態勢変更「ユニットの態勢を変更する」and 出撃「駐留状態から碇泊状態へ」-
        // both 実行待機時間48G秒 / 実行所要時間240G秒. 0x0B06 is the strategy-side
        // own-ship mode change; the tactical palette sends 0x0411 and 0x0412.
        [OriginalSwitchModeCodec.RequestType] = new("態勢変更", StandardWaitGameSeconds, 240),
        [OriginalTacticalCommandCodec.ChangeModeCommandType] = new("態勢変更", StandardWaitGameSeconds, 240),
        [OriginalTacticalCommandCodec.SortieCommandType] = new("出撃", StandardWaitGameSeconds, 240),
    };

    /// <summary>
    /// The original schedule for a tactical command type, or null when this
    /// authority has not recovered one. A missing entry never gates a command:
    /// an unrecovered schedule must not become an invented refusal.
    /// </summary>
    public static OriginalTacticalCommandSchedule? For(ushort applicationType) =>
        Schedules.TryGetValue(applicationType, out var schedule) ? schedule : null;

    /// <summary>The wait in authority ticks, 0 when nothing is recovered.</summary>
    public static uint WaitTicks(ushort applicationType) => For(applicationType)?.WaitGameSeconds ?? 0;

    /// <summary>
    /// The 実行所要時間 in authority ticks - how long the command occupies the
    /// unit. 0 when the original states 0, and 0 when the original states a
    /// formula this authority has not recovered (移動/旋回/反転), because an
    /// unrecovered duration must not become an invented one.
    /// </summary>
    public static uint DurationTicks(ushort applicationType) =>
        For(applicationType)?.DurationGameSeconds ?? 0;

    /// <summary>The refusal the player sees while a command is still waiting.</summary>
    public const string WaitingText = "実行待機時間中です";

    /// <summary>The metadata code for that refusal.</summary>
    public const string WaitingErrorCode = "TACTICAL_COMMAND_WAITING";

    /// <summary>The refusal the player sees while the unit is still carrying out a command.</summary>
    public const string ExecutingText = "実行処理中です";

    /// <summary>The metadata code for that refusal.</summary>
    public const string ExecutingErrorCode = "TACTICAL_COMMAND_EXECUTING";
}
