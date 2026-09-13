using System.Collections.Concurrent;

namespace Logh7.Server.OriginalGateway;

public sealed partial class OriginalTacticalBattleRegistry
{
    // One command clock per (ship incarnation, command), because the original
    // states 実行待機時間 on each command tile separately - 停止 alone carries 0 -
    // and never as a lockout across different commands. The interval itself is
    // the shipped client's own number (OriginalTacticalCommandTiming), in G秒,
    // which is the authority tick. Grid command leases serialize accepted
    // commands and their effects.
    private readonly ConcurrentDictionary<(uint Unit, ushort Type), (long Generation, uint Tick)> _playerLastCommands = new();

    /// <summary>
    /// True while this unit is still inside the wait its own last accepted
    /// command of this type started. A zero wait never waits.
    /// </summary>
    internal bool IsCommandWaiting(uint unit, ushort type, long generation, uint tick, uint waitTicks)
    {
        if (waitTicks == 0) return false;
        if (!_playerLastCommands.TryGetValue((unit, type), out var last) || last.Generation != generation)
            return false;
        var elapsed = unchecked(tick - last.Tick);
        return elapsed > int.MaxValue || elapsed < waitTicks;
    }

    /// <summary>
    /// Called only after a command has actually been accepted; a refused
    /// command never starts a wait. Reimport and connection changes do not
    /// clear it.
    /// </summary>
    internal void RecordCommand(uint unit, ushort type, long generation, uint tick) =>
        _playerLastCommands[(unit, type)] = (generation, tick);

    /// <summary>
    /// 攻撃 and 射撃 share one weapon, so they share one recharge, keyed on the
    /// shoot type. Its length is the original's own 実行待機時間 for that tile -
    /// 48 G秒 - replacing the authored 72 that this file used to borrow from
    /// the NPC controller.
    /// </summary>
    internal bool IsPlayerWeaponRecharging(uint unit, long generation, uint tick) =>
        IsCommandWaiting(unit, OriginalTacticalCommandCodec.ShootShipCommandType, generation, tick,
            OriginalTacticalCommandTiming.WaitTicks(OriginalTacticalCommandCodec.ShootShipCommandType));

    internal void RecordPlayerShot(uint unit, long generation, uint tick) =>
        RecordCommand(unit, OriginalTacticalCommandCodec.ShootShipCommandType, generation, tick);

    // 実行所要時間: how long an accepted command occupies the unit. The original
    // lists a pending/executing command panel of its own (constmsg group 0 row
    // 182, 「現在実行待機または実行処理中のコマンドを一覧します」), so a unit carrying one
    // out is a state the original models. 停止 - whose own description is
    // 「行動をキャンセルする」 - clears it.
    private readonly ConcurrentDictionary<uint, (long Generation, uint Until)> _executing = new();

    public bool IsExecuting(uint unit, long generation, uint tick)
    {
        if (!_executing.TryGetValue(unit, out var busy) || busy.Generation != generation) return false;
        var remaining = unchecked(busy.Until - tick);
        return remaining != 0 && remaining <= int.MaxValue;
    }

    public void RecordExecuting(uint unit, long generation, uint until) =>
        _executing[unit] = (generation, until);

    public void ClearExecuting(uint unit) => _executing.TryRemove(unit, out _);

    // A base carries out commands of its own - RepairBase, SupplyBase,
    // EncourageBase, 緊急補給 - and StopBase is their 停止. Base ids and unit ids are
    // separate id spaces that can and do collide (a grid's base 2 beside the
    // player's unit 2), so a base's occupancy is kept apart from a unit's rather
    // than sharing the map.
    private readonly ConcurrentDictionary<uint, uint> _baseExecuting = new();

    public bool IsBaseExecuting(uint baseId, uint tick)
    {
        if (!_baseExecuting.TryGetValue(baseId, out var until)) return false;
        var remaining = unchecked(until - tick);
        return remaining != 0 && remaining <= int.MaxValue;
    }

    public void RecordBaseExecuting(uint baseId, uint until) => _baseExecuting[baseId] = until;

    /// <summary>StopBase's own act: cancel what the base is carrying out.</summary>
    public bool ClearBaseExecuting(uint baseId) => _baseExecuting.TryRemove(baseId, out _);
}
