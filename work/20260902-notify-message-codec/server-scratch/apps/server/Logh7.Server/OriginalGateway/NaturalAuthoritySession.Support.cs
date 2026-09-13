using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// 修理 (0x0413) and 補給 (0x0414) - the support commands a 工作艦 and a 補給艦
    /// perform on another unit.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC for the shapes and the schedule. The client's own loggers
    /// <c>_INF:CommandRepairFleet#</c> (0x00497680) and
    /// <c>_INF:CommandSupplyFleet#</c> (0x00497770) print the identical layout,
    /// <c>time / wait / id / unit|transport_unit / target</c>, and constmsg group 0
    /// gives row 25 「[ 修理 ] 工作艦による修理を行う 注）旗艦の右 実行待機時間48G秒
    /// 実行所要時間1800G秒」 and row 26 「[ 補給 ] 補給艦による補給を行う 注）旗艦の左
    /// 実行待機時間48G秒 実行所要時間1800G秒」. The 1800 is a recovered number, so it is
    /// enforced as real occupancy.
    ///
    /// Both rows name the vessel that performs the command. This authority does
    /// not know which ship kind id is a 工作艦 or a 補給艦, so it does not guess:
    /// the battlefield states the role in words
    /// (<see cref="OriginalBattlefieldFleet.Role"/>), and the performing unit must
    /// belong to an outfit that carries it.
    ///
    /// 補給 has a second performer: a base that 緊急補給 (0x0422) has granted the
    /// standing to resupply the unit. Row 27 says that command 「緊急補給可能にする」 -
    /// it makes the resupply possible - so the grant it writes is what this reads.
    ///
    /// 修理's effect is NEW_DESIGN but not arbitrary: it restores the target's
    /// damaged hulls and leaves its destroyed ones destroyed, and it commits
    /// through the same <c>CommitUnitDamageAsync</c> path a hit does. No point cost
    /// is charged: the manual prices 完全修復 at 160, and borrowing that number for a
    /// different command would be inventing one.
    ///
    /// 補給 refills the target's stores to the authority's own full value of 100 -
    /// the value every unit it creates starts at - through
    /// <c>SupplyOwnOriginalUnitAsync</c>, whose refill and history row commit
    /// together.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessTacticalSupportAsync(
        byte[] payload, ushort type, uint sequence, CancellationToken cancellationToken)
    {
        var repairing = type == OriginalTacticalCommandCodec.RepairFleetCommandType;
        if (!OriginalTacticalCommandCodec.TryDecodeSupportCommand(payload, type, out var command))
        {
            // A body the handler cannot read is refused on screen, never by closing
            // the session: that is the guarantee the not-implemented band used to
            // provide for every tactical type and now every handler owes.
            return RejectCommandVisibly(repairing ? "original.tactical-repair.request-shape"
                : "original.tactical-supply.request-shape", type, "この命令は実行できません");
        }
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("SUPPORT_WORLD_REQUIRED", type, "この命令は実行できません");
        // Order identifies the issuing character, separately from the support
        // vessel and target. Reject before any restore, effect or execution lock.
        if (command.Order != _worldCharacterId)
            return RejectCommandVisibly("SUPPORT_ACTOR_NOT_CONTROLLED", type, "この人物では命令できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        var number = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number;
        using var lease = await _battles.LockAsync(_worldGridCellId, number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (!IsCurrentTacticalFieldActive)
            return RejectCommandVisibly("SUPPORT_TACTICAL_FIELD_REQUIRED", type, "戦術中ではありません");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;

        var power = _createdCharacter?.Power ?? 0;

        // 緊急補給 (0x0422) grants a base the standing to resupply a unit -
        // 「緊急補給可能にする」 - so a 補給 whose performer is that base is accepted
        // without a 補給艦. This is the grant's consumer; without it the grant would
        // be a row nothing reads.
        if (!repairing && command.Unit != 0 &&
            ActiveBattlefieldCatalog.ProjectTacticalBases(_worldGridCellId, [command.Unit]).Count == 1)
        {
            // A grid-wide emergency grant is not permission to use an enemy base.
            // Match the basic faction eligibility of the base-performed command.
            if (!(ActiveBattlefieldCatalog.Resolve(_worldGridCellId).BaseInformation ?? [])
                .Any(candidate => candidate.Id == command.Unit && candidate.Power == power))
                return RejectCommandVisibly("SUPPLY_BASE_NOT_FRIENDLY", type, "その拠点には命令できません");
            if (command.TargetId != _worldGridUnitId)
                return RejectCommandVisibly("SUPPLY_TARGET_NOT_CONTROLLED", type, "その艦隊には実施できません");
            if (!_tacticalEncounter.HasSurvivors(command.TargetId))
                return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");
            var grantedHere = await _store.HasOriginalEmergencySupplyGrantAsync(
                _accountId, _worldCharacterId, command.TargetId, _worldGridCellId, cancellationToken);
            if (!grantedHere)
                return RejectCommandVisibly("SUPPLY_BASE_NOT_GRANTED", type, "緊急補給が設定されていません");
            return await CompleteSupplyAsync(payload, type, sequence, command, cancellationToken);
        }

        // The vessel that performs it must be a living friendly outfit carrying
        // the role the command's own description names.
        var performer = OtherBattleParticipants().FirstOrDefault(participant =>
            participant.Unit.Id == command.Unit &&
            !participant.IsHostileTo(power, 0) &&
            _tacticalEncounter.HasSurvivors(participant.Unit.Id));
        if (performer is null)
            return RejectCommandVisibly("SUPPORT_VESSEL_NOT_FRIENDLY", type, "その艦は指揮できません");
        if (_battles.IsExecuting(performer.Unit.Id, performer.ShipGeneration, _gameClock.Tick))
            return RejectCommandVisibly("SUPPORT_VESSEL_EXECUTING", type, "その艦は命令を実行中です");
        if (!ActiveBattlefieldCatalog.OutfitCarriesRole(_worldGridCellId, performer.Unit.Outfit,
                repairing ? "repair" : "supply"))
        {
            return RejectCommandVisibly(repairing ? "REPAIR_VESSEL_REQUIRED" : "SUPPLY_VESSEL_REQUIRED",
                type, repairing ? "工作艦がありません" : "補給艦がありません");
        }

        // 修理 commits through the encounter's damage state, which this authority
        // owns for every unit in the field, so any living friendly fleet is a
        // legitimate target - and the client will offer one: its pick filter 0xD8F
        // accepts the flagship (node flag 0x0400) and an allied fleet (0x0800)
        // alike (evidence/support-command-target-rules-v399.md). 補給 refills
        // through SupplyOwnOriginalUnitAsync, which persists only the viewer's own
        // unit, so it still requires that target.
        OriginalTacticalParticipantSnapshot? target = null;
        if (command.TargetId != _worldGridUnitId)
        {
            target = OtherBattleParticipants().FirstOrDefault(participant =>
                participant.Unit.Id == command.TargetId && !participant.IsHostileTo(power, 0));
            if (target is null)
                return RejectCommandVisibly("SUPPORT_TARGET_NOT_FRIENDLY", type, "その艦隊には実施できません");
        }
        if (!_tacticalEncounter.HasSurvivors(command.TargetId))
            return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");

        // ORIGINAL_STATIC:004C76E0 initializes all six ship-service radii to1;
        // 005DD720/005DD880 compute3D distance and004F1180 requires radius>distance.
        // 0x37 is the record stride, NOT55 angular bins (see service-range-static-20260912).
        // The emergency-base path above has separate semantics and does not use this gate.
        if ((target?.Ship ?? _tacticalUnitShip) is not { } targetPose ||
            !WithinShipServiceRange(performer.Ship, targetPose))
            return RejectCommandVisibly("SUPPORT_TARGET_OUT_OF_RANGE", type, "対象が範囲外です");

        if (!repairing)
        {
            if (target is not null)
            {
                var suppliedFleet = await _battles.CommitFleetSupplyAsync(_worldGridCellId,
                    target.Unit.Id, target.ShipGeneration, cancellationToken);
                if (suppliedFleet is null)
                    return RejectCommandVisibly("SUPPLY_FLEET_UNAVAILABLE", type, "補給できません");
                var serviceStarted = RecordShipServiceCommand(type, performer, payload);
                var update = OriginalWorldEntryCodec.EncodeUnits([suppliedFleet.Unit]);
                _battles.Publish(_worldGridCellId, number, PendingNotifications.Writer, [update],
                    targetUnit: suppliedFleet.Unit.Id, targetEntry: suppliedFleet.EncodeEntry());
                return EncodeApplicationResponse(EncodeShipServiceEcho(payload, type, serviceStarted),
                    type, includeLobbyPrefix: true) with
                {
                    AdditionalResponses = [EncodeApplicationPush(update)],
                    ResponseMetadata = $"tactical-fleet-supply-accepted;target={suppliedFleet.Unit.Id};supplies=100;design=new"
                };
            }
            return await CompleteSupplyAsync(payload, type, sequence, command, cancellationToken, performer);
        }

        var damage = _tacticalEncounter.GetUnitDamage(command.TargetId);
        if (damage.Damaged <= damage.Destroyed)
            return RejectCommandVisibly("REPAIR_NOTHING_DAMAGED", type, "修理する損害がありません");
        var repaired = new OriginalTacticalDamageState(damage.Destroyed, damage.Destroyed);
        // The generation guard must name the ship being repaired, not the viewer's.
        await _battles.CommitUnitDamageAsync(_worldGridCellId, number, command.TargetId,
            repaired, cancellationToken, target?.ShipGeneration ?? CurrentShipGeneration);
        PublishOwnParticipantSnapshot();
        var repairStarted = RecordShipServiceCommand(type, performer, payload);
        // Known observers do not receive a new entry bundle, so explicitly
        // refresh the repaired unit after the start notification as well.
        var repairedUnit = _tacticalEncounter.ProjectUnit(target?.Unit ?? CurrentPlayerInformationUnit());
        _battles.Publish(_worldGridCellId, number, PendingNotifications.Writer,
            [OriginalWorldEntryCodec.EncodeUnits([repairedUnit])]);
        _receipt.Record("tactical-repair", FormattableString.Invariant(
            $"vessel={command.Unit};target={command.TargetId};damaged={damage.Damaged}->{repaired.Damaged};destroyed={repaired.Destroyed}"),
            _accountId);
        var response = EncodeApplicationResponse(
            EncodeShipServiceEcho(payload, type, repairStarted), type, includeLobbyPrefix: true);
        return response with
        {
            AdditionalResponses =
            [
                EncodeApplicationPush(EncodeTacticalBattlefieldUnits()),
                EncodeApplicationPush(OriginalSystemSceneCodec.EncodeTacticalUnitShips(
                    CurrentTacticalBattlefield())),
            ],
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-repair-accepted;vessel={command.Unit};target={command.TargetId};damaged={repaired.Damaged};destroyed={repaired.Destroyed};design=new")
        };
    }

    /// <summary>
    /// Recovered isotropic ship-service distance; base commands are separate.
    /// </summary>
    private static bool WithinShipServiceRange(OriginalTacticalUnitShipRecord source,
        OriginalTacticalUnitShipRecord target)
    {
        var dx = target.X - source.X;
        var dy = target.Y - source.Y;
        var dz = target.Z - source.Z;
        var distance = (float)Math.Sqrt((double)dx * dx + (double)dy * dy + (double)dz * dz);
        return float.IsFinite(distance) && distance < 1f;
    }

    private uint RecordShipServiceCommand(ushort type, OriginalTacticalParticipantSnapshot performer, byte[] payload)
    {
        // Called under the grid lease, after a successful effect. The original
        // countdown belongs to the performing ship, not the issuing flagship.
        var tick = _gameClock.Tick;
        _battles.RecordExecuting(performer.Unit.Id, performer.ShipGeneration,
            unchecked(tick + OriginalTacticalCommandTiming.DurationTicks(type)));
        _battles.RecordCommand(_worldGridUnitId, type, CurrentShipGeneration, tick);
        var targetId = BinaryPrimitives.ReadUInt32BigEndian(payload.AsSpan(18));
        var targetEntry = targetId == _worldGridUnitId ? EncodeCurrentActorEntry()
            : OtherBattleParticipants().First(p => p.Unit.Id == targetId).EncodeEntry();
        _battles.Publish(_worldGridCellId, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number,
            PendingNotifications.Writer, [EncodeShipServiceEcho(payload, type, tick)],
            performer.Unit.Id, performer.EncodeEntry(), targetId, targetEntry);
        return tick;
    }

    private static byte[] EncodeShipServiceEcho(byte[] payload, ushort type, uint started)
    {
        var response = OriginalTacticalCommandCodec.EncodeCommandEcho(payload);
        // 004C13A0/004C14A0 compute remaining = time + wait - current clock.
        // Match our authored immediate-start occupancy; never echo client timing as authority.
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(6), started);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(10), OriginalTacticalCommandTiming.DurationTicks(type));
        return response;
    }

    /// <summary>Persist and project a refill of the session's own unit.</summary>
    private async Task<NaturalAuthoritySessionResult> CompleteSupplyAsync(
        byte[] payload, ushort type, uint sequence,
        OriginalTacticalSupportCommand command, CancellationToken cancellationToken,
        OriginalTacticalParticipantSnapshot? performer = null)
    {
        if (_persistedGridUnit is not OriginalGridUnitRecord stored)
            return Invalid("original.tactical-supply.persistence-not-supported", type);
        var supplyFingerprint = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            FormattableString.Invariant(
                $"original-tactical-supply/v1|{_moveGridRequestScope:N}|{sequence}|{Convert.ToHexString(payload)}"))));
        OriginalTacticalSupplyResult supplied;
        try
        {
            supplied = await _store.SupplyOwnOriginalUnitAsync(_accountId,
                new OriginalTacticalSupplyWrite(supplyFingerprint, stored.CharacterId, stored.UnitId,
                    command.Unit, stored.AuthorityVersion, stored.ShipGeneration),
                cancellationToken);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.tactical-supply.persistence-not-supported", type);
        }
        if (supplied.Status == OriginalTacticalSupplyStatus.Replayed)
            return Invalid("original.tactical-supply.replay", type);
        if (supplied.Status != OriginalTacticalSupplyStatus.Supplied || supplied.Unit is null)
        {
            return RejectCommandVisibly(supplied.ErrorCode ?? "TACTICAL_SUPPLY_REJECTED", type,
                supplied.ErrorCode == "TACTICAL_SUPPLY_ALREADY_FULL"
                    ? "補給の必要がありません" : "補給できません");
        }
        ApplyPersistedGridUnit(supplied.Unit);
        PublishOwnParticipantSnapshot();
        uint? supplyStarted = null;
        if (performer is null) RecordTacticalCommand(type, _gameClock.Tick);
        else supplyStarted = RecordShipServiceCommand(type, performer, payload);
        var suppliedUnitFrame = OriginalWorldEntryCodec.EncodeUnits([CurrentPlayerInformationUnit()]);
        _battles.Publish(_worldGridCellId, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number,
            PendingNotifications.Writer, [suppliedUnitFrame], targetUnit: _worldGridUnitId,
            targetEntry: EncodeCurrentActorEntry());
        _receipt.Record("tactical-supply", FormattableString.Invariant(
            $"vessel={command.Unit};target={command.TargetId};supplies={supplied.Unit.Supplies}"),
            _accountId);
        var supplyResponse = EncodeApplicationResponse(
            supplyStarted is { } started ? EncodeShipServiceEcho(payload, type, started)
                : OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return supplyResponse with
        {
            AdditionalResponses =
            [
                EncodeApplicationPush(suppliedUnitFrame),
            ],
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-supply-accepted;vessel={command.Unit};target={command.TargetId};supplies={supplied.Unit.Supplies};authority-version={supplied.AuthorityVersion};design=new")
        };
    }
}
