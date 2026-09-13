using System.Collections.Concurrent;
using System.Threading.Channels;

namespace Logh7.Server.OriginalGateway;

// Process-owned casualties/participants for the authored encounter at each grid.
// Player positions, campaign persistence and movement reservations are not here yet.
public sealed partial class OriginalTacticalBattleRegistry
{
    private sealed class Observer(uint ownUnit, Guid subscriptionId, bool primaryNpcKnown, bool tacticalActive, bool participatesInCombat)
    {
        public uint OwnUnit { get; } = ownUnit;
        public long OwnGeneration { get; set; }
        public Guid SubscriptionId { get; } = subscriptionId;
        public bool TacticalActive { get; set; } = tacticalActive;
        public bool ParticipatesInCombat { get; } = participatesInCombat;
        // Accessed only by Publish under this battle's command lease.
        public Dictionary<uint,long> KnownUnits { get; } = primaryNpcKnown
            ? new() { [ownUnit]=0,[OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId]=0 }
            : new() { [ownUnit]=0 };
    }

    private sealed class Battle(ushort number)
    {
        public OriginalTacticalEncounter Encounter { get; } = new(number);
        public SemaphoreSlim Commands { get; } = new(1, 1);
        public ConcurrentDictionary<ChannelWriter<OriginalTacticalNotificationBatch>, Observer> Observers { get; } = new();
        public ConcurrentDictionary<uint, OriginalTacticalNpcController> Npcs { get; } = new();
        public IReadOnlyList<OriginalInformationBaseRecord>? Objectives { get; set; }
    }

    private readonly ConcurrentDictionary<uint, Battle> _battles = new();
    private sealed record Presence(long Revision, OriginalTacticalParticipantSnapshot Snapshot, bool NpcTargetReady,
        Func<OriginalTacticalDamageState,CancellationToken,Task>? PersistDamage);
    private readonly ConcurrentDictionary<ChannelWriter<OriginalTacticalNotificationBatch>, Presence> _participants = new();
    private long _participantRevision;
    private readonly ConcurrentDictionary<uint,long> _shipGenerations = new();
    // Transport removal must not erase the durable owner of an accepted hit.
    // One latest binding per unit; this is not an offline simulation roster.
    private readonly ConcurrentDictionary<uint,Presence> _damageOwners = new();

    public bool ObserveShipGeneration(uint unit, long generation)
    {
        if (unit == 0 || generation < 0) throw new ArgumentOutOfRangeException(nameof(generation));
        return _shipGenerations.AddOrUpdate(unit,generation,(_,current)=>Math.Max(current,generation)) == generation;
    }

    public bool IsCurrentShipGeneration(uint unit, long generation) =>
        _shipGenerations.GetValueOrDefault(unit) == generation;

    public void UpdateParticipant(ChannelWriter<OriginalTacticalNotificationBatch> owner,
        OriginalTacticalParticipantSnapshot snapshot, bool npcTargetReady = true,
        Func<OriginalTacticalDamageState,CancellationToken,Task>? persistDamage = null)
    {
        if (ObserveShipGeneration(snapshot.Unit.Id,snapshot.ShipGeneration))
        {
            var presence=new Presence(Interlocked.Increment(ref _participantRevision),snapshot,npcTargetReady,persistDamage);
            if(persistDamage is not null)
                _damageOwners.AddOrUpdate(snapshot.Unit.Id,presence,(_,old)=>
                    old.Snapshot.ShipGeneration>snapshot.ShipGeneration || old.Revision>presence.Revision ? old : presence);
            _participants[owner]=presence;
        }
    }

    public void RemoveParticipant(ChannelWriter<OriginalTacticalNotificationBatch> owner) =>
        _participants.TryRemove(owner, out _);

    public IReadOnlyList<OriginalTacticalParticipantSnapshot> OtherParticipants(uint grid, uint ownUnit) =>
        QueryParticipants(grid, ownUnit, npcTargetsOnly: false);

