using System.Security.Cryptography;
using System.Text;
using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// EncourageBase (0x041D) - 鼓舞 aimed at a base instead of a fleet.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC for the shape: the client's own logger prints
    /// <c>time / wait / id / base</c> - a header and a base, nothing else - which
    /// the recovered base decoder reads and
    /// <c>OriginalUnhandledCommandShapeTests</c> exercised while it was still
    /// unhandled. The command is the base-side twin of 鼓舞 (0x0409), whose own row
    /// is 「[ 鼓舞 ] 味方ユニットの士気値を上げる 実行待機時間48G秒 実行所要時間0G秒」.
    ///
    /// NEW_DESIGN, and the same NEW_DESIGN the fleet's 鼓舞 already carries: the
    /// original states neither a morale rate nor a price for either, so the base is
    /// restored to its ceiling in one act and charged the same military-pool cost.
    /// The only genuinely new thing is that a base now has a morale at all - it had
    /// to, for the command to mean anything - and that state is persisted, so a
    /// restart restores it like every other committed fact.
    ///
    /// The base must be one this grid actually carries. A base at its ceiling is
    /// refused rather than charged.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessEncourageBaseAsync(
        byte[] payload, ushort type, uint sequence, CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeBaseCommand(payload, type,
                out _, out _, out var order, out var baseId))
            return RejectCommandVisibly("original.base-encourage.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("BASE_ENCOURAGE_WORLD_REQUIRED", type, "鼓舞できません");
        // The logger calls the third word id, and 0x004B4A90 takes it from the
        // acting character - the same field 鼓舞 carries.
        if (order != _worldCharacterId)
            return RejectCommandVisibly("BASE_ENCOURAGE_ACTOR_NOT_CONTROLLED", type, "この命令は実行できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        using var lease = await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;
        if (ActiveBattlefieldCatalog.ProjectTacticalBases(_worldGridCellId, [baseId]).Count != 1)
            return RejectCommandVisibly("BASE_ENCOURAGE_BASE_UNKNOWN", type, "その拠点はありません");

        var fingerprint = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            FormattableString.Invariant(
                $"original-base-encourage/v1|{_moveGridRequestScope:N}|{sequence}|{Convert.ToHexString(payload)}"))));
        OriginalBaseEncourageResult encouraged;
        try
        {
            encouraged = await _store.EncourageOriginalBaseAsync(_accountId,
                new OriginalBaseEncourageWrite(fingerprint, _worldCharacterId, baseId, _worldGridCellId,
                    FlagshipMoraleMaximum,
                    new OriginalMoveGridPointCharge(OriginalCommandPointPool.Military,
                        FlagshipEncouragePointCost, CommandPointPolicy.Value, _gameClock.Now,
                        IsCurrentTacticalFieldActive)),
                cancellationToken);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.base-encourage.persistence-not-supported", type);
        }
        if (encouraged.Status == OriginalBaseEncourageStatus.Replayed)
            return Invalid("original.base-encourage.replay", type);
        if (encouraged.Status != OriginalBaseEncourageStatus.Encouraged)
        {
            return RejectCommandVisibly(encouraged.ErrorCode ?? "BASE_ENCOURAGE_REJECTED", type,
                encouraged.ErrorCode == "BASE_ENCOURAGE_MORALE_FULL"
                    ? "鼓舞の必要がありません" : "鼓舞できません");
        }

        RecordTacticalCommand(type, _gameClock.Tick);
        _receipt.Record("tactical-base-encourage", FormattableString.Invariant(
            $"base={baseId};morale={encouraged.Morale}"), _accountId);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-base-encourage-accepted;base={baseId};morale={encouraged.Morale};authority-version={encouraged.AuthorityVersion};design=new")
        };
    }
}
