namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalNpcEvent(uint Tick, uint Grid, uint Actor, uint Target, string Action,
    float X, float Y, float Direction, ushort Damaged = 0, ushort Destroyed = 0, byte? Arms = null);

public sealed partial class OriginalTacticalBattleRegistry
{
    // All-or-none relative to other commands/AI holding this grid lease.
    // Does not provide database atomicity or permission checks for its caller.
    public bool ApplyNpcControlAssignments(uint grid, IReadOnlyList<OriginalNpcControlAssignment> assignments)
    {
        ArgumentNullException.ThrowIfNull(assignments);
        if (!_battles.TryGetValue(grid, out var battle)) return false;
        var units = new HashSet<uint>();
        var commits = new List<Action>(assignments.Count);
        foreach (var assignment in assignments)
        {
            if (!units.Add(assignment.Unit) || !battle.Npcs.TryGetValue(assignment.Unit, out var npc) ||
                npc.Snapshot.ShipGeneration != assignment.ExpectedGeneration ||
                !IsCurrentShipGeneration(assignment.Unit, assignment.ExpectedGeneration))
                return false;
            commits.Add(npc.PrepareControlAssignment(assignment.Character, assignment.Corps, assignment.Autonomous));
        }
        foreach (var commit in commits) commit();
        return true;
    }
    // Caller holds grid lease and has already authorized/persisted assignment.
    // This is not a network command handler: it applies the coherent local
    // projection and simulation mode without removing a participant from battle.
    public bool ApplyNpcControlAssignment(uint grid, uint unit, uint character,
        OriginalTacticalCorpsRecord corps, bool autonomous)
    {
        if (!_battles.TryGetValue(grid, out var battle) || !battle.Npcs.TryGetValue(unit, out var npc))
            return false;
        npc.ApplyControlAssignment(character, corps, autonomous);
        return true;
    }

    public void RegisterNpc(OriginalTacticalParticipantSnapshot snapshot,
        OriginalStaticUnitShipCapabilities capabilities, OriginalStaticArmsTable arms,
        IReadOnlyList<OriginalInformationBaseRecord>? objectives = null, bool defensive = false)
    {
        // Caller owns the grid command lease. A scene refresh never respawns NPCs.
        var battle = _battles.TryGetValue(snapshot.Unit.Grid, out var existing) ? existing :
            Resolve(snapshot.Unit.Grid, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number);
        battle.Encounter.RegisterUnitNumber(snapshot.Unit.Id, capabilities.Number);
        if (battle.Npcs.TryAdd(snapshot.Unit.Id, new(snapshot, capabilities, arms)) && defensive)
            battle.Npcs[snapshot.Unit.Id].SetDefensivePosture(true);
        if (objectives is not null) battle.Objectives ??= objectives.ToArray();
    }

    public OriginalTacticalParticipantSnapshot? NpcSnapshot(uint grid, uint unit) =>
        _battles.TryGetValue(grid, out var battle) && battle.Npcs.TryGetValue(unit, out var npc) ? npc.Snapshot : null;

    // Internal scene refresh, not a command or a grant of control.
    internal bool RefreshNpcCharacter(uint grid,OriginalTacticalParticipantSnapshot restored) =>
        _battles.TryGetValue(grid,out var battle) && battle.Npcs.TryGetValue(restored.Unit.Id,out var npc) &&
        IsCurrentShipGeneration(restored.Unit.Id,restored.ShipGeneration) && npc.RefreshCharacter(restored);

