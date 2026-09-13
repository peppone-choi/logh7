using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// 陸戦 (0x040F) and 陸戦解除 (0x0410) - a unit's landing force going ashore and
    /// coming back.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC for the shapes and the schedules. constmsg group 0 row 17 is
    /// 「[ 陸戦 ] 碇泊状態から陸戦を投下する 実行待機時間48G秒 実行所要時間240G秒」 and row 18
    /// 「[ 陸戦解除 ] 陸戦ユニットを帰還させる 実行待機時間48G秒 実行所要時間240G秒」. The bodies are
    /// recovered too: 陸戦 carries a unit list and one byte, 陸戦解除 a unit list, and
    /// its body was captured from the shipped client on 2026-09-10.
    ///
    /// Row 17 states the precondition itself - 碇泊状態から - so the acting unit must
    /// be anchored, which is posture 5, and the base it is anchored at is the place
    /// the force lands. Neither the base nor the destination is guessed: the unit's
    /// own stored <c>base_id</c> supplies it, and 陸戦解除 brings that same force home
    /// from that same base.
    ///
    /// NEW_DESIGN, and named as such: where a unit's carried troops live. The troop
    /// *stock* concept is recovered - <c>OriginalStockKind.Troops</c>, the warehouse
    /// transfer primitive, and the party record's TroopPackages / Carrying fields -
    /// but stock is held per base and outfit, so the unit side had no endpoint. The
    /// complement is the same 100 the authority already uses for a unit's full
    /// stores rather than a second invented magnitude.
    ///
    /// What a landed force then *does* - hold ground, fight, take a base - is not
    /// modelled and is not pretended: the command moves the force and records where
    /// it is. MoveTroop / AttackTroop / StopTroop stay unhandled for exactly that
    /// reason, and say so in the coverage ledger.
    ///
    /// The 陸戦 byte is carried on the receipt and not branched on: nothing
    /// recovered says what it selects.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessTroopLandingAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        var landing = type == OriginalTacticalCommandCodec.SortieTroopsCommandType;
        IReadOnlyList<uint> units;
        byte selector = 0;
        if (landing)
        {
            if (!OriginalTacticalCommandCodec.TryDecodeUnitListWithByte(payload, type, out var list, out selector))
                return RejectCommandVisibly("original.tactical-sortie-troops.request-shape", type,
                "この命令は実行できません");
            units = list.UnitIds;
        }
        else
        {
            if (!OriginalTacticalCommandCodec.TryDecodeWarpCommand(payload, type, out var recall))
                return RejectCommandVisibly("original.tactical-evacuate-troops.request-shape", type,
                "この命令は実行できません");
            units = recall.UnitIds;
        }

        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("TROOP_WORLD_REQUIRED", type, "この命令は実行できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        var number = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number;
        using var lease = await _battles.LockAsync(_worldGridCellId, number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;

        // The landing force is this character's own, and it is persisted against
        // that unit, so no other unit can be ordered ashore from here.
        if (units.Count == 0 || units.Any(unit => unit != _worldGridUnitId))
            return RejectCommandVisibly("TROOP_UNIT_NOT_CONTROLLED", type, "この艦隊は指揮できません");
        if (!_tacticalEncounter.HasSurvivors(_worldGridUnitId))
            return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");
        if (_persistedGridUnit is not OriginalGridUnitRecord stored)
            return Invalid("original.tactical-troops.persistence-not-supported", type);

        uint baseId;
        if (landing)
        {
            // Row 17 states the precondition: 碇泊状態から. Anchored is posture 5, and
            // the base it is anchored at is where the force goes ashore.
            if (stored.Mode != OriginalUnitPostureCatalog.Anchored)
                return RejectCommandVisibly("TROOP_NOT_ANCHORED", type, "碇泊状態ではありません");
            baseId = stored.BaseId;
            if (baseId == 0)
                return RejectCommandVisibly("TROOP_BASE_REQUIRED", type, "拠点がありません");
        }
        else
        {
            OriginalTroopState ashore;
            try
            {
                ashore = await _store.ReadOriginalTroopStateAsync(
                    _accountId, _worldCharacterId, _worldGridUnitId, cancellationToken);
            }
            catch (NotSupportedException)
            {
                return Invalid("original.tactical-troops.persistence-not-supported", type);
            }
            if (ashore.Landed <= 0 || ashore.BaseId == 0)
                return RejectCommandVisibly("TROOP_NONE_LANDED", type, "陸戦ユニットがいません");
            baseId = ashore.BaseId;
        }

        OriginalTroopMoveResult moved;
        var write = new OriginalTroopMoveWrite(_worldCharacterId, _worldGridUnitId, baseId, _worldGridCellId);
        try
        {
            moved = landing
                ? await _store.LandOriginalTroopsAsync(_accountId, write, cancellationToken)
                : await _store.RecallOriginalTroopsAsync(_accountId, write, cancellationToken);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.tactical-troops.persistence-not-supported", type);
        }
        if (!moved.Applied)
        {
            return RejectCommandVisibly(moved.ErrorCode ?? "TROOP_MOVE_REJECTED", type,
                landing ? "陸戦を投下できません" : "陸戦ユニットを帰還させられません");
        }

        RecordTacticalCommand(type, _gameClock.Tick);
        _receipt.Record(landing ? "tactical-sortie-troops" : "tactical-evacuate-troops",
            FormattableString.Invariant(
                $"unit={_worldGridUnitId};base={baseId};carried={moved.Carried};landed={moved.Landed};selector={selector}"),
            _accountId);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            ResponseMetadata = FormattableString.Invariant(
                $"{(landing ? "tactical-sortie-troops" : "tactical-evacuate-troops")}-accepted;unit={_worldGridUnitId};base={baseId};carried={moved.Carried};landed={moved.Landed};authority-version={moved.AuthorityVersion};design=new")
        };
    }
}
