using System.Security.Cryptography;
using System.Text;
using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// 態勢変更 (0x0411) and 出撃 (0x0412) - the tactical palette's own posture
    /// commands, as against the strategy card's 0x0B06.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC for both the shapes and the schedule. The client's loggers
    /// give the bodies: <c>_INF:CommandChangeMode#</c> (0x00497150) prints
    /// <c>time / wait / id / unit[n] / kind / target_base</c>, and
    /// <c>_INF:CommandSortie#</c> (0x00497570) prints <c>time / wait / id /
    /// unit[n]</c> - the same field list as 撤退, so the recovered warp decoder
    /// reads it. constmsg group 0 rows 20 and 21 give both commands
    /// 実行待機時間48G秒 / 実行所要時間240G秒, and row 21 even states 出撃's transition:
    /// 「駐留状態から碇泊状態へ」, i.e. mode 4 to mode 5. So neither the wait, the
    /// occupancy nor the transition is invented here.
    ///
    /// The posture change itself is the one the strategy route already performs -
    /// <c>DepartOwnOriginalUnitAsync</c>, the same guards, the same persisted
    /// transaction - which is the missing tactical departure route
    /// <see cref="ProcessOwnDepartureAsync"/> records in its own comment. What is
    /// added here is the client's own 0x042F <c>NotifyChangeMode</c> beside the
    /// player-context push, which the tactical scene needs and the strategy route
    /// does not send.
    ///
    /// Only modes 4 and 5 are supported, exactly as on the strategy route: they
    /// are the two the sender emits and the two the store models. Any other
    /// posture is refused visibly rather than half-applied.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessTacticalModeChangeAsync(
        byte[] payload, ushort type, uint sequence, CancellationToken cancellationToken)
    {
        IReadOnlyList<uint> units;
        byte mode;
        uint targetBase;
        if (type == OriginalTacticalCommandCodec.SortieCommandType)
        {
            if (!OriginalTacticalCommandCodec.TryDecodeWarpCommand(payload, type, out var sortie))
                return RejectCommandVisibly("original.tactical-sortie.request-shape", type,
                "この命令は実行できません");
            units = sortie.UnitIds;
            // Row 21 states the transition itself: 駐留状態 (4) to 碇泊状態 (5).
            mode = 5;
            targetBase = 0;
        }
        else
        {
            if (!OriginalTacticalCommandCodec.TryDecodeChangeModeCommand(payload, out var change))
                return RejectCommandVisibly("original.tactical-change-mode.request-shape", type,
                "この命令は実行できません");
            units = change.UnitIds;
            mode = change.Kind;
            targetBase = change.TargetBase;
        }

        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("TACTICAL_MODE_WORLD_REQUIRED", type, "態勢を変更できません");
        if (units.Count == 0 || units.Any(unit => unit != _worldGridUnitId))
            return RejectCommandVisibly("TACTICAL_MODE_UNIT_NOT_CONTROLLED", type, "この艦隊は指揮できません");
        // The client's own sub-panel offers four postures - 駐留 / 航行 / 戦闘 / 碇泊
        // (see OriginalUnitPostureCatalog). Only 駐留 and 碇泊 have an observed wire
        // value and a stored transition, so the other two are refused by name
        // rather than silently.
        if (mode is not (OriginalUnitPostureCatalog.Garrison or OriginalUnitPostureCatalog.Anchored))
            return RejectCommandVisibly("TACTICAL_MODE_NOT_IMPLEMENTED", type, "この態勢は未対応です");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;

        var number = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number;
        using var lease = await _battles.LockAsync(_worldGridCellId, number, cancellationToken);
        OriginalGridUnitRecord? unit;
        try
        {
            unit = await _store.FindOriginalGridUnitAsync(
                _accountId, _worldCharacterId, _worldGridUnitId, cancellationToken);
        }
        catch (NotSupportedException)
        {
            return RejectCommandVisibly("TACTICAL_MODE_STORAGE_REQUIRED", type, "態勢を保存できません");
        }
        if (unit is null || unit.CurrentCellId != _worldGridCellId || !HasCurrentShipIncarnation ||
            unit.ShipGeneration != CurrentShipGeneration)
            return RejectCommandVisibly("TACTICAL_MODE_SCENE_STALE", type, "情報を更新してください");
        if (targetBase != 0 && targetBase != unit.BaseId)
            return RejectCommandVisibly("TACTICAL_MODE_BASE_UNAVAILABLE", type, "その拠点では変更できません");
        if (unit.Mode == mode || (mode == 5 && unit.Mode != 4) ||
            unit.InjuryReturnId is not null ||
            !_battles.GetEncounter(unit.CurrentCellId, number).HasSurvivors(unit.UnitId))
            return RejectCommandVisibly("TACTICAL_MODE_UNIT_UNAVAILABLE", type, "この艦は態勢を変更できません");
        if (mode == 4 && FindPublicFlagshipPort(unit.CurrentCellId, unit.BaseId) == 0)
            return RejectCommandVisibly("TACTICAL_MODE_PORT_UNAVAILABLE", type, "宇宙港がありません");

        var fingerprint = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            FormattableString.Invariant(
                $"original-tactical-mode/v1|{_moveGridRequestScope:N}|{sequence}|{Convert.ToHexString(payload)}"))));
        OriginalDepartureStoreResult result;
        try
        {
            result = await _store.DepartOwnOriginalUnitAsync(_accountId,
                new(fingerprint, unit.CharacterId, unit.UnitId, unit.AuthorityVersion,
                    unit.ShipGeneration, unit.BaseId, mode),
                cancellationToken);
        }
        catch (NotSupportedException)
        {
            return RejectCommandVisibly("TACTICAL_MODE_STORAGE_REQUIRED", type, "態勢を保存できません");
        }
        catch (InvalidOperationException ex) when (ex.Message.StartsWith("DEPARTURE_", StringComparison.Ordinal))
        {
            return RejectCommandVisibly(ex.Message, type, "態勢変更の条件を満たしていません");
        }

        RecordTacticalCommand(type, _gameClock.Tick);
        ApplyPersistedGridUnit(result.Unit);
        PublishOwnParticipantSnapshot();
        var ship = _tacticalUnitShip ?? OriginalSystemSceneCodec.CreateTacticalBattlefield(
            _worldGridUnitId, _worldCharacterId, CurrentBattlefieldTemplate()).Records[0];
        _receipt.Record("tactical-mode",
            FormattableString.Invariant(
                $"unit={unit.UnitId};mode={result.Unit.Mode};base={result.Unit.BaseId}"),
            _accountId);
        var name = type == OriginalTacticalCommandCodec.SortieCommandType ? "sortie" : "change-mode";
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            AdditionalResponses =
            [
                // The tactical scene's own answer, which the strategy route omits.
                EncodeApplicationPush(OriginalSwitchModeCodec.EncodeNotification(new(
                    _gameClock.Tick, result.Unit.Mode, result.Unit.BaseId,
                    [new(unit.UnitId, ship.Direction, ship.X, ship.Y, ship.Z)],
                    CurrentPublicPortSpot(), 0))),
                EncodeApplicationPush(OriginalWorldEntryCodec.EncodeUnits([CurrentPlayerInformationUnit()])),
            ],
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-mode-accepted;name={name};unit={unit.UnitId};mode={result.Unit.Mode};base={result.Unit.BaseId};authority-version={result.Unit.AuthorityVersion};design=new")
        };
    }
}
