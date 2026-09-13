using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalUnitComplementTests
{
    [Fact]
    public void Temporary_single_hull_policy_separates_damage_from_destruction()
    {
        var damaged = OriginalTacticalCommandAuthority.ApplyAuthoredDamage(new(0, 0), 1);
        Assert.Equal(new OriginalTacticalDamageState(1, 0), damaged);
        var destroyed = OriginalTacticalCommandAuthority.ApplyAuthoredDamage(damaged, 1);
        Assert.Equal(new OriginalTacticalDamageState(1, 1), destroyed);
        Assert.Equal(destroyed, OriginalTacticalCommandAuthority.ApplyAuthoredDamage(destroyed, 1));
    }
    [Fact]
    public async Task Small_unit_still_targets_a_larger_unit_with_more_casualties_than_its_own_complement()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var encounter = battles.GetEncounter(101, 100);
        battles.RegisterNpc(OriginalNpcAiTests.Actor(10, 3, 0),
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities with { Number = 1 },
            OriginalAuthoredPlayableCatalog.TacticalArms);
        battles.RegisterNpc(OriginalNpcAiTests.Actor(11, 2, 3),
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
            OriginalAuthoredPlayableCatalog.TacticalArms);
        encounter.RecordUnitDamage(11, new(50, 50));
        var events = new List<OriginalNpcEvent>();
        for (uint tick = 100; tick < 250; tick += 6)
            events.AddRange(await battles.AdvanceNpcsAsync(tick, TestContext.Current.CancellationToken));
        Assert.Contains(events, e => e.Action == "fire" && e.Actor == 10 && e.Target == 11);
    }
    [Fact]
    public void Different_unit_complements_share_a_battle_without_sharing_the_death_threshold()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var encounter = battles.GetEncounter(101, 100);
        battles.RegisterNpc(OriginalNpcAiTests.Actor(10, 2, 0),
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities with { Number = 1 },
            OriginalAuthoredPlayableCatalog.TacticalArms);
        battles.RegisterNpc(OriginalNpcAiTests.Actor(11, 3, 3),
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
            OriginalAuthoredPlayableCatalog.TacticalArms);
        encounter.RecordUnitDamage(10, new(1, 1));
        encounter.RecordUnitDamage(11, new(1, 1));
        Assert.False(encounter.HasSurvivors(10));
        Assert.True(encounter.HasSurvivors(11));
        Assert.Throws<ArgumentOutOfRangeException>(() => encounter.RecordUnitDamage(10, new(2, 2)));
    }

    [Fact]
    public async Task Npc_hit_clamps_damage_to_the_target_unit_complement()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var encounter = battles.GetEncounter(101, 100);
        battles.RegisterNpc(OriginalNpcAiTests.Actor(10, 3, 0),
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
            OriginalAuthoredPlayableCatalog.TacticalArms);
        battles.RegisterNpc(OriginalNpcAiTests.Actor(11, 2, 3),
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities with { Number = 1 },
            OriginalAuthoredPlayableCatalog.TacticalArms);
        var events = new List<OriginalNpcEvent>();
        for (uint tick = 100; tick < 250; tick += 6)
            events.AddRange(await battles.AdvanceNpcsAsync(tick, TestContext.Current.CancellationToken));
        var hits = events.Where(e => e.Action == "fire" && e.Target == 11).ToArray();
        Assert.Equal(2, hits.Length);
        Assert.Equal(1, hits[0].Damaged);
        Assert.Equal(0, hits[0].Destroyed);
        Assert.Equal(1, hits[1].Damaged);
        Assert.Equal(1, hits[1].Destroyed);
        Assert.False(encounter.HasSurvivors(11));
        Assert.DoesNotContain(events, e => e.Actor == 11 && e.Action == "fire" && e.Tick >= hits[1].Tick);
    }
}
