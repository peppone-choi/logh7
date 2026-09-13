namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// 隊列変更 = 0x040D CommandFileFleet. constmsg group 0 row 16:
    /// 「[ 隊列変更 ] 任意の隊列に変更する 実行待機時間48G秒 実行所要時間0G秒」 - put the units
    /// into a chosen formation.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC for the shape and the schedule. The client's own logger
    /// <c>_INF:CommandFileFleet#</c> (0x00496050) prints
    /// <c>time / wait / id / position[n]{id, direction, {x,y,z}} / kind</c> - the
    /// same 20-byte per-unit element 移動 carries, followed by one formation byte -
    /// and row 16 gives the 48 G秒 wait and a stated duration of 0.
    ///
    /// The <c>kind</c> byte is the formation the client chose, and the eight
    /// values it can take are now recovered from the client itself: pressing
    /// 隊列変更 opens a sub-panel whose cells name 防御 / 紡錘 / 艦種１ / 艦種２ /
    /// 混成１ / 混成２ / 散開 / 三列 (see
    /// <see cref="OriginalFleetFormationCatalog"/>). What each formation *does* is
    /// still not recovered, so the name is carried on the receipt and the response
    /// metadata and **not** turned into a combat modifier or a placement - but a
    /// value the client's own panel cannot produce is refused rather than echoed.
    ///
    /// The <c>position</c> array is decoded but **not applied**. A first draft of
    /// this handler took the last position as a destination, on the reasoning that
    /// a formation is where a unit stands. The live client falsified that: its
    /// 隊列変更 carries x/y/z of 7.02e-37 - denormal noise from a struct field it
    /// never fills - and applying them wrote that noise into the scene. Whatever
    /// the client means by those words, it is not a place this authority may move
    /// a ship to.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessTacticalFileFleetAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeFileFleetCommand(payload, out var command))
            return RejectCommandVisibly("original.tactical-file-fleet.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("original.tactical-file-fleet.world-not-entered", type,
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
        if (command.Positions.Count == 0)
            return RejectCommandVisibly("FILE_FLEET_UNIT_REQUIRED", type, "隊列を変更する艦隊がありません");
        if (command.Positions.Any(position => position.UnitId != _worldGridUnitId))
            return RejectCommandVisibly("FILE_FLEET_UNIT_NOT_CONTROLLED", type, "この艦隊は指揮できません");

        // The position array is NOT input. Captured live 2026-09-10: the shipped
        // client sent 隊列変更 with kind=7 and a position whose x/y/z read
        // 7.02e-37, 7.02e-37, 7.01e-37 - denormal noise from a struct field it
        // never fills, not a place to stand. An earlier draft of this handler
        // applied those words to the ship and wrote that noise into the scene.
        // The formation is identified by `kind`; where the ships then stand is
        // the client's own arrangement, and nothing recovered says what each
        // formation looks like. So the command is accepted, the formation is
        // recorded, and no position is invented from an uninitialised field.
        if (OriginalFleetFormationCatalog.NameOf(command.Kind) is not { } formation)
            return RejectCommandVisibly("FILE_FLEET_FORMATION_UNKNOWN", type, "その隊列はありません");
        RecordTacticalCommand(type, _gameClock.Tick);
        _receipt.Record("tactical-file-fleet", FormattableString.Invariant(
            $"unit={_worldGridUnitId};kind={command.Kind};formation={formation};positions={command.Positions.Count}"),
            _accountId);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-file-fleet-accepted;unit={_worldGridUnitId};kind={command.Kind};formation={formation};positions={command.Positions.Count};design=new")
        };
    }
}
