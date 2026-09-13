using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalNpcAssignmentProjectionTests
{
    [Fact]
    public async Task Assigned_character_survives_refresh_while_unit_identity_and_losses_stay_intact()
    {
        var registry = new OriginalTacticalBattleRegistry();
        var original = OriginalNpcAiTests.Actor(10, 3, 0);
        var enemy = OriginalNpcAiTests.Actor(20, 2, 3);
        var caps = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities;
        using (await registry.LockAsync(101, 100, TestContext.Current.CancellationToken))
        {
            registry.RegisterNpc(original, caps, OriginalAuthoredPlayableCatalog.TacticalArms);
            registry.RegisterNpc(enemy, caps, OriginalAuthoredPlayableCatalog.TacticalArms);
            registry.GetEncounter(101, 100).RecordUnitDamage(10, new(25, 0));
            Assert.True(registry.ApplyNpcControlAssignment(101, 10, 77,
                OriginalSystemSceneCodec.CreatePlayableTacticalCorps(77), false));
            registry.RegisterNpc(original, caps, OriginalAuthoredPlayableCatalog.TacticalArms);
        }
        var assigned = registry.NpcSnapshot(101, 10)!;
        Assert.Equal(original.Unit, assigned.Unit);
        Assert.Equal(original.Ship with { Character = 77 }, assigned.Ship);
        Assert.Equal(77u, assigned.Corps.Id);
        Assert.Equal(original.ShipGeneration, assigned.ShipGeneration);
        Assert.Equal(original.Power, assigned.Power);
        Assert.Equal((ushort)25, registry.GetEncounter(101, 100).GetUnitDamage(10).Damaged);
        var events = new List<OriginalNpcEvent>();
        for (uint tick = 100; tick <= 190; tick += 6)
            events.AddRange(await registry.AdvanceNpcsAsync(tick, TestContext.Current.CancellationToken));
        Assert.DoesNotContain(events, e => e.Actor == 10);
        Assert.Contains(events, e => e.Actor == 20 && e.Target == 10 && e.Action == "fire");
        Assert.Equal(77u, registry.OtherParticipants(101, 20).Single(p => p.Unit.Id == 10).Ship.Character);
    }

    [Fact]
    public void Invalid_control_identity_cannot_partially_change_snapshot()
    {
        var registry = new OriginalTacticalBattleRegistry();
        var before = OriginalNpcAiTests.Actor(10, 3, 0);
        registry.RegisterNpc(before, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
            OriginalAuthoredPlayableCatalog.TacticalArms);
        Assert.Throws<ArgumentException>(() => registry.ApplyNpcControlAssignment(101, 10, 77,
            OriginalSystemSceneCodec.CreatePlayableTacticalCorps(78), false));
        Assert.Throws<ArgumentException>(() => registry.ApplyNpcControlAssignment(101, 10, 0,
            OriginalSystemSceneCodec.CreatePlayableTacticalCorps(0), false));
        Assert.Same(before, registry.NpcSnapshot(101, 10));
        Assert.False(registry.ApplyNpcControlAssignment(102, 10, 77,
            OriginalSystemSceneCodec.CreatePlayableTacticalCorps(77), false));
    }
}
