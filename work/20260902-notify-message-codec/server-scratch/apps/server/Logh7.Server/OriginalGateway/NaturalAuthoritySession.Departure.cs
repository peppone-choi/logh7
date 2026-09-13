using System.Security.Cryptography;
using System.Text;
using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    private async Task<NaturalAuthoritySessionResult> ProcessOwnDepartureAsync(
        byte[] payload,uint sequence,CancellationToken cancellationToken)
    {
        const ushort type=OriginalSwitchModeCodec.RequestType;
        if(!OriginalSwitchModeCodec.TryDecodeRequest(payload,out var request))
            return RejectCommandVisibly("original.departure.request-shape", type,
                "この命令は実行できません");
        if(!_worldEntered || _createdCharacter is null || _worldCharacterId==0 || _worldGridUnitId==0)
            return RejectCommandVisibly("DEPARTURE_WORLD_REQUIRED",type,"出港できません");
        if(request.ActorId!=_worldCharacterId)
            return RejectCommandVisibly("DEPARTURE_ACTOR_NOT_OWNED",type,"出港する権限がありません");
        // ORIGINAL_STATIC E121/E122: own-ship sender004B4A00 emits4/5 with
        // zeroed card/PCP/MCP/spot/arrays.4 garrisons;5 leaves port for orbit.
        // Do not silently treat other requests as this supported action.
        if(request.Mode is not (4 or 5) || request.Card!=0 || request.Pcp!=0 || request.Mcp!=0 ||
            request.Spot!=0 || request.SpotOwner!=0 || request.Units.Count!=0 || request.MoveCharacters.Count!=0)
            return RejectCommandVisibly("DEPARTURE_ROUTE_NOT_IMPLEMENTED",type,"この出港操作は未対応です");
        if(TacticalScheduleRefusal(type,_gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;
        var number=OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number;
        using var lease=await _battles.LockAsync(_worldGridCellId,number,cancellationToken);
        OriginalGridUnitRecord? unit;
        try { unit=await _store.FindOriginalGridUnitAsync(_accountId,_worldCharacterId,_worldGridUnitId,cancellationToken); }
        catch(NotSupportedException) { return RejectCommandVisibly("DEPARTURE_STORAGE_REQUIRED",type,"出港情報を保存できません"); }
        if(unit is null || unit.CurrentCellId!=_worldGridCellId || !HasCurrentShipIncarnation ||
            unit.ShipGeneration!=CurrentShipGeneration)
            return RejectCommandVisibly("DEPARTURE_SCENE_STALE",type,"情報を更新してください");
        // NEW_DESIGN first strategic route; tactical departures need entity
        // positions and042F in addition to the player-context0B0B notification.
        var map=ActiveBattlefieldCatalog.Resolve(unit.CurrentCellId);
        if(HasUnsafeBattlefield(unit.CurrentCellId,unit.UnitId,_createdCharacter.Value.Power))
            return RejectCommandVisibly("DEPARTURE_TACTICAL_ROUTE_PENDING",type,"戦闘中の出港は未対応です");
        if(unit.BaseId!=0 && !map.ProjectBaseInformation(unit.CurrentCellId)
            .Any(b=>b.Id==unit.BaseId && b.Power==_createdCharacter.Value.Power))
            return RejectCommandVisibly("DEPARTURE_BASE_UNAVAILABLE",type,"出港できる拠点がありません");
        if(unit.Mode==request.Mode || (request.Mode==5 && unit.Mode!=4) ||
            unit.InjuryReturnId is not null || !_battles.GetEncounter(unit.CurrentCellId,number).HasSurvivors(unit.UnitId))
            return RejectCommandVisibly("DEPARTURE_UNIT_UNAVAILABLE",type,"この艦は出港できません");
        if(request.Mode==4 && FindPublicFlagshipPort(unit.CurrentCellId,unit.BaseId)==0)
            return RejectCommandVisibly("DEPARTURE_PORT_UNAVAILABLE",type,"宇宙港がありません");
        var fingerprint=Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            FormattableString.Invariant($"original-departure/v1|{_moveGridRequestScope:N}|{sequence}|{Convert.ToHexString(payload)}"))));
        OriginalDepartureStoreResult result;
        try
        {
            result=await _store.DepartOwnOriginalUnitAsync(_accountId,new(fingerprint,unit.CharacterId,unit.UnitId,
                unit.AuthorityVersion,unit.ShipGeneration,unit.BaseId,(byte)request.Mode),cancellationToken);
        }
        catch(NotSupportedException) { return RejectCommandVisibly("DEPARTURE_STORAGE_REQUIRED",type,"出港情報を保存できません"); }
        catch(InvalidOperationException ex) when(ex.Message.StartsWith("DEPARTURE_",StringComparison.Ordinal))
        { return RejectCommandVisibly(ex.Message,type,"出港条件を満たしていません"); }
        if(result.Unit.UnitId!=unit.UnitId || result.Unit.CharacterId!=unit.CharacterId ||
            result.Unit.CurrentCellId!=unit.CurrentCellId || result.Unit.ShipGeneration!=unit.ShipGeneration)
            return Invalid("original.departure.persisted-state-shape",type);
        RecordTacticalCommand(type,_gameClock.Tick);
        ApplyPersistedGridUnit(result.Unit);
        PublishOwnParticipantSnapshot();
        _receipt.Record("departure",$"unit-{unit.UnitId};base-{result.Unit.BaseId};mode-{result.Unit.Mode};updated-{result.Updated}",_accountId);
        return EncodeApplicationResponse(OriginalMovedBaseCodec.Encode(new(
            _gameClock.Tick,_worldCharacterId,result.Unit.BaseId,result.Unit.Mode,CurrentPublicPortSpot(),0,[_worldCharacterId])),
            type,includeLobbyPrefix:true) with
        {
            AdditionalResponses=[EncodeApplicationPush(OriginalWorldEntryCodec.EncodeUnits([CurrentPlayerInformationUnit()]))],
            ResponseMetadata=$"departure-unit={unit.UnitId};authority-version={result.Unit.AuthorityVersion};design=new"
        };
    }

    private uint FindPublicFlagshipPort(uint grid,uint baseId) =>
        (ActiveBattlefieldCatalog.Resolve(grid).BaseInstitutions ?? [])
            .Where(b=>b.Id==baseId)
            .SelectMany(b=>b.Institutions).Where(i=>i.Kind==4)
            .SelectMany(i=>i.Spots).Where(s=>s.Kind==6)
            .OrderBy(s=>s.Id).Select(s=>s.Id).FirstOrDefault();

    private uint CurrentPublicPortSpot() =>
        _persistedGridUnit is { Mode:4,BaseId:>0 } unit
            ? FindPublicFlagshipPort(unit.CurrentCellId,unit.BaseId) : 0;

    // FALSIFIED 2026-09-09 (v345): the character record's spot-owner word is not
    // the base that holds the port. Serving the base id there did not unlock any
    // BASE-target card command and made the client lose the spot's name - the
    // location panel went from 宇宙港/旗艦桟橋 to 宇宙港/スポット不明. Whatever
    // owns a spot, it is not a base id, so this stays zero until it is known.
    private byte[] EncodeLocatedCharacter(uint characterId,uint unitId,ushort card,
        OriginalCreateCharacterCommand character) =>
        OriginalWorldEntryCodec.EncodeCharacter(characterId,unitId,card,character,
            characterId==_worldCharacterId && unitId==_worldGridUnitId ? CurrentPublicPortSpot() : 0,0,
            characterId==_worldCharacterId ? _worldPcp : 0,
            characterId==_worldCharacterId ? _worldMcp : 0);
}