    public async Task<IReadOnlyList<OriginalNpcEvent>> AdvanceNpcsAsync(uint tick, CancellationToken cancellationToken)
    {
        var events = new List<OriginalNpcEvent>();
        foreach (var (grid, battle) in _battles.OrderBy(pair => pair.Key))
        {
            if (battle.Npcs.IsEmpty) continue;
            using var lease = await LockAsync(grid, battle.Encounter.EnemyNumber, cancellationToken);
            await ReconcileFleetControllersAsync(grid,cancellationToken);
            var encounter = battle.Encounter;
            if (encounter.IsCompleted) continue;
            NotifyCombatStarted(grid, encounter.EnemyNumber);
            foreach (var (id, npc) in battle.Npcs.OrderBy(pair => pair.Key))
            {
                if (!encounter.HasSurvivors(id)) continue;
                // Support operations occupy the performing ship. Keep its pose,
                // targeting and weapons unchanged until that incarnation's lock ends.
                if (IsExecuting(id, npc.Snapshot.ShipGeneration, tick)) continue;
                // Fresh per actor: a target killed earlier in this tick cannot retaliate.
                var candidates = QueryParticipants(grid, id, npcTargetsOnly: true)
                    .Concat(battle.Npcs.Values.Select(n => n.Snapshot))
                    .DistinctBy(p => p.Unit.Id).Select(p => Project(p, encounter)).ToArray();
                var before = npc.Snapshot;
                var pending = npc.PrepareAdvance(tick, candidates, encounter.UnitNumber);
                var step = pending.Step;
                if (step.Ship != before.Ship)
                    await PersistFleetPoseAsync(grid,id,before.ShipGeneration,step.Ship,cancellationToken);
                pending.Commit();
                if (step.Ship != before.Ship)
                {
                    var command = new OriginalTacticalMoveShipCommand(tick, 0, 2,
                        [new(id, before.Ship.Direction, before.Ship.X, before.Ship.Y, before.Ship.Z)],
                        npc.MovementVelocity, step.Ship.Direction,
                        [new(step.Ship.X, step.Ship.Y, step.Ship.Z)]);
                    Publish(grid, encounter.EnemyNumber, null,
                    [
                        OriginalTacticalCommandCodec.EncodeMoveShipCommand(command),
                        OriginalSystemSceneCodec.EncodeTacticalUnitShips(new([step.Ship])),
                        OriginalTacticalCommandCodec.EncodeMovedShipNotification(new(tick, id, step.Ship.Direction,
                            step.Ship.X, step.Ship.Y, step.Ship.Z, 1)),
                    ], id, Project(before, encounter).EncodeEntry());
                    events.Add(new(tick, grid, id, step.TargetId, "move", step.Ship.X, step.Ship.Y, step.Ship.Direction));
                }
                if (step.Arms is not byte arms) continue;
                var target = candidates.FirstOrDefault(p => p.Unit.Id == step.TargetId);
                if (target is null || !encounter.HasSurvivors(target.Unit.Id)) continue;
                var damage = OriginalTacticalCommandAuthority.ApplyAuthoredDamage(encounter.GetUnitDamage(target.Unit.Id),
                    encounter.UnitNumber(target.Unit.Id));
                var attacked = OriginalTacticalCommandAuthority.CreateDamageNotification(tick, id, arms, 1,
                    target.Unit.Id, damage, target.Ship.Morale);
                await CommitUnitDamageAsync(grid,encounter.EnemyNumber,target.Unit.Id,damage,cancellationToken,
                    target.ShipGeneration);
                pending.CommitFire();
                // Target entry is pre-hit; 0426 applies the cumulative casualty exactly once.
                // Loading players remain world participants and may still
                // prevent victory; target eligibility is not their existence.
                var completion = !OtherParticipants(grid, id).Concat(battle.Npcs.Values.Select(n => n.Snapshot))
                    .Any(p => p.IsHostileTo(before.Power,before.Camp) && encounter.HasSurvivors(p.Unit.Id))
                    ? encounter.TryComplete(grid, before.Power, before.Camp, battle.Objectives,
                        npcIsHostile: battle.Npcs.TryGetValue(OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId,
                            out var primary) && primary.Snapshot.IsHostileTo(before.Power,before.Camp))
                    : Array.Empty<byte[]>();
                Publish(grid, encounter.EnemyNumber, null,
                    new[] { OriginalTacticalCommandCodec.EncodeAttackedNotification(attacked) }.Concat(completion).ToArray(),
                    id, Project(npc.Snapshot, encounter).EncodeEntry(), target.Unit.Id, target.EncodeEntry());
                events.Add(new(tick, grid, id, target.Unit.Id, "fire", step.Ship.X, step.Ship.Y,
                    step.Ship.Direction, damage.Damaged, damage.Destroyed, arms));
                if (encounter.IsCompleted) break;
            }
        }
        return events;
    }

    private static OriginalTacticalParticipantSnapshot Project(OriginalTacticalParticipantSnapshot snapshot,
        OriginalTacticalEncounter encounter) => new(encounter.ProjectUnit(snapshot.Unit), snapshot.Ship,
            snapshot.Corps, snapshot.CharacterFrame.ToArray(), snapshot.Power,snapshot.ShipGeneration, snapshot.Outfit,
            snapshot.CommanderMerit);
}

public readonly record struct OriginalNpcControlAssignment(uint Unit, uint Character,
    OriginalTacticalCorpsRecord Corps, bool Autonomous, long ExpectedGeneration,
    uint? ControllerUnitId=null,long? ControllerGeneration=null);
