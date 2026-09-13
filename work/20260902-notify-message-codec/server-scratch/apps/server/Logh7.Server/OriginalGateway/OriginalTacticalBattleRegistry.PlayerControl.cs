using System.Collections.Concurrent;

namespace Logh7.Server.OriginalGateway;

public sealed partial class OriginalTacticalBattleRegistry
{
    // Prepare before awaiting persistence; apply under the same grid lease.
    // Changing distribution must not re-enable AI or clear current orders.
    internal Action PrepareControlledCorpsUpdate(uint grid,OriginalTacticalCorpsRecord corps)
    {
        var commits=_battles.TryGetValue(grid,out var battle)
            ? battle.Npcs.Values.Where(npc=>npc.Snapshot.Ship.Character==corps.Id)
                .Select(npc=>npc.PrepareCorpsUpdate(corps)).ToArray()
            : Array.Empty<Action>();
        return () => { foreach(var commit in commits) commit(); };
    }

    // Session snapshots must not roll back an accepted distribution. This is
    // process-owned, incarnation-scoped state, not cross-restart persistence.
    private readonly ConcurrentDictionary<uint, (long Generation, OriginalTacticalCorpsRecord Corps)> _playerControls = new();

    internal OriginalTacticalCorpsRecord? PlayerControl(uint unit, long generation) =>
        _playerControls.TryGetValue(unit, out var value) && value.Generation == generation
            ? value.Corps : null;

    internal void RecordPlayerControl(uint unit, long generation, OriginalTacticalCorpsRecord corps) =>
        _playerControls.AddOrUpdate(unit, (generation, corps),
            (_, current) => current.Generation > generation ? current : (generation, corps));
}
