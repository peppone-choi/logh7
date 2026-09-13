using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalPlayerFireCadenceTests
{
    [Theory]
    [InlineData(10000L)]
    [InlineData(178956970000L)] // tick4294967280; next valid shot wraps to32.
    public async Task Recharge_survives_clock_wrap_and_rejects_a_backward_tick(long firstShotTime)
    {
        var clock = new Clock();
        var gameClock = new OriginalGameClock(clock);
        var battles = new OriginalTacticalBattleRegistry();
        var actor = OriginalPlayerCombatTests.Session(battles, 2, 2,
            OriginalSharedBattleTests.CloseCombatCatalog(), clock, gameClock);
        await Send(actor, "0F02", 1);
        const string shot = "0406000000000000000000000002010000000200017F000001";
        clock.Timestamp = firstShotTime;
        Assert.Equal(firstShotTime == 10000 ? 240u : 4294967280u, gameClock.Tick);
        Assert.StartsWith("tactical-command-accepted", (await Send(actor, shot, 2)).ResponseMetadata);
        clock.Timestamp = firstShotTime - 1;
        Assert.StartsWith("command-reject=TACTICAL_WEAPON_RECHARGING",
            (await Send(actor, shot, 3)).ResponseMetadata);
        clock.Timestamp = firstShotTime + 1999; // 47 ticks, one short of the original's 48
        Assert.StartsWith("command-reject=TACTICAL_WEAPON_RECHARGING",
            (await Send(actor, shot, 4)).ResponseMetadata);
        Assert.Equal((ushort)25, battles.GetEncounter(101, 100).EnemyDamage.Damaged);
        clock.Timestamp = firstShotTime + 2000; // 48 G秒 = the tooltip's 実行待機時間
        Assert.Equal(firstShotTime == 10000 ? 288u : 32u, gameClock.Tick);
        Assert.StartsWith("tactical-command-accepted", (await Send(actor, shot, 5)).ResponseMetadata);
        Assert.Equal((ushort)50, battles.GetEncounter(101, 100).EnemyDamage.Damaged);
    }

    [Fact]
    public async Task A_later_connection_keeps_the_server_epoch_and_existing_recharge()
    {
        var clock = new Clock();
        var gameClock = new OriginalGameClock(clock);
        var battles = new OriginalTacticalBattleRegistry();
        var catalog = OriginalSharedBattleTests.CloseCombatCatalog();
        var first = OriginalPlayerCombatTests.Session(battles, 2, 2, catalog, clock, gameClock);
        await Send(first, "0F02", 1);
        const string shot = "0406000000000000000000000002010000000200017F000001";
        clock.Timestamp = 1000;
        Assert.StartsWith("tactical-command-accepted", (await Send(first, shot, 2)).ResponseMetadata);
        clock.Timestamp = 2000;
        var later = OriginalPlayerCombatTests.Session(battles, 2, 2, catalog, clock, gameClock);
        await Send(later, "0F02", 1);
        Assert.StartsWith("command-reject=TACTICAL_WEAPON_RECHARGING",
            (await Send(later, shot, 2)).ResponseMetadata);
        clock.Timestamp = 4000;
        Assert.StartsWith("tactical-command-accepted", (await Send(later, shot, 3)).ResponseMetadata);
        Assert.Equal((ushort)50, battles.GetEncounter(101, 100).EnemyDamage.Damaged);
    }

    [Theory]
    [InlineData("0405", "01")]
    [InlineData("0406", "0001")]
    public async Task A_second_session_cannot_bypass_recharge_or_use_client_time(string opcode, string targetPrefix)
    {
        var clock = new Clock();
        var battles = new OriginalTacticalBattleRegistry();
        var catalog = OriginalSharedBattleTests.CloseCombatCatalog();
        var first = OriginalPlayerCombatTests.Session(battles, 2, 2, catalog, clock);
        var second = OriginalPlayerCombatTests.Session(battles, 2, 2, catalog, clock);
        await Send(first, "0F02", 1);
        await Send(second, "0F02", 1);
        var shot = opcode + "F1234567DEADBEEF000000020100000002" + targetPrefix + "7F000001";
        Assert.StartsWith("tactical-command-accepted", (await Send(first, shot, 2)).ResponseMetadata);
        var rejected = await Send(second, shot, 2);
        Assert.StartsWith("command-reject=TACTICAL_WEAPON_RECHARGING", rejected.ResponseMetadata);
        Assert.Equal((ushort)25, battles.GetEncounter(101, 100).EnemyDamage.Damaged);
        await Send(second, "0F02", 3); // Scene refresh must not reset the ship's cadence.
        clock.Timestamp = 1999; // 47 ticks, one short of the original's 48 G秒 実行待機時間.
        Assert.StartsWith("command-reject=TACTICAL_WEAPON_RECHARGING",
            (await Send(first, shot, 3)).ResponseMetadata);
        Assert.StartsWith("command-reject=TACTICAL_WEAPON_RECHARGING",
            (await Send(second, shot, 4)).ResponseMetadata);
        clock.Timestamp = 2000;
        Assert.StartsWith("tactical-command-accepted", (await Send(second, shot, 5)).ResponseMetadata);
        Assert.Equal((ushort)50, battles.GetEncounter(101, 100).EnemyDamage.Damaged);
    }

    private static Task<NaturalAuthoritySessionResult> Send(NaturalAuthoritySession session, string hex, uint sequence) =>
        session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(Convert.FromHexString(hex),
            new byte[16], sequence), TestContext.Current.CancellationToken);

    internal sealed class Clock : TimeProvider
    {
        public long Timestamp;
        public override long TimestampFrequency => 1000;
        public override long GetTimestamp() => Timestamp;
    }
}