    private IReadOnlyList<OriginalTacticalParticipantSnapshot> QueryParticipants(uint grid, uint ownUnit,
        bool npcTargetsOnly) =>
        _participants.Values.Where(p => p.Snapshot.Unit.Grid == grid && p.Snapshot.Unit.Id != ownUnit &&
                IsCurrentShipGeneration(p.Snapshot.Unit.Id,p.Snapshot.ShipGeneration))
            // Existing sessions may represent the same owned unit. Project its
            // latest snapshot once; closing an older owner cannot erase another.
            .GroupBy(p => p.Snapshot.Unit.Id)
            // A second loading connection must not shield a unit whose first
            // connection has already imported the scene. Keep its latest pose.
            .Where(group => !npcTargetsOnly || group.Any(p => p.NpcTargetReady))
            .Select(group => group.MaxBy(p => p.Revision)!.Snapshot)
            // The primary NPC already has explicit scene fields. Additional
            // NPCs use the ordinary roster/import path.
            .Concat(_battles.TryGetValue(grid, out var battle)
                ? battle.Npcs.Values.Select(n => n.Snapshot).Where(p => p.Unit.Id != ownUnit &&
                    p.Unit.Id != OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId)
                : [])
            .DistinctBy(p => p.Unit.Id)
            .OrderBy(p => p.Unit.Id).ToArray();

    // Called while the scene builder holds this grid's command lease, after
    // the corresponding participant frames have been encoded in its response.
    public void MarkProjectedParticipants(uint grid, ushort number,
        ChannelWriter<OriginalTacticalNotificationBatch> observer, Guid subscriptionId, IEnumerable<uint> units)
    {
        if (Resolve(grid, number).Observers.TryGetValue(observer, out var state) &&
            state.SubscriptionId == subscriptionId)
            foreach (var unit in units) state.KnownUnits[unit]=_shipGenerations.GetValueOrDefault(unit);
    }

    private Battle Resolve(uint grid, ushort number)
    {
        var battle = _battles.GetOrAdd(grid, _ => new(number));
        if (battle.Encounter.EnemyNumber != number)
            throw new InvalidOperationException("TACTICAL_BATTLE_TEMPLATE_CHANGED");
        return battle;
    }

    public OriginalTacticalEncounter GetEncounter(uint grid, ushort number) => Resolve(grid, number).Encounter;

    // Caller holds the grid command lease. Commit before changing the shared
    // casualty or publishing0426; never await another session's network writer.
    public async Task CommitUnitDamageAsync(uint grid,ushort number,uint unit,
        OriginalTacticalDamageState damage,CancellationToken cancellationToken,long? expectedGeneration=null)
    {
        // Caller holds the grid lease. Validate before any durable side effect,
        // not only when publishing the in-memory casualty after persistence.
        var encounter = Resolve(grid,number).Encounter;
        encounter.ValidateUnitDamage(unit,damage);
        var generation=expectedGeneration ?? _shipGenerations.GetValueOrDefault(unit);
        if(!IsCurrentShipGeneration(unit,generation))
            throw new InvalidOperationException("DAMAGE_TARGET_GENERATION_STALE");
        if(_damageOwners.TryGetValue(unit,out var owner))
        {
            if(owner.Snapshot.Unit.Grid!=grid || owner.Snapshot.ShipGeneration!=generation)
                throw new InvalidOperationException("DAMAGE_TARGET_LOCATION_STALE");
            await owner.PersistDamage!(damage,cancellationToken);
        }
        else await PersistFleetDamageAsync(grid,unit,generation,damage,cancellationToken);
        if(!IsCurrentShipGeneration(unit,generation))
            throw new InvalidOperationException("DAMAGE_TARGET_GENERATION_STALE");
        // Legacy NPCs/in-memory fixtures may be unbound. Persisted ordinary
        // fleet units and player owners commit storage before changing memory.
        encounter.RecordUnitDamage(unit,damage);
        // A committed live hit ends an authored defensive outfit's hold. Scene
        // restores use RecordUnitDamage directly and never reach this path.
        if(_battles.TryGetValue(grid,out var hit) && hit.Npcs.TryGetValue(unit,out var struck))
            struck.Provoke();
    }

    public async Task<IDisposable> LockAsync(uint grid, ushort number, CancellationToken cancellationToken)
    {
        var battle = Resolve(grid, number);
        await battle.Commands.WaitAsync(cancellationToken);
        return new Lease(() => battle.Commands.Release());
    }

    public IDisposable Subscribe(uint grid, ushort number, ChannelWriter<OriginalTacticalNotificationBatch> observer,
        uint ownUnit = 0, Guid subscriptionId = default, bool primaryNpcKnown = true, bool tacticalActive = true,
        bool participatesInCombat = true)
    {
        var battle = Resolve(grid, number);
        var state=new Observer(ownUnit, subscriptionId, primaryNpcKnown, tacticalActive, participatesInCombat);
        state.OwnGeneration=_shipGenerations.GetValueOrDefault(ownUnit);
        state.KnownUnits[ownUnit]=_shipGenerations.GetValueOrDefault(ownUnit);
        battle.Observers.TryAdd(observer,state);
        return new Lease(() => battle.Observers.TryRemove(observer, out _));
    }

