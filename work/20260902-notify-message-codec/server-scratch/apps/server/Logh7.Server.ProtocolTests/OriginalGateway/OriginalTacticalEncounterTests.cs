using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalTacticalEncounterTests
{
    [Theory]
    [InlineData(0, 0)]
    [InlineData(25, 0)]
    [InlineData(100, 99)]
    [InlineData(100, 0)]
    public void Does_not_end_while_any_enemy_survives(ushort damaged, ushort destroyed)
    {
        var encounter = new OriginalTacticalEncounter(100);
        encounter.RecordEnemyDamage(new(damaged, destroyed));
        Assert.Empty(encounter.TryComplete(101, 2, 0, [new(1, 2, 0, 101)]));
        Assert.Equal("000000000317006501", Convert.ToHexString(encounter.EncodeGridState(101, 101)));
    }

    [Theory]
    [InlineData(1, 0)]
    [InlineData(3, 0)]
    [InlineData(2, 1)]
    public void Unowned_enemy_or_other_camp_base_blocks_completion(byte power, byte camp)
    {
        var encounter = new OriginalTacticalEncounter(100);
        encounter.RecordEnemyDamage(new(100, 100));
        Assert.Empty(encounter.TryComplete(101, 2, 0, [new(1, 2, 0, 101), new(2, power, camp, 101)]));
        Assert.False(encounter.IsCompleted);
    }

    [Fact]
    public void Missing_objective_data_does_not_mean_all_objectives_captured()
    {
        var encounter = new OriginalTacticalEncounter(100);
        encounter.RecordEnemyDamage(new(100, 100));
        Assert.Empty(encounter.TryComplete(101, 2, 0, null));
        Assert.False(encounter.IsCompleted);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void Completed_encounter_emits_matching_grid_and_tactics_state_once(bool hasBase)
    {
        var encounter = new OriginalTacticalEncounter(100);
        encounter.RecordEnemyDamage(new(100, 100));
        OriginalInformationBaseRecord[] bases = hasBase ? [new(1, 2, 0, 101), new(2, 3, 0, 102)] : [];
        var frames = encounter.TryComplete(101, 2, 0, bases);
        Assert.Equal(new[] { "000000000317006500", "000000000F1F0000000065" },
            frames.Select(frame => Convert.ToHexString(frame)).ToArray());
        Assert.True(encounter.IsCompleted);
        Assert.Empty(encounter.TryComplete(101, 2, 0, bases));
        Assert.Equal("000000000317006500", Convert.ToHexString(encounter.EncodeGridState(101, 101)));
    }

    [Fact]
    public void Other_grid_is_not_marked_as_this_encounter()
    {
        var encounter = new OriginalTacticalEncounter(100);
        Assert.Equal("000000000317006600", Convert.ToHexString(encounter.EncodeGridState(102, 101)));
    }

    [Fact]
    public void Destroyed_enemy_is_excluded_from_refresh_but_retained_for_final_damage_projection()
    {
        var encounter = new OriginalTacticalEncounter(100);
        var player = OriginalSystemSceneCodec.CreateAuthoredTacticalUnitShip(2, 2);
        var enemy = OriginalSystemSceneCodec.CreateAuthoredTacticalUnitShip(99, 99);
        encounter.RecordEnemyDamage(new(100, 100));
        var refresh = OriginalSystemSceneCodec.EncodeTacticalUnitShips(new(encounter.ProjectParticipants(player, enemy)));
        var finalHit = OriginalSystemSceneCodec.EncodeTacticalUnitShips(new(encounter.ProjectParticipants(player, enemy, true)));
        Assert.Equal("00000000033B000100000002", Convert.ToHexString(refresh[..12]));
        Assert.Equal(55, refresh.Length);
        Assert.Equal("00000000033B0002", Convert.ToHexString(finalHit[..8]));
        Assert.Equal(102, finalHit.Length);
    }

    [Fact]
    public void Enemy_number_is_a_template_count_not_a_fixed_one_hundred_threshold()
    {
        var encounter = new OriginalTacticalEncounter(200);
        encounter.RecordEnemyDamage(new(100, 100));
        Assert.True(encounter.EnemyHasSurvivors);
        Assert.Empty(encounter.TryComplete(101, 2, 0, []));
    }

    [Theory]
    [InlineData(101, 100)]
    [InlineData(0, 1)]
    [InlineData(100, 101)]
    public void Rejects_impossible_cumulative_casualties(ushort damaged, ushort destroyed)
    {
        var encounter = new OriginalTacticalEncounter(100);
        Assert.Throws<ArgumentOutOfRangeException>(() => encounter.RecordEnemyDamage(new(damaged, destroyed)));
    }
}
