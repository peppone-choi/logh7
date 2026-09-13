using System.Security.Cryptography;
using System.Text;
using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// RepairBase (0x041B) and SupplyBase (0x041C) - a base repairing and
    /// resupplying units in its own grid.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC for the shape: the client's loggers
    /// <c>_INF:CommandRepairBase#</c> and <c>_INF:CommandSupplyBase#</c> print
    /// <c>time / wait / id / base / target[]</c>, and each carries its own
    /// 「<c>target_size[%d] is over than 32</c>」 bounds check, so the list and its
    /// ceiling are the client's own (evidence/every-body-recovered-v409.md).
    ///
    /// These are the base-performed twins of 修理 (0x0413) and 補給 (0x0414), and
    /// they need **no new state at all**: the effects are the ones this authority
    /// already commits. RepairBase clears the target's damaged hulls through the
    /// same <c>CommitUnitDamageAsync</c> path a hit uses, leaving destroyed hulls
    /// destroyed; SupplyBase refills through <c>SupplyOwnOriginalUnitAsync</c>,
    /// whose refill and history row commit together.
    ///
    /// The base has to be one this grid actually carries. RepairBase accepts any
    /// living friendly target, exactly as 修理 does now; SupplyBase still requires
    /// the viewer's own unit, because its store path persists only that unit - the
    /// same limit 補給 states, and for the same reason.
    ///
    /// Neither has a constmsg row of its own, so no schedule is enforced: an
    /// unrecovered schedule must never become an invented wait.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessBaseSupportAsync(
        byte[] payload, ushort type, uint sequence, CancellationToken cancellationToken)
    {
        var repairing = type == OriginalTacticalCommandCodec.RepairBaseCommandType;
        if (!OriginalTacticalCommandCodec.TryDecodeBaseTargetListCommand(payload, type,
                out _, out _, out var actorId, out var baseId, out var targets))
        {
            // Refused on screen, never by closing the session.
            return RejectCommandVisibly(repairing ? "original.base-repair.request-shape"
                : "original.base-supply.request-shape", type, "この命令は実行できません");
        }
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("BASE_SUPPORT_WORLD_REQUIRED", type, "この命令は実行できません");
        if (actorId != _worldCharacterId)
            return RejectCommandVisibly("BASE_SUPPORT_ACTOR_NOT_CONTROLLED", type, "この命令は実行できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        var number = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number;
        using var lease = await _battles.LockAsync(_worldGridCellId, number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;
        if (ActiveBattlefieldCatalog.ProjectTacticalBases(_worldGridCellId, [baseId]).Count != 1)
            return RejectCommandVisibly("BASE_SUPPORT_BASE_UNKNOWN", type, "その拠点はありません");
        // Restoration authority rule: mere presence in this grid does not grant
        // use of an enemy base. Unknown affiliation is not permission either.
        // Same-power eligibility is not a complete base-command role model.
        var friendlyBase = (ActiveBattlefieldCatalog.Resolve(_worldGridCellId).BaseInformation ?? [])
            .Any(candidate => candidate.Id == baseId && candidate.Power == _createdCharacter.Value.Power);
        if (!friendlyBase)
            return RejectCommandVisibly("BASE_SUPPORT_BASE_NOT_FRIENDLY", type, "その拠点には命令できません");
        if (targets.Count != 1)
            return RejectCommandVisibly("BASE_SUPPORT_TARGET_REQUIRED", type, "対象がありません");

        var targetId = targets[0];
        if (!_tacticalEncounter.HasSurvivors(targetId))
            return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");

        if (!repairing)
        {
            // The refill persists only the viewer's own unit, the same limit 補給
            // states, so a base cannot resupply a fleet this authority cannot save.
            if (targetId != _worldGridUnitId)
                return RejectCommandVisibly("SUPPLY_TARGET_NOT_CONTROLLED", type, "その艦隊には実施できません");
            return await CompleteSupplyAsync(payload, type, sequence,
                new OriginalTacticalSupportCommand(0, 0, 0, baseId, targetId), cancellationToken);
        }

        var power = _createdCharacter?.Power ?? 0;
        OriginalTacticalParticipantSnapshot? target = null;
        if (targetId != _worldGridUnitId)
        {
            target = OtherBattleParticipants().FirstOrDefault(participant =>
                participant.Unit.Id == targetId && !participant.IsHostileTo(power, 0));
            if (target is null)
                return RejectCommandVisibly("SUPPORT_TARGET_NOT_FRIENDLY", type, "その艦隊には実施できません");
        }
        var damage = _tacticalEncounter.GetUnitDamage(targetId);
        if (damage.Damaged <= damage.Destroyed)
            return RejectCommandVisibly("REPAIR_NOTHING_DAMAGED", type, "修理する損害がありません");
        var repaired = new OriginalTacticalDamageState(damage.Destroyed, damage.Destroyed);
        await _battles.CommitUnitDamageAsync(_worldGridCellId, number, targetId,
            repaired, cancellationToken, target?.ShipGeneration ?? CurrentShipGeneration);
        PublishOwnParticipantSnapshot();
        RecordTacticalCommand(type, _gameClock.Tick);
        var repairedUnit = _tacticalEncounter.ProjectUnit(target?.Unit ?? CurrentPlayerInformationUnit());
        _battles.Publish(_worldGridCellId, number, PendingNotifications.Writer,
            [OriginalWorldEntryCodec.EncodeUnits([repairedUnit])], targetUnit: targetId,
            targetEntry: target is null ? EncodeCurrentActorEntry() : target.EncodeEntry());
        _receipt.Record("tactical-base-repair", FormattableString.Invariant(
            $"base={baseId};target={targetId};damaged={damage.Damaged}->{repaired.Damaged}"), _accountId);
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
                $"tactical-base-repair-accepted;base={baseId};target={targetId};damaged={repaired.Damaged};design=new")
        };
    }
}
