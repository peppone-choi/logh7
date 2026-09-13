namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// <c>CommandStopFleet</c> = 0x0415: 停止 addressed to named fleets instead of
    /// to whatever is selected. It cancels what those fleets are carrying out,
    /// which is what constmsg group 0 row 13 states 停止 does -
    /// 「行動をキャンセルする」 - and what this authority already models as the
    /// 実行所要時間 occupancy a command holds on a unit.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC: the body is the client's own. <c>_INF:CommandStopFleet#</c>
    /// (0x00497B30) prints <c>time / wait / id / unit[n]</c> and nothing else - the
    /// same field list as 撤退's <c>_INF:CommandWarpShip#</c> - so the recovered
    /// warp decoder reads it with the type as a parameter, no new shape guessed.
    ///
    /// This authority gives the player one fleet, so the only unit he may name is
    /// his own; naming any other is refused visibly rather than silently ignored.
    /// The original has no schedule row of its own for StopFleet (group 0 row 13
    /// belongs to 停止 0x040A), so nothing gates it - an unrecovered schedule must
    /// never become an invented wait.
    ///
    /// **This build of the client cannot send it.** The request dispatcher's
    /// selector table has no arm for 0x0415 - see
    /// <see cref="OriginalTacticalCommandCatalog.CanBeSentByClient"/> - so there is
    /// no live receipt for this handler and none is claimed. It is implemented
    /// because the type is part of the recovered protocol and because a frame that
    /// does arrive must be answered; it is not evidence of a working palette
    /// button.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessTacticalStopFleetAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeWarpCommand(payload, type, out var command))
            return RejectCommandVisibly("original.tactical-stop-fleet.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("original.tactical-stop-fleet.world-not-entered", type,
                "この命令は実行できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        using var lease = await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (command.UnitIds.Count == 0)
            return RejectCommandVisibly("STOP_FLEET_UNIT_REQUIRED", type, "停止する艦隊がありません");
        if (command.UnitIds.Any(unit => unit != _worldGridUnitId))
            return RejectCommandVisibly("STOP_FLEET_UNIT_NOT_CONTROLLED", type, "この艦隊は指揮できません");

        // Exactly what 停止 does for the selection, for the fleets this names.
        foreach (var unit in command.UnitIds.Distinct())
            _battles.ClearExecuting(unit);
        RecordTacticalCommand(type, _gameClock.Tick);
        _receipt.Record("tactical-stop-fleet",
            FormattableString.Invariant($"units={string.Join('/', command.UnitIds)}"), _accountId);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-stop-fleet-accepted;units={string.Join('/', command.UnitIds)};design=new")
        };
    }
}
