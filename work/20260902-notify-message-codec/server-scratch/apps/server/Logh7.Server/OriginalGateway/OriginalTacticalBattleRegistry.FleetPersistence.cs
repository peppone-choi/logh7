using System.Collections.Concurrent;
using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class OriginalTacticalBattleRegistry
{
    // Called under the grid lease after the authorized DB transaction commits.
    public void ApplyCommittedFleetRepairs(IReadOnlyList<OriginalFleetUnitRecord> before,
        IReadOnlyList<OriginalFleetUnitRecord> after)
        => PrepareFleetRepairs(before,after)();

    // Hold the SAME grid lease through preparation, DB await, and commit.
    // Discard the returned action on storage rejection; preparation is read-only.
    public Action PrepareFleetRepairs(IReadOnlyList<OriginalFleetUnitRecord> before,
        IReadOnlyList<OriginalFleetUnitRecord> after)
    {
        if (before.Count == 0 || before.Count != after.Count ||
            before.Select(row=>row.UnitId).Distinct().Count()!=before.Count)
            throw new InvalidOperationException("FLEET_REPAIR_PROJECTION_SELECTION");
        var commits=new List<Action>();
        for(var index=0;index<before.Count;index++)
        {
            var source=before[index]; var result=after[index];
            if(result != source with { Damaged=source.Destroyed, Supplies=0, Revision=checked(source.Revision+1) } ||
                !_fleetPersistence.TryGetValue(source.UnitId,out var binding) || binding.State!=source ||
                !_battles.TryGetValue(source.GridId,out var battle) ||
                !battle.Npcs.TryGetValue(source.UnitId,out var npc) ||
                npc.Snapshot.ShipGeneration!=source.Generation || !IsCurrentShipGeneration(source.UnitId,source.Generation))
                throw new InvalidOperationException("FLEET_REPAIR_PROJECTION_STALE");
            var damage=new OriginalTacticalDamageState(result.Damaged,result.Destroyed);
            battle.Encounter.ValidateUnitDamage(result.UnitId,damage);
            var apply=npc.PrepareSupplyUpdate(result.Supplies,damage);
            commits.Add(()=>{
                binding.State=result;
                battle.Encounter.RecordUnitDamage(result.UnitId,damage);
                apply();
            });
        }
        return () => { foreach(var commit in commits) commit(); };
    }

    // Caller authorizes the support command and holds the grid lease across await.
    public async Task<OriginalTacticalParticipantSnapshot?> CommitFleetSupplyAsync(
        uint grid, uint unit, long generation, CancellationToken cancellationToken)
    {
        if (!_battles.TryGetValue(grid, out var battle) ||
            !battle.Npcs.TryGetValue(unit, out var npc) ||
            !_fleetPersistence.TryGetValue(unit, out var binding)) return null;
        var before = binding.State;
        if (before.GridId != grid || before.Generation != generation ||
            npc.Snapshot.ShipGeneration != generation || !IsCurrentShipGeneration(unit, generation) ||
            !battle.Encounter.HasSurvivors(unit) || before.Supplies >= 100) return null;
        var commit = npc.PrepareSupplyUpdate(100, battle.Encounter.GetUnitDamage(unit));
        if (!await binding.Store.SupplyOrdinaryUnitAsync(before, cancellationToken)) return null;
        binding.State = before with { Supplies = 100, Revision = checked(before.Revision + 1) };
        commit();
        return npc.Snapshot;
    }

    private async Task ReconcileFleetControllersAsync(uint grid,CancellationToken cancellationToken)
    {
        foreach(var binding in _fleetPersistence.Values.Where(b=>b.State.GridId==grid &&
            b.State.ControllerCharacterId is not null).OrderBy(b=>b.State.UnitId))
        {
            var before=binding.State;
            var npc=NpcSnapshot(grid,before.UnitId)
                ?? throw new InvalidOperationException("FLEET_UNIT_RECONCILIATION_MISSING");
            if(npc.ShipGeneration!=before.Generation || !IsCurrentShipGeneration(before.UnitId,before.Generation))
                throw new InvalidOperationException("FLEET_UNIT_RECONCILIATION_CONFLICT");
            var next=await binding.Store.ReleaseUnavailableControllerAsync(before,cancellationToken);
            if(next!=before) ApplyReleasedFleetController(next,binding.Fallback);
        }
    }

    internal void ValidateFleetControllerReconciliation(OriginalFleetUnitRecord row)
    {
        if(_fleetPersistence.TryGetValue(row.UnitId,out var binding) && binding.State!=row)
            throw new InvalidOperationException("FLEET_UNIT_RECONCILIATION_CONFLICT");
    }

    internal void ApplyReleasedFleetController(OriginalFleetUnitRecord row,OriginalTacticalParticipantSnapshot authored)
    {
        if(!_fleetPersistence.TryGetValue(row.UnitId,out var binding)) return;
        if(row.Revision!=binding.State.Revision+1 || row.ControllerCharacterId is not null ||
            !row.Autonomous || !IsCurrentShipGeneration(row.UnitId,row.Generation))
            throw new InvalidOperationException("FLEET_UNIT_RECONCILIATION_CONFLICT");
        if(!ApplyNpcControlAssignment(row.GridId,row.UnitId,authored.Ship.Character,authored.Corps,true))
            throw new InvalidOperationException("FLEET_UNIT_RECONCILIATION_MISSING");
        binding.State=row;
    }

    // Caller holds the grid lease for the whole await and has authorized the
    // command. Prepare memory before DB commit; never publish on a failed save.
    public async Task<bool> CommitFleetControlAssignmentsAsync(uint grid,IReadOnlyList<OriginalNpcControlAssignment> assignments,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(assignments);
        if (assignments.Count==0 || !_battles.TryGetValue(grid,out var battle)) return false;
        var seen=new HashSet<uint>();
        var commits=new List<Action>();
        var writes=new List<OriginalFleetControlWrite>();
        var nextBindings=new List<(FleetPersistence Binding,OriginalFleetUnitRecord Next)>();
        PostgresFleetUnitStore? store=null;
        foreach(var assignment in assignments)
        {
            if (!seen.Add(assignment.Unit) || !battle.Npcs.TryGetValue(assignment.Unit,out var npc) ||
                !_fleetPersistence.TryGetValue(assignment.Unit,out var binding)) return false;
            var row=binding.State;
            if (row.GridId!=grid || row.Generation!=assignment.ExpectedGeneration ||
                npc.Snapshot.ShipGeneration!=row.Generation || npc.Snapshot.Unit.Outfit!=row.OutfitId ||
                !IsCurrentShipGeneration(assignment.Unit,row.Generation)) return false;
            store ??= binding.Store;
            if (!store.SharesDataSource(binding.Store)) return false;
            commits.Add(npc.PrepareControlAssignment(assignment.Character,assignment.Corps,assignment.Autonomous));
            writes.Add(new(row.UnitId,grid,row.OutfitId,row.Generation,row.Revision,assignment.Character,assignment.Autonomous,
                assignment.Corps,assignment.ControllerUnitId,assignment.ControllerGeneration));
            nextBindings.Add((binding,row with { ControllerCharacterId=assignment.Character,
                ControllerUnitId=assignment.ControllerUnitId,ControllerGeneration=assignment.ControllerGeneration,
                Autonomous=assignment.Autonomous,Revision=checked(row.Revision+1) }));
        }
        if (!await store!.SaveControlAssignmentsAsync(writes,cancellationToken)) return false;
        // No awaits/cancellation checks after durable success: finish the
        // already-prepared memory transition while still holding the lease.
        foreach(var (binding,next) in nextBindings) binding.State=next;
        foreach(var commit in commits) commit();
        return true;
    }
    private sealed class FleetPersistence(OriginalFleetUnitRecord state, PostgresFleetUnitStore store,
        OriginalTacticalParticipantSnapshot fallback)
    {
        public OriginalFleetUnitRecord State { get; set; } = state;
        public PostgresFleetUnitStore Store { get; } = store;
        public OriginalTacticalParticipantSnapshot Fallback { get; } = fallback;
    }
    private readonly ConcurrentDictionary<uint,FleetPersistence> _fleetPersistence = new();

    // Called after initial restore, under grid lease. Refresh cannot replace a
    // live binding's revision with the older row it happened to read.
    public void BindFleetUnitPersistence(OriginalFleetUnitRecord row, PostgresFleetUnitStore store,
        OriginalTacticalParticipantSnapshot? fallback=null)
    {
        var npc = NpcSnapshot(row.GridId,row.UnitId);
        if (npc is null || npc.ShipGeneration != row.Generation || !IsCurrentShipGeneration(row.UnitId,row.Generation))
            throw new InvalidOperationException("FLEET_UNIT_BINDING_STALE");
        if(fallback is null && row.ControllerCharacterId is not null)
            throw new InvalidOperationException("FLEET_FALLBACK_REQUIRED");
        fallback ??= npc;
        if(fallback.Unit.Id!=row.UnitId || fallback.Unit.Outfit!=row.OutfitId ||
            fallback.Power!=row.Power || fallback.Camp!=row.Camp)
            throw new InvalidOperationException("FLEET_FALLBACK_CONFLICT");
        _fleetPersistence.TryAdd(row.UnitId,new(row,store,fallback));
    }

    private async Task PersistFleetPoseAsync(uint grid,uint unit,long generation,
        OriginalTacticalUnitShipRecord pose,CancellationToken cancellationToken)
    {
        if (!_fleetPersistence.TryGetValue(unit,out var binding)) return;
        var before=binding.State;
        if (before.GridId!=grid || before.Generation!=generation || pose.Id!=unit ||
            !IsCurrentShipGeneration(unit,generation))
            throw new InvalidOperationException("FLEET_UNIT_BINDING_STALE");
        var next=before with { X=pose.X,Y=pose.Y,Z=pose.Z,Direction=pose.Direction };
        if (!await binding.Store.SaveAsync(next,cancellationToken))
            throw new InvalidOperationException("FLEET_UNIT_SAVE_CONFLICT");
        binding.State=next with { Revision=checked(before.Revision+1) };
    }

    private async Task PersistFleetDamageAsync(uint grid,uint unit,long generation,
        OriginalTacticalDamageState damage,CancellationToken cancellationToken)
    {
        if (!_fleetPersistence.TryGetValue(unit,out var binding)) return;
        var before=binding.State;
        var npc=NpcSnapshot(grid,unit);
        if (before.GridId!=grid || before.Generation!=generation || npc is null || npc.ShipGeneration!=generation)
            throw new InvalidOperationException("FLEET_UNIT_BINDING_STALE");
        var next=before with { Damaged=damage.Damaged,Destroyed=damage.Destroyed,
            X=npc.Ship.X,Y=npc.Ship.Y,Z=npc.Ship.Z,Direction=npc.Ship.Direction };
        if (!await binding.Store.SaveAsync(next,cancellationToken))
            throw new InvalidOperationException("FLEET_UNIT_SAVE_CONFLICT");
        binding.State=next with { Revision=checked(before.Revision+1) };
    }
}
