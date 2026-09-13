namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// 要塞砲 (0x0419) - a base firing its fortress gun along a bearing.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC for the shape and the schedule. The client's logger
    /// <c>_INF:CommandShootFortress#</c> prints <c>time / wait / id / base /
    /// direction</c> and carries no bounds-check string, so the command has no
    /// target list: **it aims by bearing, not at a unit**
    /// (evidence/every-body-recovered-v409.md). constmsg group 0 row 19 gives the
    /// schedule - 「[ 要塞砲 ] 要塞砲を発射する 実行待機時間48G秒 実行所要時間1800G秒」 - and the
    /// 1800 is a recovered number, so it is enforced as real occupancy.
    ///
    /// **The armament is recovered, not authored.** The tactical base record this
    /// authority already serves carries <c>CannonAngle</c> and <c>CannonStart</c>
    /// beside the base's position, so a base states its own gun and its own arc. A
    /// base whose content gives it no gun refuses the command for that stated
    /// reason rather than an unimplemented one.
    ///
    /// What the shot does is the authority's existing hit: the nearest hostile whose
    /// bearing from the base falls inside the gun's arc takes the same authored
    /// damage step a fleet's hit takes, through the same
    /// <c>CommitUnitDamageAsync</c> path. No second magnitude is introduced, and no
    /// range model is invented - the arc is the whole constraint the content states.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessShootFortressAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeShootFortressCommand(payload,
                out _, out _, out var order, out var baseId, out var direction))
            return RejectCommandVisibly("original.fortress-shoot.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("FORTRESS_WORLD_REQUIRED", type, "この命令は実行できません");
        if (order != _worldCharacterId)
            return RejectCommandVisibly("FORTRESS_ACTOR_NOT_CONTROLLED", type, "この命令は実行できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        var number = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number;
        using var lease = await _battles.LockAsync(_worldGridCellId, number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;

        var bases = ActiveBattlefieldCatalog.ProjectTacticalBases(_worldGridCellId, [baseId]);
        if (bases.Count != 1)
            return RejectCommandVisibly("FORTRESS_BASE_UNKNOWN", type, "その拠点はありません");
        var fortress = bases[0];
        // The content states the gun. A base without one says so.
        if (fortress.CannonAngle == 0)
            return RejectCommandVisibly("FORTRESS_NO_CANNON", type, "要塞砲がありません");

        var aim = Degrees(direction);
        var start = fortress.CannonStart % 360d;
        var span = Math.Min(360d, fortress.CannonAngle);
        if (!WithinArc(aim, start, span))
            return RejectCommandVisibly("FORTRESS_BEARING_OUTSIDE_ARC", type, "その方位は射界外です");

        var power = _createdCharacter?.Power ?? 0;
        OriginalTacticalParticipantSnapshot? victim = null;
        var nearest = double.MaxValue;
        foreach (var participant in OtherBattleParticipants())
        {
            if (!participant.IsHostileTo(power, 0)) continue;
            if (!_tacticalEncounter.HasSurvivors(participant.Unit.Id)) continue;
            var dx = participant.Ship.X - fortress.X;
            var dy = participant.Ship.Y - fortress.Y;
            var distance = Math.Sqrt(dx * dx + dy * dy);
            if (distance == 0 || !double.IsFinite(distance)) continue;
            if (!WithinArc(Degrees(MathF.Atan2(dy, dx)), start, span)) continue;
            if (distance >= nearest) continue;
            nearest = distance;
            victim = participant;
        }
        if (victim is null)
            return RejectCommandVisibly("FORTRESS_NO_TARGET_IN_ARC", type, "射界に目標がありません");

        var damage = OriginalTacticalCommandAuthority.ApplyAuthoredDamage(
            _tacticalEncounter.GetUnitDamage(victim.Unit.Id),
            _tacticalEncounter.UnitNumber(victim.Unit.Id));
        await _battles.CommitUnitDamageAsync(_worldGridCellId, number, victim.Unit.Id,
            damage, cancellationToken, victim.ShipGeneration);
        RecordTacticalCommand(type, _gameClock.Tick);
        _receipt.Record("tactical-fortress-shoot", FormattableString.Invariant(
            $"base={baseId};bearing={aim:F1};target={victim.Unit.Id};damaged={damage.Damaged}"), _accountId);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            AdditionalResponses =
            [
                EncodeApplicationPush(EncodeTacticalBattlefieldUnits()),
            ],
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-fortress-shoot-accepted;base={baseId};target={victim.Unit.Id};damaged={damage.Damaged};design=new")
        };
    }

    private static double Degrees(float radians)
    {
        var value = radians * 180d / Math.PI % 360d;
        return value < 0 ? value + 360d : value;
    }

    /// <summary>Whether a bearing falls inside an arc that may wrap past 360.</summary>
    private static bool WithinArc(double bearing, double start, double span)
    {
        if (span >= 360d) return true;
        var offset = (bearing - start) % 360d;
        if (offset < 0) offset += 360d;
        return offset < span;
    }
}
