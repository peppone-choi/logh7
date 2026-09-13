using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// 空戦 (0x040E) - a unit sending its carried craft against an enemy.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC for the shape and the schedule: the client's loggers give
    /// 空戦 攻撃's own field list with <c>skill</c> where 攻撃 has <c>kind</c>, so the
    /// recovered attack decoder reads it, and constmsg group 0 row 15 is
    /// 「[ 空戦 ] 空戦を仕掛ける 実行待機時間48G秒 実行所要時間0G秒」. Row 34 「[ 対空 ]
    /// 空戦隊への攻撃」 names what it sends: squadrons.
    ///
    /// **The carried craft are not a new idea.** <c>OriginalStockKind.ShipBoats</c>
    /// is one of the warehouse's own recovered stock kinds; what was missing was a
    /// place for a *unit* to carry its own, exactly as it was for troops. The
    /// complement is the same 100 the authority already uses for stores and for a
    /// landing force, and a sortie costs the same authored step and lands the same
    /// authored hit as every other attack here.
    ///
    /// The <c>skill</c> byte is carried and not branched on: nothing recovered says
    /// what a skill selects.
    ///
    /// **Not modelled:** the target's own squadrons do not intercept - 対空 is a
    /// separate command this authority does not implement - and no craft are lost
    /// on either side beyond the sortie's own cost.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessAirBattleAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeAttackShipCommand(payload, type, out var command))
            return RejectCommandVisibly("original.tactical-air-battle.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("AIR_WORLD_REQUIRED", type, "この命令は実行できません");
        if (command.UnitIds.Count != 1 || command.UnitIds[0] != _worldGridUnitId)
            return RejectCommandVisibly("AIR_UNIT_NOT_CONTROLLED", type, "この艦隊は指揮できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        var number = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number;
        using var lease = await _battles.LockAsync(_worldGridCellId, number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (!_tacticalEncounter.HasSurvivors(_worldGridUnitId))
            return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;

        var power = _createdCharacter?.Power ?? 0;
        var target = OtherBattleParticipants().FirstOrDefault(participant =>
            participant.Unit.Id == command.TargetId && participant.IsHostileTo(power, 0));
        if (target is null || !_tacticalEncounter.HasSurvivors(command.TargetId))
            return RejectCommandVisibly("AIR_TARGET_NOT_HOSTILE", type, "その艦隊には仕掛けられません");

        OriginalSimpleStoreResult spent;
        try
        {
            spent = await _store.SpendOriginalBoatsAsync(_accountId,
                new OriginalBoatSpendWrite(_worldCharacterId, _worldGridUnitId, _worldGridCellId,
                    AuthoredAssaultMoraleStep),
                cancellationToken);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.tactical-air-battle.persistence-not-supported", type);
        }
        if (!spent.Applied)
        {
            return RejectCommandVisibly(spent.ErrorCode ?? "AIR_REJECTED", type,
                spent.ErrorCode == "BOAT_NONE_CARRIED" ? "空戦隊がいません" : "空戦を仕掛けられません");
        }

        var damage = OriginalTacticalCommandAuthority.ApplyAuthoredDamage(
            _tacticalEncounter.GetUnitDamage(command.TargetId),
            _tacticalEncounter.UnitNumber(command.TargetId));
        await _battles.CommitUnitDamageAsync(_worldGridCellId, number, command.TargetId,
            damage, cancellationToken, target.ShipGeneration);
        RecordTacticalCommand(type, _gameClock.Tick);
        _receipt.Record("tactical-air-battle", FormattableString.Invariant(
            $"unit={_worldGridUnitId};target={command.TargetId};boats={spent.Value};skill={command.Kind};damaged={damage.Damaged}"),
            _accountId);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            AdditionalResponses = [EncodeApplicationPush(EncodeTacticalBattlefieldUnits())],
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-air-battle-accepted;unit={_worldGridUnitId};target={command.TargetId};boats={spent.Value};damaged={damage.Damaged};design=new")
        };
    }

    /// <summary>
    /// Admission (0x040B) and AdmissionBase (0x041A) - a unit or a base admitting
    /// other units.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC for the shapes: the loggers print
    /// <c>time / wait / id / unit / target[]</c> and
    /// <c>time / wait / id / base / target[]</c>, each with its own
    /// 「<c>target_size[%d] is over than 32</c>」 bounds check
    /// (evidence/every-body-recovered-v409.md).
    ///
    /// An admission is a standing permission, so it is stored the way 緊急補給's
    /// grant is - one row per (host, target), and granting the same pair twice is
    /// the same grant. The host has to be something this grid carries: the viewer's
    /// own unit for Admission, a base of this grid for AdmissionBase; and the target
    /// has to be a living friendly unit in the field.
    ///
    /// **What an admission then permits is not modelled** - this authority has no
    /// docking, no transfer of ships between formations and no capacity rule - so
    /// the command records the permission and claims nothing more. That is the same
    /// discipline 緊急補給 follows, and unlike 緊急補給 there is no consumer yet to
    /// point at; the row is the whole of it, and this says so.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessAdmissionAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        var hostIsBase = type == OriginalTacticalCommandCodec.AdmissionBaseCommandType;
        if (!OriginalTacticalCommandCodec.TryDecodeBaseTargetListCommand(payload, type,
                out _, out _, out var order, out var hostId, out var targets))
            return RejectCommandVisibly("original.tactical-admission.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("ADMISSION_WORLD_REQUIRED", type, "この命令は実行できません");
        if (order != _worldCharacterId)
            return RejectCommandVisibly("ADMISSION_ACTOR_NOT_CONTROLLED", type, "この命令は実行できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        using var lease = await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;

        if (hostIsBase)
        {
            if (ActiveBattlefieldCatalog.ProjectTacticalBases(_worldGridCellId, [hostId]).Count != 1)
                return RejectCommandVisibly("ADMISSION_HOST_UNKNOWN", type, "その拠点はありません");
        }
        else if (hostId != _worldGridUnitId)
        {
            return RejectCommandVisibly("ADMISSION_HOST_NOT_CONTROLLED", type, "この艦隊は指揮できません");
        }
        if (targets.Count == 0)
            return RejectCommandVisibly("ADMISSION_TARGET_REQUIRED", type, "対象がありません");

        var power = _createdCharacter?.Power ?? 0;
        var granted = 0;
        long version = 0;
        foreach (var targetId in targets.Distinct())
        {
            if (targetId != _worldGridUnitId)
            {
                var participant = OtherBattleParticipants().FirstOrDefault(p =>
                    p.Unit.Id == targetId && !p.IsHostileTo(power, 0));
                if (participant is null)
                    return RejectCommandVisibly("ADMISSION_TARGET_NOT_FRIENDLY", type, "その艦隊は収容できません");
            }
            if (!_tacticalEncounter.HasSurvivors(targetId))
                return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");
            OriginalSimpleStoreResult result;
            try
            {
                result = await _store.GrantOriginalAdmissionAsync(_accountId,
                    new OriginalAdmissionWrite(_worldCharacterId, hostIsBase, hostId, targetId, _worldGridCellId),
                    cancellationToken);
            }
            catch (NotSupportedException)
            {
                return Invalid("original.tactical-admission.persistence-not-supported", type);
            }
            if (!result.Applied)
                return RejectCommandVisibly(result.ErrorCode ?? "ADMISSION_REJECTED", type, "収容できません");
            granted++;
            version = result.AuthorityVersion;
        }

        RecordTacticalCommand(type, _gameClock.Tick);
        _receipt.Record("tactical-admission", FormattableString.Invariant(
            $"host={(hostIsBase ? "base" : "unit")}:{hostId};targets={granted}"), _accountId);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-admission-accepted;host={(hostIsBase ? "base" : "unit")}:{hostId};targets={granted};authority-version={version};design=new")
        };
    }

    /// <summary>
    /// MoveFortress (0x041F) - moving a fortress to a point.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC for the shape: the client's logger prints
    /// <c>time / wait / id / base / velocity / to_position[]{x,y,z}</c> with its own
    /// 「<c>to_position_size[%d] is over than 32</c>」 bounds check - **平行移動's shape
    /// with a base in front of it** (evidence/every-body-recovered-v409.md).
    ///
    /// A fortress that has moved needs a position of its own, because the catalog's
    /// is where it started. That position is persisted, so a restart puts the
    /// fortress where it was left, like every other committed fact here.
    ///
    /// **NEW_DESIGN, and named:** the move is applied at once. The command carries a
    /// velocity, but nothing recovered says how a fortress travels or how long it
    /// takes, and this authority does not simulate 平行移動's path either - the same
    /// choice, for the same reason. The velocity is carried on the receipt and not
    /// used, and no schedule is enforced because the command has no constmsg row.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessMoveFortressAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeMoveFortressCommand(payload,
                out _, out _, out var order, out var baseId, out var velocity, out var destinations))
            return RejectCommandVisibly("original.fortress-move.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("FORTRESS_WORLD_REQUIRED", type, "この命令は実行できません");
        if (order != _worldCharacterId)
            return RejectCommandVisibly("FORTRESS_ACTOR_NOT_CONTROLLED", type, "この命令は実行できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        using var lease = await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;
        if (ActiveBattlefieldCatalog.ProjectTacticalBases(_worldGridCellId, [baseId]).Count != 1)
            return RejectCommandVisibly("FORTRESS_BASE_UNKNOWN", type, "その拠点はありません");
        if (destinations.Count != 1)
            return RejectCommandVisibly("FORTRESS_DESTINATION_REQUIRED", type, "目標地点がありません");

        var destination = destinations[0];
        OriginalSimpleStoreResult moved;
        try
        {
            moved = await _store.MoveOriginalBaseAsync(_accountId,
                new OriginalBasePositionWrite(baseId, _worldGridCellId,
                    destination.X, destination.Y, destination.Z),
                cancellationToken);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.fortress-move.persistence-not-supported", type);
        }
        if (!moved.Applied)
            return RejectCommandVisibly(moved.ErrorCode ?? "FORTRESS_MOVE_REJECTED", type, "移動できません");

        RecordTacticalCommand(type, _gameClock.Tick);
        _receipt.Record("tactical-fortress-move", FormattableString.Invariant(
            $"base={baseId};to={destination.X:F3}/{destination.Y:F3}/{destination.Z:F3};velocity={velocity:F3}"),
            _accountId);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-fortress-move-accepted;base={baseId};authority-version={moved.AuthorityVersion};design=new")
        };
    }
}
