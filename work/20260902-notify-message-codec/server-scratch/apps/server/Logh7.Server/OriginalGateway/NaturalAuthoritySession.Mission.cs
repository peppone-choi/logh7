namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// 任務 = application type 0x0421. The palette tooltip, constmsg group 0
    /// row 24, is 「[ 任務 ] 麾下の戦隊に対し指令を下す 実行待機時間48G秒 実行所要時間0G秒」: an
    /// order to the squadrons under the player's command, carrying the mission he
    /// chose from the six-icon sub-panel and the object he pointed it at.
    /// </summary>
    /// <remarks>
    /// The addressing is the client's own, read out of the image and then seen on
    /// the wire. The mission icon handler (case 26 at 0x00511821) records the
    /// mission at [0x0077A900] and moves the palette to command state 0x19;
    /// state 0x19's handler (0x0050EFA4, reached through the command-state jump
    /// table at 0x00512224) builds the frame from the selected-unit array at
    /// 0x02215128 with its count at 0x02216664 - the same array 射撃 fires with -
    /// and appends the object FUN_004EF8E0 resolved for the click. So the unit
    /// list is friendly and normally holds the player's own fleet, and the target
    /// is whatever the mission's own pick mask admits: missions 0/3/4 use mask
    /// 0x1181 (a hostile fleet), 1 uses 0x0C04 and 2 uses 0x1004 (a celestial
    /// object), and 5 sends with no pick at all.
    ///
    /// Official update04 describes faction-wide instruction visibility and
    /// mission rewards. This handler validates the sender, selection and target,
    /// relays the unchanged instruction to same-side tactical participants,
    /// and echoes the command back as the answer - the client's own
    /// selector arm at 0x004B80EC expects the response type to be 0x0421, the
    /// request type itself. Every refusal is visible to the player in Japanese
    /// rather than silent. Chief election/authorization,
    /// offline AI execution and mission rewards remain to be connected.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessMissionAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeMissionCommand(payload, out var command))
            return RejectCommandVisibly("original.mission.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("MISSION_WORLD_REQUIRED", type, "任務を下せません");
        // 004B4850 obtains Order through004B4A90: the character ID assigned by0204,
        // not the selected unit ID. Bind it before recording or relaying any order.
        if (command.Order != _worldCharacterId)
            return RejectCommandVisibly("MISSION_ACTOR_NOT_CONTROLLED", type, "この人物では任務を下せません");

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
            return RejectCommandVisibly("MISSION_TACTICAL_FIELD_REQUIRED", type, "戦術中ではありません");
        if (!_tacticalEncounter.HasSurvivors(_worldGridUnitId))
            return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;

        // The unit list is the selection: the player's own fleet, and any living
        // friendly fleet in the same field he could have selected with it.
        if (command.UnitIds.Count == 0)
            return RejectCommandVisibly("MISSION_UNIT_REQUIRED", type, "命令する味方ユニットがありません");
        var power = _createdCharacter?.Power ?? 0;
        foreach (var unit in command.UnitIds)
        {
            if (unit == _worldGridUnitId) continue;
            if (!OtherBattleParticipants().Any(participant =>
                    participant.Unit.Id == unit &&
                    !participant.IsHostileTo(power, 0) &&
                    _tacticalEncounter.HasSurvivors(participant.Unit.Id)))
            {
                return RejectCommandVisibly("MISSION_UNIT_NOT_FRIENDLY", type, "命令できる味方ユニットがいません");
            }
        }

        // Six icons, six missions: 0x25..0x2A write 0..5 and nothing else.
        if (command.Mission > OriginalTacticalCommandCodec.HighestMission)
            return RejectCommandVisibly("MISSION_KIND_UNKNOWN", type, "その任務は行えません");

        // Original state19 sets the sender kind, not the picker's temporary kind:
        // 0050F0CA/0050F142 -> 1, 0050F0FC occupation -> 0; retreat sends zeros.
        // 004B4850 writes that argument to the expanded structure at+91.
        // This validates the wire tag only; it does not establish entity semantics.
        var expectedTargetKind = command.Mission is 2 or 5 ? (byte)0 : (byte)1;
        if (command.TargetKind != expectedTargetKind)
            return RejectCommandVisibly("MISSION_TARGET_KIND_INVALID", type, "この任務の目標種別が不正です");

        // Mission 5 is the one branch that sends with no pick at all; the other
        // five reach the sender only with an object the hit list resolved, so a
        // target that names nothing in this field is not a frame the client could
        // have produced.
        if (command.Mission == OriginalTacticalCommandCodec.MissionWithoutTarget)
        {
            if (command.TargetId != 0)
                return RejectCommandVisibly("MISSION_TARGET_NOT_ALLOWED", type, "この任務に目標は指定できません");
        }
        else if (command.Mission is 1 or 2)
        {
            // Official update04: defence/occupation select a planet or fortress,
            // not a unit. The sender's kind1 for defence is not a unit-table tag.
            if (command.TargetId == 0 ||
                ActiveBattlefieldCatalog.ProjectTacticalBases(_worldGridCellId, [command.TargetId]).Count != 1)
                return RejectCommandVisibly("MISSION_TARGET_BASE_NOT_IN_FIELD", type, "目標の拠点が戦術内にありません");
            var bases = CurrentBattlefieldTemplate().ProjectBaseInformation(_worldGridCellId)
                .Where(candidate => candidate.Id == command.TargetId).ToArray();
            if (bases.Length != 1)
                return RejectCommandVisibly("MISSION_BASE_AFFILIATION_UNKNOWN", type, "拠点の所属が確認できません");
            // Same power but rebel camp is a different side, as for fleet targets.
            var friendly = bases[0].Power == power && bases[0].Camp == 0;
            if (command.Mission == 1 && !friendly)
                return RejectCommandVisibly("MISSION_BASE_NOT_FRIENDLY", type, "味方の拠点を選択してください");
            if (command.Mission == 2 && friendly)
                return RejectCommandVisibly("MISSION_BASE_NOT_HOSTILE", type, "他勢力の拠点を選択してください");
        }
        else if (command.TargetId == 0 ||
                 !OtherBattleParticipants().Any(participant =>
                     participant.Unit.Id == command.TargetId &&
                     _tacticalEncounter.HasSurvivors(participant.Unit.Id)))
        {
            return RejectCommandVisibly("MISSION_TARGET_NOT_IN_FIELD", type, "目標が戦術内にいません");
        }
        else if (command.Mission == 3 && !OtherBattleParticipants().Any(participant =>
                     participant.Unit.Id == command.TargetId && participant.IsHostileTo(power, 0)))
        {
            return RejectCommandVisibly("MISSION_TARGET_NOT_HOSTILE", type, "他勢力の戦隊を選択してください");
        }

        RecordTacticalCommand(type, _gameClock.Tick);
        var frame = OriginalTacticalCommandCodec.EncodeMissionCommand(command);
        // Official update04: every same-side tactical participant sees the
        // instruction, including players not selected in the ordered unit list.
        // Keep that list unchanged: visibility does not force online players' AI.
        foreach (var unit in OtherBattleParticipants()
                     .Where(participant => !participant.IsHostileTo(power, 0))
                     .Select(participant => participant.Unit.Id).Distinct())
        {
            _battles.Publish(_worldGridCellId, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number,
                PendingNotifications.Writer, [frame], onlyUnit: unit, tacticalOnly: true);
        }
        _receipt.Record("mission",
            FormattableString.Invariant(
                $"units={string.Join('/', command.UnitIds)};mission={command.Mission};name={OriginalMissionCatalog.NameOf(command.Mission)};target-kind={command.TargetKind};target={command.TargetId}"),
            _accountId);
        return EncodeApplicationResponse(frame, type, includeLobbyPrefix: true) with
        {
            ResponseMetadata = FormattableString.Invariant(
                $"mission-accepted;actor={command.Order};units={string.Join('/', command.UnitIds)};mission={command.Mission};name={OriginalMissionCatalog.NameOf(command.Mission)};target-kind={command.TargetKind};target={command.TargetId};design=new")
        };
    }
}
