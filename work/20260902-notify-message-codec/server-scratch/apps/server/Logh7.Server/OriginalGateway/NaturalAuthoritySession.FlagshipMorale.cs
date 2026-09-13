using System.Security.Cryptography;
using System.Text;
using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// 鼓舞 (0x0409 CommandEncourageFlagship). NEW_DESIGN: the original cost and
    /// the original morale gain are not recovered. Encouraging a fleet is a
    /// military act, so it is charged to the military pool in the command's own
    /// transaction, and a refused encouragement spends nothing.
    /// </summary>
    public const uint FlagshipEncouragePointCost = 40;

    /// <summary>The morale ceiling the 0x0326 record has always been served at.</summary>
    public const byte FlagshipMoraleMaximum = 100;

    private async Task<NaturalAuthoritySessionResult> ProcessEncourageFlagshipAsync(
        byte[] payload, ushort type, uint sequence, CancellationToken cancellationToken)
    {
        if (!OriginalEncourageFlagshipCodec.TryDecode(payload, out var command))
            return RejectCommandVisibly("original.flagship-encourage.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("FLAGSHIP_ENCOURAGE_WORLD_REQUIRED", type, "鼓舞できません");
        // ORIGINAL_STATIC 2026-09-10: the client's own logger and the sender
        // 0x004B45D0 name these words - id is the acting character, unit is the
        // fleet. The live capture could not separate them (both were 2), and
        // checking the character id against the unit id happened to pass there
        // and would have been wrong for any other world.
        if (command.Id != _worldCharacterId || command.Unit != _worldGridUnitId)
            return RejectCommandVisibly("FLAGSHIP_ENCOURAGE_UNIT_NOT_CONTROLLED", type,
                "この艦隊は指揮できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);
        using var lease = await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (!_tacticalEncounter.HasSurvivors(_worldGridUnitId))
            return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;
        if (_persistedGridUnit is not OriginalGridUnitRecord unit)
            return Invalid("original.flagship-encourage.persistence-not-supported", type);

        var fingerprint = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            FormattableString.Invariant(
                $"original-flagship-encourage/v1|{_moveGridRequestScope:N}|{sequence}|{Convert.ToHexString(payload)}"))));
        OriginalFlagshipEncourageResult encouraged;
        try
        {
            encouraged = await _store.EncourageOwnOriginalFlagshipAsync(_accountId,
                new OriginalFlagshipEncourageWrite(fingerprint, unit.CharacterId, unit.UnitId,
                    unit.AuthorityVersion, unit.ShipGeneration, FlagshipMoraleMaximum,
                    new OriginalMoveGridPointCharge(OriginalCommandPointPool.Military,
                        FlagshipEncouragePointCost, CommandPointPolicy.Value, _gameClock.Now,
                        IsCurrentTacticalFieldActive)),
                cancellationToken);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.flagship-encourage.persistence-not-supported", type);
        }

        if (encouraged.Status == OriginalFlagshipEncourageStatus.Replayed)
            return Invalid("original.flagship-encourage.replay", type);
        if (encouraged.Status != OriginalFlagshipEncourageStatus.Encouraged || encouraged.Unit is null)
        {
            return RejectCommandVisibly(encouraged.ErrorCode ?? "FLAGSHIP_ENCOURAGE_REJECTED", type,
                FlagshipEncourageRejectionText(encouraged.ErrorCode));
        }

        RecordTacticalCommand(type, _gameClock.Tick);
        ApplyPersistedGridUnit(encouraged.Unit);
        PublishOwnParticipantSnapshot();
        var balances = (await _store.ListCharactersAsync(_accountId, cancellationToken))
            .SingleOrDefault(row => row.CharacterId == _worldCharacterId);
        if (balances is not null)
        {
            _worldPcp = balances.Pcp;
            _worldMcp = balances.Mcp;
        }
        _receipt.Record("flagship-encourage",
            FormattableString.Invariant($"unit={encouraged.Unit.UnitId};morale={encouraged.Unit.Morale}"),
            _accountId);
        // The command's own body comes back unchanged: the client stores the
        // 16-byte record and reads no result field from it (0x004BB917).
        var response = EncodeApplicationResponse(
            OriginalEncourageFlagshipCodec.Encode(command), type, includeLobbyPrefix: true);
        return response with
        {
            AdditionalResponses =
            [
                EncodeApplicationPush(OriginalSystemSceneCodec.EncodeTacticalUnitShips(
                    CurrentTacticalBattlefield())),
            ],
            ResponseMetadata = FormattableString.Invariant(
                $"flagship-encourage-accepted;unit={encouraged.Unit.UnitId};morale={encouraged.Unit.Morale};mcp-cost={FlagshipEncouragePointCost};pcp={_worldPcp};mcp={_worldMcp};authority-version={encouraged.AuthorityVersion};design=new")
        };
    }

    private static string FlagshipEncourageRejectionText(string? errorCode) => errorCode switch
    {
        "FLAGSHIP_ENCOURAGE_MORALE_FULL" => "士気はすでに十分です",
        "FLAGSHIP_ENCOURAGE_UNIT_DESTROYED" => "この艦隊は撃破されています",
        "FLAGSHIP_ENCOURAGE_UNIT_RECOVERING" => "この艦隊は復帰中です",
        "FLAGSHIP_ENCOURAGE_COMMAND_POINTS_INSUFFICIENT" => "コマンドポイントが足りません",
        _ => "鼓舞の条件を満たしていません",
    };
}
