using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalNpcAssignmentBatchTests
{
    private static OriginalTacticalBattleRegistry Registry()
    {
        var registry = new OriginalTacticalBattleRegistry();
        foreach (uint id in new uint[] { 10, 11 })
            registry.RegisterNpc(OriginalNpcAiTests.Actor(id, 3, id),
                OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
                OriginalAuthoredPlayableCatalog.TacticalArms);
        return registry;
    }

    private static OriginalNpcControlAssignment Assign(uint id, long generation = 0) =>
        new(id, 77, OriginalSystemSceneCodec.CreatePlayableTacticalCorps(77), false, generation);

    [Theory]
    [InlineData(99, 0)]
    [InlineData(11, 1)]
    [InlineData(10, 0)]
    public void Missing_stale_or_duplicate_later_member_leaves_first_unchanged(uint second, long generation)
    {
        var registry = Registry();
        var first = registry.NpcSnapshot(101, 10);
        Assert.False(registry.ApplyNpcControlAssignments(101, [Assign(10), Assign(second, generation)]));
        Assert.Same(first, registry.NpcSnapshot(101, 10));
    }

    [Fact]
    public void Invalid_later_corps_leaves_all_snapshots_unchanged()
    {
        var registry = Registry();
        var first = registry.NpcSnapshot(101, 10);
        var second = registry.NpcSnapshot(101, 11);
        Assert.Throws<ArgumentException>(() => registry.ApplyNpcControlAssignments(101,
            [Assign(10), Assign(11) with { Character = 78 }]));
        Assert.Same(first, registry.NpcSnapshot(101, 10));
        Assert.Same(second, registry.NpcSnapshot(101, 11));
    }

    [Fact]
    public void Valid_batch_updates_every_controller_without_replacing_unit_state()
    {
        var registry = Registry();
        Assert.True(registry.ApplyNpcControlAssignments(101, [Assign(10), Assign(11)]));
        foreach (uint id in new uint[] { 10, 11 })
        {
            var state = registry.NpcSnapshot(101, id)!;
            Assert.Equal(77u, state.Ship.Character);
            Assert.Equal(77u, state.Corps.Id);
            Assert.Equal((float)id, state.Ship.X);
            Assert.Equal(id, state.Unit.Id);
        }
    }
}
