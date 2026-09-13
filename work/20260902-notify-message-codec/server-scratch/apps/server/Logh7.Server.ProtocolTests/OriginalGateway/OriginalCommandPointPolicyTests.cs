using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalCommandPointPolicyTests
{
    private static OriginalCommandPointPolicy Deployed() => OriginalCommandPointPolicy.LoadDefault();

    [Fact]
    public void Deployed_policy_carries_the_approved_authored_values()
    {
        var policy = Deployed();
        Assert.Equal("NEW_DESIGN", policy.EvidenceStatus);
        Assert.Equal("USER_APPROVED_2026-09-09", policy.Approval);
        Assert.Equal(1600u, policy.InitialPolitical);
        Assert.Equal(1600u, policy.InitialMilitary);
        Assert.Equal(1600u, policy.Cap);
        Assert.Equal(160u, policy.RegenerationAmount);
        Assert.Equal(TimeSpan.FromMinutes(5), policy.RegenerationInterval);
        Assert.Equal(2u, policy.SubstitutionRatio);
        Assert.True(policy.DeficitOnlySubstitution);
        Assert.True(policy.ExcludeSubstituteFromGrowth);
        Assert.True(policy.SuspendRegenerationInTactics);
    }

    [Fact]
    public void Regeneration_grants_whole_intervals_and_keeps_the_remainder()
    {
        var policy = Deployed();
        var start = new DateTimeOffset(2026, 9, 9, 0, 0, 0, TimeSpan.Zero);
        var accrual = policy.Accrue(100, start, start + TimeSpan.FromMinutes(12), inTactics: false);
        Assert.Equal(420u, accrual.Balance);
        Assert.Equal(320u, accrual.Granted);
        Assert.Equal(start + TimeSpan.FromMinutes(10), accrual.AccruedAt);

        // The remaining two minutes are still owed, so the next whole interval
        // arrives three minutes later rather than five.
        var next = policy.Accrue(accrual.Balance, accrual.AccruedAt, start + TimeSpan.FromMinutes(15),
            inTactics: false);
        Assert.Equal(580u, next.Balance);
        Assert.Equal(start + TimeSpan.FromMinutes(15), next.AccruedAt);
    }

    [Fact]
    public void Regeneration_stops_at_the_cap_without_banking_extra_intervals()
    {
        var policy = Deployed();
        var start = new DateTimeOffset(2026, 9, 9, 0, 0, 0, TimeSpan.Zero);
        var accrual = policy.Accrue(1550, start, start + TimeSpan.FromHours(9), inTactics: false);
        Assert.Equal(policy.Cap, accrual.Balance);
        Assert.Equal(50u, accrual.Granted);
        Assert.Equal(start + TimeSpan.FromHours(9), accrual.AccruedAt);
    }

    [Fact]
    public void Tactical_time_is_discarded_rather_than_granted_later()
    {
        var policy = Deployed();
        var start = new DateTimeOffset(2026, 9, 9, 0, 0, 0, TimeSpan.Zero);
        var inBattle = policy.Accrue(200, start, start + TimeSpan.FromMinutes(20), inTactics: true);
        Assert.Equal(200u, inBattle.Balance);
        Assert.Equal(0u, inBattle.Granted);
        Assert.Equal(start + TimeSpan.FromMinutes(20), inBattle.AccruedAt);

        var afterBattle = policy.Accrue(inBattle.Balance, inBattle.AccruedAt,
            start + TimeSpan.FromMinutes(25), inTactics: false);
        Assert.Equal(360u, afterBattle.Balance);
    }

    [Fact]
    public void A_clock_that_moves_backwards_changes_nothing()
    {
        var policy = Deployed();
        var start = new DateTimeOffset(2026, 9, 9, 0, 0, 0, TimeSpan.Zero);
        var accrual = policy.Accrue(300, start, start - TimeSpan.FromHours(1), inTactics: false);
        Assert.Equal(300u, accrual.Balance);
        Assert.Equal(0u, accrual.Granted);
        Assert.Equal(start, accrual.AccruedAt);
    }

    [Theory]
    [InlineData(OriginalCommandPointPool.Military)]
    [InlineData(OriginalCommandPointPool.Political)]
    public void A_full_pool_pays_the_whole_cost_without_touching_the_other(OriginalCommandPointPool pool)
    {
        var policy = Deployed();
        var charge = policy.Charge(1600, 1600, pool, 160);
        Assert.True(charge.Accepted);
        Assert.Equal(160u, charge.Direct);
        Assert.Equal(0u, charge.Substitute);
        Assert.Equal(pool == OriginalCommandPointPool.Military ? 1600u : 1440u, charge.PoliticalAfter);
        Assert.Equal(pool == OriginalCommandPointPool.Military ? 1440u : 1600u, charge.MilitaryAfter);
    }

    [Fact]
    public void Only_the_deficit_is_substituted_from_the_other_pool_at_the_authored_ratio()
    {
        var policy = Deployed();
        var charge = policy.Charge(political: 500, military: 100, OriginalCommandPointPool.Military, cost: 160);
        Assert.True(charge.Accepted);
        Assert.Equal(100u, charge.Direct);
        Assert.Equal(120u, charge.Substitute);
        Assert.Equal(380u, charge.PoliticalAfter);
        Assert.Equal(0u, charge.MilitaryAfter);
    }

    [Fact]
    public void An_unaffordable_command_spends_nothing_at_all()
    {
        var policy = Deployed();
        var charge = policy.Charge(political: 119, military: 100, OriginalCommandPointPool.Military, cost: 160);
        Assert.False(charge.Accepted);
        Assert.Equal("COMMAND_POINTS_INSUFFICIENT", charge.ErrorCode);
        Assert.Equal(0u, charge.Direct);
        Assert.Equal(0u, charge.Substitute);
        Assert.Equal(119u, charge.PoliticalAfter);
        Assert.Equal(100u, charge.MilitaryAfter);
    }

    [Fact]
    public void A_policy_that_makes_the_other_pool_free_is_rejected_at_load()
    {
        var json = """
            {"schemaVersion":1,"initialPolitical":10,"initialMilitary":10,"cap":10,
             "regenerationAmount":1,"regenerationIntervalSeconds":60,"substitutionRatio":0,
             "deficitOnlySubstitution":true,"excludeSubstituteFromGrowth":true,
             "suspendRegenerationInTactics":true}
            """;
        Assert.Throws<InvalidDataException>(() => OriginalCommandPointPolicy.Parse(json));
    }
}
