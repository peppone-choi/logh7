using Logh7.Server.Storage;
using System.Security.Cryptography;
using System.Text;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    private long _lastBaseTravelProjectionVersion;
    private string? _baseTravelRequestFingerprint = null;
    private TimeSpan? _baseTravelDelay = null;
    // Read by the hosting connection actor only, after ProcessAsync. Scheduler
    // uses a registered immutable owner/writer pair instead of reading session state.
    internal Guid? BaseTravelNotificationOwner => _accountId == Guid.Empty ? null : _accountId;
    private async Task<NaturalAuthoritySessionResult> ProcessBaseTravelAsync(byte[] payload,uint sequence,
        CancellationToken cancellationToken)
    {
        NaturalAuthoritySessionResult Reject(string reason) =>
            EncodeApplicationResponse(OriginalNotifyMessageCodec.EncodeInvalidMessage(
                OriginalMoveBaseCodec.RequestType,"寄港できません"),OriginalMoveBaseCodec.RequestType,true)
                with { ResponseMetadata="base-travel-rejected;"+reason };
        if (!OriginalMoveBaseCodec.TryDecodeRequest(payload,out var request)) return Reject("REQUEST_SHAPE");
        if (!_worldEntered || _createdCharacter is null || _worldCharacterId==0 || _worldGridUnitId==0)
            return Reject("WORLD_REQUIRED");
        if (request.ActorId!=_worldCharacterId) return Reject("ACTOR_NOT_OWNED");
        if (_baseTravelDelay is not { } delay) return Reject("TIME_POLICY_NOT_CONFIGURED");
        if (_store is not IOriginalBaseTravelStore travel) return Reject("STORAGE_REQUIRED");
        if (request.Mode!=4 || IsCurrentTacticalFieldActive) return Reject("STRATEGIC_PORT_ROUTE_REQUIRED");
        var error=await RestorePersistedGridUnitAsync(cancellationToken);
        if (error is not null) return Reject(error);
        using var lease=await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number,cancellationToken);
        if (_persistedGridUnit is not { } unit || !HasCurrentShipIncarnation) return Reject("SCENE_STALE");
        if (request.Card!=unit.AuthorityCardId) return Reject("CARD_NOT_AUTHORIZED");
        if (!ActiveBattlefieldCatalog.TryResolveBaseGrid(request.BaseId,out var grid) || grid!=unit.CurrentCellId ||
            request.BaseId==unit.BaseId ||
            !ActiveBattlefieldCatalog.HasFriendlyPublicPort(grid,request.BaseId,_createdCharacter.Value.Power))
            return Reject("DESTINATION_UNAVAILABLE");
        if (HasUnsafeBattlefield(grid,unit.UnitId,_createdCharacter.Value.Power)) return Reject("BATTLE_ACTIVE");
        var fingerprint=Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            FormattableString.Invariant($"original-base-travel/v1|{_moveGridRequestScope:N}|{sequence}|{Convert.ToHexString(payload)}"))));
        var now=_gameClock.Now;
        // Price160 from both manuals; Military pool remains INFERRED. Delay is
        // explicitly configured provisional policy, never a raw client value.
        var result=await travel.ScheduleOriginalBaseTravelAsync(_accountId,
            new(fingerprint,unit,grid,request.BaseId,now+delay,
                new(OriginalCommandPointPool.Military,160,CommandPointPolicy.Value,now,false)),cancellationToken);
        if (result.Status==OriginalBaseTravelStatus.Rejected || result.DueAt is null)
            return Reject(result.ErrorCode ?? "RESERVATION_REJECTED");
        _baseTravelRequestFingerprint=fingerprint;
        var character=(await _store.ListCharactersAsync(_accountId,cancellationToken))
            .Single(row=>row.CharacterId==_worldCharacterId);
        _worldPcp=character.Pcp; _worldMcp=character.Mcp;
        // Time schedules reception of0B00. Wait-as-remaining-ticks is an authored
        // test policy until the original history-display contradiction is resolved.
        var wait=checked((uint)Math.Ceiling(Math.Max(0,(result.DueAt.Value-_gameClock.Now).TotalSeconds)*
            OriginalGameClock.TicksPerSecond));
        return EncodeApplicationResponse(OriginalMoveBaseCodec.EncodeResponse(request with
            { Time=_gameClock.Tick,Wait=wait,Pcp=_worldPcp,Mcp=_worldMcp }),OriginalMoveBaseCodec.RequestType,true)
            with { ResponseMetadata=$"base-travel-scheduled;due={result.DueAt:O};wait-policy=provisional;status={result.Status}" };
    }
    // Invoke only from the connection actor, serialized with ProcessAsync and
    // cipher writes. The scheduler must enqueue, not call into mutable sessions.
    public async Task<IReadOnlyList<NaturalAuthorityPush>> ProjectCompletedBaseTravelAsync(Guid owner,
        OriginalBaseTravelCompletion completion, CancellationToken cancellationToken)
    {
        if (owner != _accountId || !_worldEntered || _createdCharacter is null ||
            (_baseTravelRequestFingerprint is not null &&
                completion.RequestFingerprint != _baseTravelRequestFingerprint) ||
            !completion.Updated || completion.Unit is not { } completed ||
            completed.CharacterId != _worldCharacterId || completed.UnitId != _worldGridUnitId)
            return [];
        if (completion.Outcome == "cancelled")
        {
            if (_baseTravelRequestFingerprint is null ||
                completion.RequestFingerprint != _baseTravelRequestFingerprint) return [];
            // ORIGINAL005751B0 compares event17's first ushort with request0B00.
            // This is a failure, never a0B0B movement event. Do not mutate the
            // scene when cancellation was caused by replacement or another move.
            var cancelled = EncodeApplicationPush(OriginalNotifyMessageCodec.EncodeInvalidMessage(
                OriginalMoveBaseCodec.RequestType,"艦の状態が変わったため寄港を中止しました"));
            _baseTravelRequestFingerprint = null;
            return [cancelled];
        }
        if (completion.Outcome != "completed" || completed.CurrentCellId != _worldGridCellId ||
            _persistedGridUnit is not { } previous ||
            previous.ShipGeneration != completed.ShipGeneration ||
            previous.AuthorityVersion > completed.AuthorityVersion ||
            _lastBaseTravelProjectionVersion >= completed.AuthorityVersion)
            return [];
        using var lease = await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number,cancellationToken);
        var current = await _store.FindOriginalGridUnitAsync(_accountId,_worldCharacterId,
            _worldGridUnitId,cancellationToken);
        // An event can wait in the connection queue while another command runs.
        // Never publish the old destination over a newer persisted move/incarnation.
        if (current != completed) return [];
        ApplyPersistedGridUnit(current);
        PublishOwnParticipantSnapshot();
        IReadOnlyList<NaturalAuthorityPush> pushes = [
            EncodeApplicationPush(OriginalMovedBaseCodec.Encode(new(_gameClock.Tick,_worldCharacterId,
                current.BaseId,current.Mode,CurrentPublicPortSpot(),0,[_worldCharacterId]))),
            EncodeApplicationPush(OriginalWorldEntryCodec.EncodeUnits([CurrentPlayerInformationUnit()])),
        ];
        _lastBaseTravelProjectionVersion = completed.AuthorityVersion;
        if (completion.RequestFingerprint == _baseTravelRequestFingerprint) _baseTravelRequestFingerprint = null;
        return pushes;
    }
}
