using System.Security.Cryptography;
using System.Text;
using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    /// <summary>
    /// 完全修復 (0x0C00). The manual's command table prices this command at 160
    /// points. Which pool it is drawn from is INFERRED - repairing a fleet is a
    /// military act - and is charged in the repair's own transaction, so a
    /// refused repair spends nothing.
    /// </summary>
    public const uint FlagshipRepairPointCost = 160;

    private async Task<NaturalAuthoritySessionResult> ProcessCompletenessRepairAsync(
        byte[] payload, ushort type, uint sequence, CancellationToken cancellationToken)
    {
        if (!OriginalCompletenessRepairCodec.TryDecode(payload, out var command))
            return RejectCommandVisibly("original.flagship-repair.request-shape", type,
                "この命令は実行できません");
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
            return RejectCommandVisibly("FLAGSHIP_REPAIR_WORLD_REQUIRED", type, "修復できません");
        if (command.ActorId != _worldCharacterId)
            return RejectCommandVisibly("FLAGSHIP_REPAIR_ACTOR_NOT_OWNED", type, "修復する権限がありません");
        // The request's result words are the client's own copy of the outcome.
        // They are never inputs: the authority derives the repair from its own
        // state, so only the unit ids in the list are read.
        if (command.Ships.Count == 0 || command.Ships.Any(row=>row.UnitId==0) ||
            command.Ships.Select(row=>row.UnitId).Distinct().Count()!=command.Ships.Count)
            return RejectCommandVisibly("FLAGSHIP_REPAIR_UNIT_NOT_CONTROLLED", type,
                "この艦は修復できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);
        using var lease = await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (_persistedGridUnit is not OriginalGridUnitRecord unit)
            return Invalid("original.flagship-repair.persistence-not-supported", type);

        var includeFlagship=command.Ships.Any(row=>row.UnitId==unit.UnitId);
        var escortIds=command.Ships.Where(row=>row.UnitId!=unit.UnitId).Select(row=>row.UnitId).ToHashSet();
        OriginalFleetUnitRecord[] escorts=[];
        Action? commitEscorts=null;
        if(escortIds.Count!=0)
        {
            if(_store is not IOriginalFleetUnitStoreProvider provider)
                return RejectCommandVisibly("FLEET_REPAIR_STORE_UNAVAILABLE",type,"修復できません");
            escorts=(await provider.FleetUnits.ReadGridAsync(unit.CurrentCellId,cancellationToken))
                .Where(row=>escortIds.Contains(row.UnitId)).OrderBy(row=>row.UnitId).ToArray();
            if(escorts.Length!=escortIds.Count || escorts.Any(row=>row.ControllerCharacterId!=unit.CharacterId ||
                row.ControllerUnitId!=unit.UnitId || row.ControllerGeneration!=unit.ShipGeneration || row.Autonomous))
                return RejectCommandVisibly("FLEET_REPAIR_UNIT_NOT_CONTROLLED",type,"この艦は修復できません");
            try
            {
                commitEscorts=_battles.PrepareFleetRepairs(escorts,escorts.Select(row=>row with {
                    Damaged=row.Destroyed,Supplies=0,Revision=checked(row.Revision+1) }).ToArray());
            }
            catch(InvalidOperationException)
            {
                return RejectCommandVisibly("FLEET_REPAIR_SCENE_STALE",type,"艦の情報を再読み込みしてください");
            }
        }

        var fingerprint = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            FormattableString.Invariant(
                $"original-flagship-repair/v1|{_moveGridRequestScope:N}|{sequence}|{Convert.ToHexString(payload)}"))));
        OriginalFlagshipRepairResult repaired;
        try
        {
            repaired = await _store.RepairOwnOriginalFlagshipAsync(_accountId,
                new OriginalFlagshipRepairWrite(fingerprint, unit.CharacterId, unit.UnitId,
                    unit.AuthorityVersion, unit.ShipGeneration,
                    new OriginalMoveGridPointCharge(OriginalCommandPointPool.Military,
                        FlagshipRepairPointCost, CommandPointPolicy.Value, _gameClock.Now,
                        IsCurrentTacticalFieldActive),escorts,includeFlagship),
                cancellationToken);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.flagship-repair.persistence-not-supported", type);
        }
        catch (Npgsql.PostgresException)
        {
            // A server-reported SQL failure rolls back this command's transaction.
            // Do not publish the prepared memory transition or report success.
            // Transport/cancellation failures are not treated as known rollback.
            return RejectCommandVisibly("FLEET_REPAIR_STORAGE_FAILED",type,"修復を保存できませんでした");
        }

        if (repaired.Status == OriginalFlagshipRepairStatus.Replayed)
            return Invalid("original.flagship-repair.replay", type);
        if (repaired.Status != OriginalFlagshipRepairStatus.Repaired || repaired.Unit is null)
        {
            return RejectCommandVisibly(repaired.ErrorCode ?? "FLAGSHIP_REPAIR_REJECTED", type,
                FlagshipRepairRejectionText(repaired.ErrorCode));
        }

        commitEscorts?.Invoke();
        ApplyPersistedGridUnit(repaired.Unit);
        _tacticalEncounter.RecordUnitDamage(repaired.Unit.UnitId,
            new(repaired.Unit.Damaged, repaired.Unit.Destroyed));
        PublishOwnParticipantSnapshot();
        var balances = (await _store.ListCharactersAsync(_accountId, cancellationToken))
            .SingleOrDefault(row => row.CharacterId == _worldCharacterId);
        if (balances is not null)
        {
            _worldPcp = balances.Pcp;
            _worldMcp = balances.Mcp;
        }
        _receipt.Record("flagship-repair",
            FormattableString.Invariant(
                $"unit={repaired.Unit.UnitId};damaged={repaired.Unit.Damaged};supplies={repaired.Unit.Supplies}"),
            _accountId);
        // The client's own record shape carries the outcome back in the result
        // words it left unset.
        var outcomes=(repaired.Escorts ?? []).ToDictionary(row=>row.UnitId,
            row=>new OriginalCompletenessRepairShip(row.UnitId,row.Damaged,row.Supplies));
        if(includeFlagship) outcomes[repaired.Unit.UnitId]=new(repaired.Unit.UnitId,repaired.Unit.Damaged,repaired.Unit.Supplies);
        var updates=new List<NaturalAuthorityPush>{EncodeApplicationPush(OriginalWorldEntryCodec.EncodeUnits([CurrentPlayerInformationUnit()]))};
        foreach(var escort in repaired.Escorts ?? [])
        {
            var snapshot=_battles.NpcSnapshot(unit.CurrentCellId,escort.UnitId)!;
            var update=OriginalWorldEntryCodec.EncodeUnits([snapshot.Unit]);
            updates.Add(EncodeApplicationPush(update));
            _battles.Publish(unit.CurrentCellId,OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number,
                PendingNotifications.Writer,[update],targetUnit:escort.UnitId,targetEntry:snapshot.EncodeEntry());
        }
        var response = EncodeApplicationResponse(
            OriginalCompletenessRepairCodec.Encode(command with
            {
                Pcp = _worldPcp,
                Mcp = _worldMcp,
                Ships = command.Ships.Select(row=>outcomes[row.UnitId]).ToArray(),
            }),
            type,
            includeLobbyPrefix: true);
        return response with
        {
            AdditionalResponses = updates,
            ResponseMetadata = FormattableString.Invariant(
                $"flagship-repair-accepted;unit={repaired.Unit.UnitId};damaged={repaired.Unit.Damaged};destroyed={repaired.Unit.Destroyed};supplies={repaired.Unit.Supplies};mcp-cost={FlagshipRepairPointCost};pcp={_worldPcp};mcp={_worldMcp};authority-version={repaired.AuthorityVersion};design=new")
        };
    }

    private static string FlagshipRepairRejectionText(string? errorCode) => errorCode switch
    {
        "FLAGSHIP_REPAIR_NOT_IN_PORT" => "宇宙港に駐留してください",
        "FLAGSHIP_REPAIR_NOTHING_DAMAGED" => "修復する損害がありません",
        "FLAGSHIP_REPAIR_NO_SUPPLIES" => "軍需物資が足りません",
        "FLAGSHIP_REPAIR_COMMAND_POINTS_INSUFFICIENT" => "コマンドポイントが足りません",
        _ => "修復条件を満たしていません",
    };
}
