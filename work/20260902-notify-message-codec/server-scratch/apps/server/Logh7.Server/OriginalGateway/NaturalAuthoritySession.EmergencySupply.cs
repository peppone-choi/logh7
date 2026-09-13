using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// 緊急補給 (0x0422) - a base making a unit resupplyable.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC for the shape and the schedule. constmsg group 0 row 27 is
    /// 「[ 緊急補給 ] 緊急補給可能にする 実行待機時間48G秒 実行所要時間0G秒」, and the body is the
    /// two-word tail 修理 and 補給 carry - a base and a unit - which the recovered
    /// support decoder already reads (OriginalUnhandledCommandShapeTests).
    ///
    /// The row decides the semantics and this handler follows it literally: the
    /// command makes an emergency resupply **possible**; it does not perform one.
    /// So nothing is refilled here. What the grant is for is 補給 (0x0414), whose
    /// own row requires a 補給艦: a unit a base has granted may be resupplied
    /// without one while it is in that base's grid. That is the smallest consumer
    /// that keeps 「可能にする」 literal instead of quietly turning this into a second
    /// 補給.
    ///
    /// Authorization: the base must be one this grid actually carries, and the
    /// unit must be the viewer's own - the grant is persisted against that
    /// character's unit, so a unit this authority cannot commit for cannot be
    /// granted either.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessEmergencySupplyAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeSupportCommand(payload, type, out var command))
            return RejectCommandVisibly("original.tactical-emergency-supply.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("EMERGENCY_SUPPLY_WORLD_REQUIRED", type, "この命令は実行できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        var number = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number;
        using var lease = await _battles.LockAsync(_worldGridCellId, number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;

        // command.Unit is the base; command.TargetId is the unit it makes suppliable.
        if (ActiveBattlefieldCatalog.ProjectTacticalBases(_worldGridCellId, [command.Unit]).Count != 1)
            return RejectCommandVisibly("EMERGENCY_SUPPLY_BASE_UNKNOWN", type, "その拠点はありません");
        if (command.TargetId != _worldGridUnitId)
            return RejectCommandVisibly("EMERGENCY_SUPPLY_TARGET_NOT_CONTROLLED", type, "その艦隊には実施できません");
        if (!_tacticalEncounter.HasSurvivors(command.TargetId))
            return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");

        OriginalEmergencySupplyGrantResult granted;
        try
        {
            granted = await _store.GrantOriginalEmergencySupplyAsync(_accountId,
                new OriginalEmergencySupplyGrantWrite(_worldCharacterId, command.TargetId,
                    command.Unit, _worldGridCellId),
                cancellationToken);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.tactical-emergency-supply.persistence-not-supported", type);
        }
        if (!granted.Granted)
            return RejectCommandVisibly(granted.ErrorCode ?? "EMERGENCY_SUPPLY_REJECTED", type, "緊急補給を設定できません");

        RecordTacticalCommand(type, _gameClock.Tick);
        _receipt.Record("tactical-emergency-supply", FormattableString.Invariant(
            $"base={command.Unit};unit={command.TargetId};already={granted.AlreadyGranted}"), _accountId);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-emergency-supply-granted;base={command.Unit};unit={command.TargetId};already={granted.AlreadyGranted};authority-version={granted.AuthorityVersion};design=new")
        };
    }
}
