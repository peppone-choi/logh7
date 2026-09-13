using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// MoveTroop (0x0416) - a landing force already ashore marches to another area.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC for the shape: the client's logger prints
    /// <c>time / wait / id / unit[n] / area</c> and carries its own
    /// 「<c>unit_size[%d] is over than 32</c>」 bounds check, so the recovered
    /// unit-list-with-one-byte decoder reads it.
    ///
    /// **The byte is read, and the reason is the client's own name for it.** The
    /// logger calls it <c>area</c>, and the areas a tactical grid has in this
    /// authority are its bases, so the byte names the base the force marches to. A
    /// value that is not a base this grid carries is refused rather than guessed
    /// at. This is a narrower claim than it looks: 陸戦's byte is called
    /// <c>skill</c> by the same logger and is *not* interpreted, precisely because
    /// nothing recovered says what a skill is.
    ///
    /// The force keeps its strength - this moves where it stands and nothing else.
    /// What standing at one area rather than another means for a landed force is
    /// still not modelled, and is not pretended here.
    ///
    /// The command has no constmsg row, so no schedule is enforced.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessMoveTroopAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeUnitListWithByte(payload, type, out var units, out var area))
            return RejectCommandVisibly("original.tactical-move-troop.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("TROOP_WORLD_REQUIRED", type, "この命令は実行できません");
        if (units.UnitIds.Count == 0 || units.UnitIds.Any(unit => unit != _worldGridUnitId))
            return RejectCommandVisibly("TROOP_UNIT_NOT_CONTROLLED", type, "この艦隊は指揮できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        using var lease = await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;

        uint destination = area;
        if (destination == 0 ||
            ActiveBattlefieldCatalog.ProjectTacticalBases(_worldGridCellId, [destination]).Count != 1)
            return RejectCommandVisibly("TROOP_AREA_UNKNOWN", type, "その区域はありません");

        OriginalTroopState ashore;
        try
        {
            ashore = await _store.ReadOriginalTroopStateAsync(
                _accountId, _worldCharacterId, _worldGridUnitId, cancellationToken);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.tactical-move-troop.persistence-not-supported", type);
        }
        if (ashore.Landed <= 0 || ashore.BaseId == 0)
            return RejectCommandVisibly("TROOP_NONE_LANDED", type, "陸戦ユニットがいません");

        OriginalTroopMoveResult moved;
        try
        {
            moved = await _store.RelocateOriginalTroopsAsync(_accountId,
                new OriginalTroopMoveWrite(_worldCharacterId, _worldGridUnitId, ashore.BaseId, _worldGridCellId),
                destination, cancellationToken);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.tactical-move-troop.persistence-not-supported", type);
        }
        if (!moved.Applied)
        {
            return RejectCommandVisibly(moved.ErrorCode ?? "TROOP_MOVE_REJECTED", type,
                moved.ErrorCode == "TROOP_ALREADY_THERE" ? "すでにその区域にいます" : "移動できません");
        }

        RecordTacticalCommand(type, _gameClock.Tick);
        _receipt.Record("tactical-move-troop", FormattableString.Invariant(
            $"unit={_worldGridUnitId};from={ashore.BaseId};to={destination};landed={moved.Landed}"), _accountId);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-move-troop-accepted;unit={_worldGridUnitId};from={ashore.BaseId};to={destination};landed={moved.Landed};authority-version={moved.AuthorityVersion};design=new")
        };
    }
}
