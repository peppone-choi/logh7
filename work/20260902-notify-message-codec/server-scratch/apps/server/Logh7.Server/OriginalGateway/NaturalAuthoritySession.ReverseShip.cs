namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// 反転 = 0x0403 CommandReverseShip. constmsg group 0 row 14:
    /// 「[ 反転 ] 旗艦を中心に反転する 実行待機時間48G秒 実行所要時間旋回性能+機動で算出」 - turn the
    /// selected units about the flagship to face the opposite way.
    /// </summary>
    /// <remarks>
    /// The body was already recovered (E031, 0x0049C020/0x00493C47) and the
    /// client's own logger <c>_INF:CommandReverseShip#</c> (0x00493E40) confirms
    /// it: <c>time / wait / id / unit[n]{id,direction} / direction</c>. Only the
    /// handler was missing, so 反転 was answered with the not-implemented refusal.
    ///
    /// NEW_DESIGN in one place, and it is named: the original states the duration
    /// as 「旋回性能+機動で算出」, a formula this authority has not recovered, so - as
    /// with 旋回 - the new heading is applied at once rather than turned through.
    /// The heading itself is not invented: it is the unit's current heading turned
    /// by half a turn, which is what 反転 means, and the command's own trailing
    /// <c>direction</c> byte selects which way round the client drew it.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessTacticalReverseShipAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeReverseShipCommand(payload, out var command))
            return RejectCommandVisibly("original.tactical-reverse-ship.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("original.tactical-reverse-ship.world-not-entered", type,
                "この命令は実行できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        using var lease = await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (!_tacticalEncounter.HasSurvivors(_worldGridUnitId))
            return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;
        if (command.Units.Count != 1 || command.Units[0].UnitId != _worldGridUnitId)
            return RejectCommandVisibly("TACTICAL_UNIT_NOT_CONTROLLED", type, "この艦隊は指揮できません");

        var current = _tacticalUnitShip ?? OriginalSystemSceneCodec.CreateTacticalBattlefield(
            _worldGridUnitId, _worldCharacterId, CurrentBattlefieldTemplate()).Records[0];
        var reversed = Normalize(current.Direction + MathF.PI);
        _tacticalUnitShip = current with { Direction = reversed };
        PublishOwnParticipantSnapshot();
        _receipt.Record("tactical-reverse-ship", FormattableString.Invariant(
            $"accepted;unit={_worldGridUnitId};from={current.Direction};to={reversed};side={command.Direction}"),
            _accountId);
        RecordTacticalCommand(type, _gameClock.Tick);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            AdditionalResponses =
            [
                EncodeApplicationPush(OriginalSystemSceneCodec.EncodeTacticalUnitShips(
                    CurrentTacticalBattlefield())),
                EncodeApplicationPush(OriginalTacticalCommandCodec.EncodeTurnedNotification(
                    new(_gameClock.Tick, _worldGridUnitId, reversed))),
            ],
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-reverse-ship-accepted;unit={_worldGridUnitId};from={current.Direction};direction={reversed};design=new")
        };
    }

    /// <summary>Headings are kept in (-pi, pi], the range every recovered heading uses.</summary>
    private static float Normalize(float radians)
    {
        var turn = MathF.PI * 2f;
        var value = MathF.IEEERemainder(radians, turn);
        if (value <= -MathF.PI) value += turn;
        if (value > MathF.PI) value -= turn;
        return value;
    }
}
