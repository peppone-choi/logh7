namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// 具申 = 0x0408 CommandSuggestion, answered with 0x0430 ResponseSuggestion.
    /// The palette tooltip, constmsg group 0 row 11, is
    /// 「[ 具申 ] 味方ユニットへ具申する 実行待機時間48G秒 実行所要時間0G秒」: a suggestion is
    /// addressed to one friendly unit and carries a mission and its object.
    /// </summary>
    /// <remarks>
    /// The shipped client never sends this. Its selector arm (0x004B81AE, selector
    /// 0x80) exists and names the pair, but nothing in the image calls selector
    /// 0x80 - see <see cref="OriginalSuggestionCodec"/>. It is implemented anyway
    /// because it is a recovered part of the protocol and because an unimplemented
    /// type that does arrive must be answered rather than dropped; it is *not*
    /// presented as live-verified, and the live command the palette's mission
    /// sub-panel sends is 任務 0x0421, handled in
    /// <see cref="ProcessMissionAsync"/>.
    ///
    /// NEW_DESIGN beyond the shapes: nothing recovered says what the recipient may
    /// do with a suggestion, so this authority validates it, relays it unchanged
    /// to the unit it names, and answers with the one bit it can defend.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessSuggestionAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        if (!OriginalSuggestionCodec.TryDecode(payload, out var command))
            return RejectCommandVisibly("original.suggestion.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("SUGGESTION_WORLD_REQUIRED", type, "具申できません");
        // The client's own logger names the words; id is the acting character.
        if (command.Id != _worldCharacterId)
            return RejectCommandVisibly("SUGGESTION_ACTOR_NOT_CONTROLLED", type, "この人物では具申できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null)
        {
            if (restoreError == "original.flagship.scene-refresh-required")
                return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
            return Invalid(restoreError, type);
        }

        using var lease = await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (!IsCurrentTacticalFieldActive)
            return RejectCommandVisibly("SUGGESTION_TACTICAL_FIELD_REQUIRED", type, "戦術中ではありません");
        if (!_tacticalEncounter.HasSurvivors(_worldGridUnitId))
            return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;

        if (command.Unit == 0)
            return RejectCommandVisibly("SUGGESTION_UNIT_REQUIRED", type, "具申する味方ユニットがありません");
        var power = _createdCharacter?.Power ?? 0;
        if (command.Unit != _worldGridUnitId &&
            !OtherBattleParticipants().Any(participant =>
                participant.Unit.Id == command.Unit &&
                !participant.IsHostileTo(power, 0) &&
                _tacticalEncounter.HasSurvivors(participant.Unit.Id)))
        {
            return RejectCommandVisibly("SUGGESTION_UNIT_NOT_FRIENDLY", type, "具申できる味方ユニットがいません");
        }
        // The suggestion carries a mission from the same six-value set 任務 uses.
        if (command.Mission > OriginalTacticalCommandCodec.HighestMission)
            return RejectCommandVisibly("SUGGESTION_MISSION_UNKNOWN", type, "その具申は行えません");

        RecordTacticalCommand(type, _gameClock.Tick);
        var response = new OriginalSuggestionResponse(command.Time, command.Id, command.Unit,
            OriginalSuggestionCodec.ResponseAccepted, command.Mission, command.TargetKind, command.Target);
        var frame = OriginalSuggestionCodec.EncodeResponse(response);
        if (command.Unit != _worldGridUnitId)
        {
            _battles.Publish(_worldGridCellId, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number,
                PendingNotifications.Writer, [frame], onlyUnit: command.Unit);
        }
        _receipt.Record("suggestion",
            FormattableString.Invariant(
                $"unit={command.Unit};mission={command.Mission};target-kind={command.TargetKind};target={command.Target}"),
            _accountId);
        return EncodeApplicationResponse(frame, type, includeLobbyPrefix: true) with
        {
            ResponseMetadata = FormattableString.Invariant(
                $"suggestion-accepted;actor={command.Id};unit={command.Unit};mission={command.Mission};target-kind={command.TargetKind};target={command.Target};response={OriginalSuggestionCodec.ResponseAccepted};design=new")
        };
    }
}
