using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// 所属変更 (0x0420) - place a fleet already in this field under this
    /// character's command.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_OBSERVED shape: the shipped client sent this body from the
    /// tactical palette's r3c2 cell, and the client's own logger
    /// <c>_INF:CommandChangeAuthority#</c> (0x00499F20) prints
    /// <c>time / wait / id / unit[n] / target</c>, which is what
    /// <see cref="OriginalTacticalCommandCodec.TryDecodeChangeAuthorityCommand"/>
    /// reads. The trailing field is the target the units are given to.
    ///
    /// This is the command that makes every other tactical command usable on a
    /// fleet other than the viewer's own. Until it existed, the client would
    /// happily build 移動 or 修理 for an authored fleet - its palette filters
    /// allow it - and the authority could only answer
    /// <c>TACTICAL_UNIT_NOT_CONTROLLED</c>, because it had no record of anyone
    /// commanding that fleet (evidence/move-point-pick-and-control-rule-v403.md).
    ///
    /// The controller model is not invented here: <c>original_fleet_unit</c>
    /// already carries <c>controller_character_id</c>, the restore path already
    /// separates an autonomous fleet from a commanded one, and
    /// <c>CommitFleetControlAssignmentsAsync</c> already commits the assignment
    /// and its projection together. This handler authorizes the request and
    /// hands it to that path.
    ///
    /// Authorization is deliberately narrow: the units must be friendly, alive,
    /// in this grid, and not the viewer's own unit; the target must be the
    /// viewer's own unit, because that is the commander this authority can bind
    /// them to. Taking a hostile fleet is not a change of command, and no
    /// original rule for it is recovered.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessChangeAuthorityAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeChangeAuthorityCommand(payload, out var command))
            return RejectCommandVisibly("original.tactical-change-authority.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("AUTHORITY_WORLD_REQUIRED", type, "この命令は実行できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        var number = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number;
        using var lease = await _battles.LockAsync(_worldGridCellId, number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (!IsCurrentTacticalFieldActive)
            return RejectCommandVisibly("AUTHORITY_TACTICAL_FIELD_REQUIRED", type, "戦術中ではありません");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;

        // The command names the formation the units are given to. Measured live
        // (v404): the client arms this pick with filter 0x60981, whose second
        // group demands node flag 0x0800 - an allied fleet - so the flagship is
        // not offered and the target is another 部隊, exactly as the client's own
        // tooltip says: 「所属部隊を変更」.
        //
        // Binding the units to the viewer's own unit is the one outcome this
        // authority persists today, through the fleet-controller columns
        // original_fleet_unit already carries. Re-parenting a fleet into another
        // NPC formation would have to change its outfit id, and the restore path
        // treats a unit's outfit as a catalog invariant
        // (FLEET_UNIT_CATALOG_CONFLICT), so that is refused visibly rather than
        // half-applied.
        var targetParticipant = command.TargetId == _worldGridUnitId
            ? null
            : OtherBattleParticipants().FirstOrDefault(p => p.Unit.Id == command.TargetId);
        if (command.TargetId != _worldGridUnitId)
        {
            if (targetParticipant is null || targetParticipant.IsHostileTo(_createdCharacter?.Power ?? 0, 0))
                return RejectCommandVisibly("AUTHORITY_TARGET_NOT_FRIENDLY", type, "その部隊には所属できません");
            return RejectCommandVisibly("AUTHORITY_REPARENT_NOT_IMPLEMENTED", type, "この所属変更は未対応です");
        }
        if (command.UnitIds.Count == 0)
            return RejectCommandVisibly("AUTHORITY_UNIT_REQUIRED", type, "艦隊を選択してください");

        var power = _createdCharacter?.Power ?? 0;
        var assignments = new List<OriginalNpcControlAssignment>(command.UnitIds.Count);
        var corps = CurrentPlayerCorps;
        foreach (var unitId in command.UnitIds)
        {
            if (unitId == _worldGridUnitId)
                return RejectCommandVisibly("AUTHORITY_UNIT_IS_OWN", type, "自艦隊は委任できません");
            var participant = OtherBattleParticipants().FirstOrDefault(p => p.Unit.Id == unitId);
            if (participant is null || participant.IsHostileTo(power, 0))
                return RejectCommandVisibly("AUTHORITY_UNIT_NOT_FRIENDLY", type, "その艦隊は指揮できません");
            if (!_tacticalEncounter.HasSurvivors(unitId))
                return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");
            assignments.Add(new(unitId, _worldCharacterId, corps, Autonomous: false,
                ExpectedGeneration: participant.ShipGeneration,
                ControllerUnitId: _worldGridUnitId,
                ControllerGeneration: CurrentShipGeneration));
        }

        if (!await _battles.CommitFleetControlAssignmentsAsync(
                _worldGridCellId, assignments, cancellationToken))
        {
            return RejectCommandVisibly("AUTHORITY_ASSIGNMENT_REJECTED", type, "委任できませんでした");
        }

        RecordTacticalCommand(type, _gameClock.Tick);
        _receipt.Record("tactical-change-authority", FormattableString.Invariant(
            $"controller={_worldCharacterId};target={command.TargetId};units={string.Join('/', command.UnitIds)}"),
            _accountId);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            AdditionalResponses =
            [
                EncodeApplicationPush(EncodeTacticalBattlefieldUnits()),
                EncodeApplicationPush(OriginalSystemSceneCodec.EncodeTacticalUnitShips(
                    CurrentTacticalBattlefield())),
            ],
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-change-authority-accepted;controller={_worldCharacterId};units={command.UnitIds.Count};design=new")
        };
    }
}
