namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// <c>StopTroop</c> = 0x0418: 停止 addressed to a landing force - cancel what the
    /// named unit's troop command is carrying out.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC: the body is the client's own. Its logger prints
    /// <c>time / wait / id / unit[n]</c> - the same field list as 撤退 and StopFleet -
    /// so the recovered warp decoder reads it with the type as a parameter, and
    /// <c>OriginalUnhandledCommandShapeTests</c> was already exercising that shape
    /// while the command was still refused.
    ///
    /// What it cancels is real and already modelled: 陸戦 and 陸戦解除 each occupy the
    /// unit for the 240 G秒 their own rows state, and that occupancy is what 停止
    /// 「行動をキャンセルする」 clears for a fleet. This is the same clear.
    ///
    /// **One honest limitation, stated rather than papered over:** this authority
    /// tracks one occupancy per unit, not one per command family. The client has a
    /// separate stop for troops because it has a separate troop queue; here the two
    /// stops coincide, so StopTroop cancels whatever the unit is carrying out. That
    /// is a smaller thing than the original's, and it is not disguised as more.
    ///
    /// The original has no schedule row for it, so nothing gates it - an
    /// unrecovered schedule must never become an invented wait.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessStopTroopAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeWarpCommand(payload, type, out var command))
            return RejectCommandVisibly("original.tactical-stop-troop.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("original.tactical-stop-troop.world-not-entered", type,
                "この命令は実行できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        using var lease = await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (command.UnitIds.Count == 0)
            return RejectCommandVisibly("TROOP_UNIT_REQUIRED", type, "停止する艦隊がありません");
        if (command.UnitIds.Any(unit => unit != _worldGridUnitId))
            return RejectCommandVisibly("TROOP_UNIT_NOT_CONTROLLED", type, "この艦隊は指揮できません");

        foreach (var unit in command.UnitIds.Distinct())
            _battles.ClearExecuting(unit);
        RecordTacticalCommand(type, _gameClock.Tick);
        _receipt.Record("tactical-stop-troop",
            FormattableString.Invariant($"units={string.Join('/', command.UnitIds)}"), _accountId);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-stop-troop-accepted;units={string.Join('/', command.UnitIds)};design=new")
        };
    }
}