    // Caller holds the grid lease. Start only a real hostile encounter, once per
    // imported scene; the importing origin receives its state in the response.
    public void NotifyCombatStarted(uint grid, ushort number,
        ChannelWriter<OriginalTacticalNotificationBatch>? importingOrigin = null)
    {
        var battle = Resolve(grid, number);
        if (battle.Encounter.IsCompleted) return;
        var powers = OtherParticipants(grid, 0)
            .Concat(battle.Npcs.Values.Select(n => n.Snapshot))
            .Where(p => battle.Encounter.HasSurvivors(p.Unit.Id))
            .Select(p => (p.Power,p.Camp)).Distinct().Take(2).Count();
        if (powers < 2) return;
        var frames = new[]
        {
            OriginalWorldBootstrapCodec.EncodeInformationGrid(checked((ushort)grid), 1),
            OriginalTacticalCommandCodec.EncodeNotifyTactics(1, grid),
        };
        foreach (var (observer, state) in battle.Observers)
        {
            if (!state.ParticipatesInCombat || state.TacticalActive ||
                !IsCurrentShipGeneration(state.OwnUnit,state.OwnGeneration)) continue;
            if (ReferenceEquals(observer, importingOrigin) ||
                observer.TryWrite(new(grid, state.SubscriptionId, frames)))
            {
                state.TacticalActive = true;
                continue;
            }
            observer.TryComplete(new IOException("TACTICAL_OBSERVER_BACKPRESSURE"));
            battle.Observers.TryRemove(observer, out _);
        }
    }

    // Caller holds this battle's command lease: mutation and event enqueue order
    // are the same for every observer. Never await a slow peer while holding it.
    /// <param name="onlyUnit">
    /// When non-zero, only the observer commanding that unit receives the
    /// frames. A suggestion is addressed to one fleet, not to the whole grid;
    /// everything else in this authority is a genuine broadcast and passes 0.
    /// </param>
    public void Publish(uint grid, ushort number, ChannelWriter<OriginalTacticalNotificationBatch>? origin,
        IReadOnlyList<byte[]> applicationFrames, uint actorUnit = 0, IReadOnlyList<byte[]>? actorEntry = null,
        uint targetUnit = 0, IReadOnlyList<byte[]>? targetEntry = null, uint onlyUnit = 0,
        bool tacticalOnly = false)
    {
        var battle = Resolve(grid, number);
        foreach (var (observer, state) in battle.Observers)
        {
            if (!state.ParticipatesInCombat || (tacticalOnly && !state.TacticalActive) || ReferenceEquals(observer, origin) ||
                (onlyUnit != 0 && state.OwnUnit != onlyUnit) ||
                !IsCurrentShipGeneration(state.OwnUnit,state.OwnGeneration)) continue;
            var actorGeneration=_shipGenerations.GetValueOrDefault(actorUnit);
            var targetGeneration=_shipGenerations.GetValueOrDefault(targetUnit);
            var needsActor = actorUnit != 0 && (!state.KnownUnits.TryGetValue(actorUnit,out var knownActor) ||
                knownActor != actorGeneration);
            var needsTarget = targetUnit != 0 && targetUnit != actorUnit &&
                (!state.KnownUnits.TryGetValue(targetUnit,out var knownTarget) || knownTarget != targetGeneration);
            if ((needsActor && actorEntry is null) || (needsTarget && targetEntry is null))
            {
                // Native 0426 needs both combatants imported before the hit.
                observer.TryComplete(new IOException("TACTICAL_PARTICIPANT_UNAVAILABLE"));
                battle.Observers.TryRemove(observer, out _);
                continue;
            }
            IEnumerable<byte[]> frames = applicationFrames;
            if (needsTarget) frames = targetEntry!.Concat(frames);
            if (needsActor) frames = actorEntry!.Concat(frames);
            if (observer.TryWrite(new(grid, state.SubscriptionId, frames)))
            {
                if (needsActor) state.KnownUnits[actorUnit]=actorGeneration;
                if (needsTarget) state.KnownUnits[targetUnit]=targetGeneration;
                continue;
            }
            // Queue admission is all-or-none for an entire native import/event.
            observer.TryComplete(new IOException("TACTICAL_OBSERVER_BACKPRESSURE"));
            battle.Observers.TryRemove(observer, out _);
        }
    }

    private sealed class Lease(Action release) : IDisposable
    {
        private Action? _release = release;
        public void Dispose() => Interlocked.Exchange(ref _release, null)?.Invoke();
    }
}
