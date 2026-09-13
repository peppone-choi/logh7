using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// 白兵戦 (0x0407) - a boarding party sent against an enemy flagship.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC for the shape and the schedule. The client's logger
    /// <c>_INF:CommandFight#</c> prints <c>time / wait / id / unit /
    /// from_direction{x,y,z} / target</c> and carries no bounds-check string, so the
    /// command names one acting unit and one target rather than lists
    /// (evidence/every-body-recovered-v409.md). constmsg group 0 row 8 is
    /// 「[ 白兵戦 ] 敵旗艦に白兵戦を仕掛ける 旗艦同士 実行待機時間48G秒 実行所要時間240G秒」 - the
    /// 240 is a recovered number and is enforced as real occupancy, and 「旗艦同士」
    /// states the rule this handler applies: it is flagship against flagship.
    ///
    /// **The boarding party is not a new model.** It comes out of the same carried
    /// complement 陸戦 puts ashore, so a unit cannot spend the same men twice: a
    /// boarding costs the authority's existing authored step of troops, and costs
    /// the enemy the authority's existing authored damage step. No second magnitude
    /// is introduced.
    ///
    /// **What is not modelled, and not pretended:** the defender's own party does
    /// not fight back, nothing is decided by who has more men, and a flagship is
    /// never captured. The command spends what a boarding costs and lands the hit it
    /// lands.
    ///
    /// <c>from_direction</c> is carried on the receipt and not used: it is where the
    /// party crosses from, and this authority does not model an approach.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessFightAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeFightCommand(payload,
                out _, out _, out var order, out var unit, out var from, out var targetId))
            return RejectCommandVisibly("original.tactical-fight.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("FIGHT_WORLD_REQUIRED", type, "この命令は実行できません");
        if (order != _worldCharacterId || unit != _worldGridUnitId)
            return RejectCommandVisibly("FIGHT_UNIT_NOT_CONTROLLED", type, "この艦隊は指揮できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        var number = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number;
        using var lease = await _battles.LockAsync(_worldGridCellId, number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (!_tacticalEncounter.HasSurvivors(_worldGridUnitId))
            return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;

        // 「敵旗艦に」「旗艦同士」: the target is an enemy, and it is a fleet in this field.
        var power = _createdCharacter?.Power ?? 0;
        var target = OtherBattleParticipants().FirstOrDefault(participant =>
            participant.Unit.Id == targetId && participant.IsHostileTo(power, 0));
        if (target is null)
            return RejectCommandVisibly("FIGHT_TARGET_NOT_HOSTILE", type, "その艦隊には仕掛けられません");
        if (!_tacticalEncounter.HasSurvivors(targetId))
            return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");

        OriginalTroopMoveResult spent;
        try
        {
            spent = await _store.SpendOriginalTroopsAsync(_accountId,
                new OriginalTroopMoveWrite(_worldCharacterId, _worldGridUnitId, 0, _worldGridCellId),
                AuthoredAssaultMoraleStep, cancellationToken);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.tactical-fight.persistence-not-supported", type);
        }
        if (!spent.Applied)
        {
            return RejectCommandVisibly(spent.ErrorCode ?? "FIGHT_REJECTED", type,
                spent.ErrorCode == "TROOP_NONE_CARRIED" ? "白兵戦を仕掛ける兵がいません" : "白兵戦を仕掛けられません");
        }

        var damage = OriginalTacticalCommandAuthority.ApplyAuthoredDamage(
            _tacticalEncounter.GetUnitDamage(targetId), _tacticalEncounter.UnitNumber(targetId));
        await _battles.CommitUnitDamageAsync(_worldGridCellId, number, targetId,
            damage, cancellationToken, target.ShipGeneration);
        RecordTacticalCommand(type, _gameClock.Tick);
        _receipt.Record("tactical-fight", FormattableString.Invariant(
            $"unit={_worldGridUnitId};target={targetId};troops={spent.Carried};damaged={damage.Damaged};from={from.X:F3}/{from.Y:F3}/{from.Z:F3}"),
            _accountId);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            AdditionalResponses =
            [
                EncodeApplicationPush(EncodeTacticalBattlefieldUnits()),
            ],
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-fight-accepted;unit={_worldGridUnitId};target={targetId};troops={spent.Carried};damaged={damage.Damaged};design=new")
        };
    }
}
