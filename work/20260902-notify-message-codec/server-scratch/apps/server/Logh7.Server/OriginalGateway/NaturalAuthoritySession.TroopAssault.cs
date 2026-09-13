using System.Security.Cryptography;
using System.Text;
using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// AttackTroop (0x0417) - the landing force ashore assaults the base it stands on.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC for the shape: the client's logger prints
    /// <c>time / wait / id / unit[n] / target</c> - 所属変更's body - which the
    /// recovered decoder reads and <c>OriginalUnhandledCommandShapeTests</c>
    /// exercised while it was still unhandled.
    ///
    /// Both endpoints this needs already exist and neither was invented for it: the
    /// landing force comes from 陸戦 (0x040F) and a base's morale from EncourageBase
    /// (0x041D). The step is the authority's existing authored damage step - the
    /// same 25 a hit takes off a fleet - so an assault costs a base what a hit costs
    /// a squadron, rather than introducing a magnitude of its own.
    ///
    /// **Not modelled, and not pretended:** the base does not resist, nobody is lost
    /// on either side, and taking a base is not a thing this authority can do. The
    /// command moves the one number it can honestly move. Nothing recovered gives a
    /// ground-combat rule, and inventing one would be a different game.
    ///
    /// The command carries no schedule of its own - the tactical palette has no
    /// constmsg row for it - so none is enforced; a missing timing entry never
    /// gates a command.
    /// </remarks>
    private async Task<NaturalAuthoritySessionResult> ProcessTroopAssaultAsync(
        byte[] payload, ushort type, uint sequence, CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeUnitListWithTarget(payload, type, out var units, out var target))
            return RejectCommandVisibly("original.tactical-attack-troop.request-shape", type,
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
        if (ActiveBattlefieldCatalog.ProjectTacticalBases(_worldGridCellId, [target]).Count != 1)
            return RejectCommandVisibly("TROOP_TARGET_BASE_UNKNOWN", type, "その拠点はありません");

        var fingerprint = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            FormattableString.Invariant(
                $"original-troop-assault/v1|{_moveGridRequestScope:N}|{sequence}|{Convert.ToHexString(payload)}"))));
        OriginalBaseEncourageResult assaulted;
        try
        {
            assaulted = await _store.AssaultOriginalBaseAsync(_accountId,
                new OriginalBaseAssaultWrite(fingerprint, _worldCharacterId, _worldGridUnitId,
                    target, _worldGridCellId, AuthoredAssaultMoraleStep),
                cancellationToken);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.tactical-attack-troop.persistence-not-supported", type);
        }
        if (assaulted.Status == OriginalBaseEncourageStatus.Replayed)
            return Invalid("original.tactical-attack-troop.replay", type);
        if (assaulted.Status != OriginalBaseEncourageStatus.Encouraged)
        {
            return RejectCommandVisibly(assaulted.ErrorCode ?? "TROOP_ASSAULT_REJECTED", type,
                assaulted.ErrorCode == "TROOP_NONE_LANDED"
                    ? "陸戦ユニットがいません" : "攻撃できません");
        }

        RecordTacticalCommand(type, _gameClock.Tick);
        _receipt.Record("tactical-attack-troop", FormattableString.Invariant(
            $"unit={_worldGridUnitId};base={target};morale={assaulted.Morale}"), _accountId);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-attack-troop-accepted;unit={_worldGridUnitId};base={target};morale={assaulted.Morale};authority-version={assaulted.AuthorityVersion};design=new")
        };
    }

    /// <summary>
    /// What one assault takes off a base's morale. NEW_DESIGN, but deliberately the
    /// authority's existing authored damage step rather than a second magnitude: a
    /// hit takes 25 off a fleet, so an assault takes 25 off a base.
    /// </summary>
    public const byte AuthoredAssaultMoraleStep = 25;
}
