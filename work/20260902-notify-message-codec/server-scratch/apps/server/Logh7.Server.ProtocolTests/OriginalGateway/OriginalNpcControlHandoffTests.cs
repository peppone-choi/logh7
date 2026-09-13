using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalNpcControlHandoffTests
{
    private static OriginalTacticalNpcController Controller() => new(
        OriginalNpcAiTests.Actor(10, 3, 0),
        OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
        OriginalAuthoredPlayableCatalog.TacticalArms);

    [Fact]
    public void Suspended_ai_cannot_move_acquire_or_fire()
    {
        var npc = Controller();
        var enemy = OriginalNpcAiTests.Actor(2, 2, 3);
        npc.Advance(100, [enemy]);
        var before = npc.Snapshot;
        npc.SetAutonomousControl(false);
        foreach (var tick in new uint[] { 106, 172, 10000 })
        {
            var step = npc.Advance(tick, [enemy]);
            Assert.Equal(before.Ship, step.Ship);
            Assert.Equal(0u, step.TargetId);
            Assert.Null(step.Arms);
            Assert.Same(before, npc.Snapshot);
        }
    }

    [Fact]
    public void Resuming_ai_reacquires_without_saved_shot_or_catchup_motion()
    {
        var npc = Controller();
        var enemy = OriginalNpcAiTests.Actor(2, 2, 3);
        npc.Advance(100, [enemy]);
        npc.SetAutonomousControl(false);
        npc.SetAutonomousControl(true);
        var before = npc.Snapshot.Ship;
        var first = npc.Advance(10000, [enemy]);
        Assert.Null(first.Arms);
        Assert.Equal(before, first.Ship);
        Assert.Equal(2u, first.TargetId);
        Assert.Null(npc.Advance(10071, [enemy]).Arms);
        Assert.NotNull(npc.Advance(10072, [enemy]).Arms);
    }

    [Fact]
    public void Repeated_autonomous_setting_does_not_restart_weapon_delay()
    {
        var npc = Controller();
        var enemy = OriginalNpcAiTests.Actor(2, 2, 3);
        npc.Advance(100, [enemy]);
        npc.SetAutonomousControl(true);
        Assert.NotNull(npc.Advance(172, [enemy]).Arms);
    }
}
