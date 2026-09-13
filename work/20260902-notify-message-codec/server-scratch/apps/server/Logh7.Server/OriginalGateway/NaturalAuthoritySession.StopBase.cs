namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// StopBase (0x041E) - 停止 addressed to a base: cancel what it is carrying out.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC for the shape: the client's logger
    /// <c>_INF:CommandStopBase#</c> prints <c>time / wait / id / base</c> and, alone
    /// among the base commands, carries **no** bounds-check string - so it has no
    /// list, which is exactly the body the recovered base decoder reads
    /// (evidence/every-body-recovered-v409.md).
    ///
    /// It is 停止's base-side twin, and it does the same thing to the same kind of
    /// state: 停止's own description is 「行動をキャンセルする」 and this authority models
    /// that as the 実行所要時間 occupancy a command holds. A base has its own
    /// occupancy, kept apart from a unit's because base ids and unit ids are
    /// separate id spaces that collide (a grid's base 2 beside the player's unit 2).
    ///
    /// **What it can cancel today, stated plainly:** none of the base commands this
    /// authority implements carries a recovered duration - RepairBase and SupplyBase
    /// have no constmsg row at all, EncourageBase and 緊急補給 state 0 - so a base is
    /// never occupied yet and the clear is usually a no-op. That is the same thing
    /// 停止 does when a fleet is idle: the command is accepted and clears nothing.
    /// The mechanism is real, so the day a base command acquires a duration this
    /// already cancels it; what is missing is the duration, not the cancel.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessStopBaseAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeBaseCommand(payload, type,
                out _, out _, out var order, out var baseId))
            return RejectCommandVisibly("original.base-stop.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("BASE_STOP_WORLD_REQUIRED", type, "この命令は実行できません");
        if (order != _worldCharacterId)
            return RejectCommandVisibly("BASE_STOP_ACTOR_NOT_CONTROLLED", type, "この命令は実行できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        using var lease = await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (ActiveBattlefieldCatalog.ProjectTacticalBases(_worldGridCellId, [baseId]).Count != 1)
            return RejectCommandVisibly("BASE_STOP_BASE_UNKNOWN", type, "その拠点はありません");

        var cleared = _battles.ClearBaseExecuting(baseId);
        RecordTacticalCommand(type, _gameClock.Tick);
        _receipt.Record("tactical-base-stop",
            FormattableString.Invariant($"base={baseId};cleared={cleared}"), _accountId);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-base-stop-accepted;base={baseId};cleared={cleared};design=new")
        };
    }
}
