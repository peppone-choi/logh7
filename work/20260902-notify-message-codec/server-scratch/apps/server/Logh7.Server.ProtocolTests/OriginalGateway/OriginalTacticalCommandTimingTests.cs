using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// 実行待機時間 - the wait the shipped client prints on every tactical command
/// tile. The numbers come from the original's own message table
/// (constmsg group 0 rows 4..27) and the unit is the G秒, which is the authority
/// tick.
/// </summary>
public sealed class OriginalTacticalCommandTimingTests
{
    [Theory]
    // constmsg group 0 rows 4, 5, 23, 6, 7, 11 (具申 = 0x0421), 10: all 実行待機時間48G秒.
    [InlineData(0x0400, 48u)]
    [InlineData(0x0401, 48u)]
    [InlineData(0x0404, 48u)]
    [InlineData(0x0405, 48u)]
    [InlineData(0x0406, 48u)]
    [InlineData(0x0421, 48u)]
    [InlineData(0x0409, 48u)]
    // row 13: 停止 is the one tactical command with 実行待機時間0G秒.
    [InlineData(0x040a, 0u)]
    public void The_table_is_the_clients_own_number(ushort type, uint wait)
    {
        Assert.Equal(wait, OriginalTacticalCommandTiming.WaitTicks(type));
        var schedule = Assert.IsType<OriginalTacticalCommandSchedule>(
            OriginalTacticalCommandTiming.For(type), exactMatch: false);
        Assert.Equal(wait, schedule.WaitGameSeconds);
    }

    [Fact]
    public void An_unrecovered_schedule_never_gates_a_command()
    {
        // 0x0416 CommandMoveTroop is a command the client can send - it is in
        // OriginalTacticalCommandCatalog - but its wire shape is not recovered, so
        // this authority must not invent a refusal for it either.
        Assert.Null(OriginalTacticalCommandTiming.For(0x0416));
        Assert.Equal(0u, OriginalTacticalCommandTiming.WaitTicks(0x0416));
        // 平行移動 used to be this test's example. It is recovered now: same body as
        // 移動 (the client's two loggers print the identical field list) and the
        // same 48 G秒 the tooltip states.
        Assert.Equal(48u, OriginalTacticalCommandTiming.WaitTicks(
            OriginalTacticalCommandCodec.ParallelMoveShipCommandType));
    }

    /// <summary>
    /// The same command may not be re-issued inside its own wait, and the
    /// player is told why. A different command is not blocked by it - the
    /// original states the wait on each tile separately, not as a lockout.
    /// </summary>
    [Fact]
    public async Task A_command_waits_its_own_interval_and_no_others()
    {
        var clock = new OriginalPlayerFireCadenceTests.Clock();
        var battles = new OriginalTacticalBattleRegistry();
        var actor = OriginalPlayerCombatTests.Session(battles, 2, 2,
            clock: clock, gameClock: new OriginalGameClock(clock));
        await Send(actor, "0F02", 1);
        var turn = Convert.ToHexString(OriginalTacticalCommandCodec.EncodeTurnShipCommand(
            new(0, 0, 0, [new(2, 0f, 1f)])).AsSpan(4));

        Assert.Contains("tactical-turn-ship-accepted", (await Send(actor, turn, 2)).ResponseMetadata);

        // Same command, no time passed: refused visibly, not silently.
        var refused = await Send(actor, turn, 3);
        Assert.StartsWith($"command-reject={OriginalTacticalCommandTiming.WaitingErrorCode}",
            refused.ResponseMetadata);

        // 停止 has a wait of 0 and is unaffected by the turn's wait.
        const string stop = "040A000000000000000000000002010000000200";
        Assert.Contains("tactical-command-accepted", (await Send(actor, stop, 4)).ResponseMetadata);

        clock.Timestamp += 1958; // 47 ticks - still one short of 48.
        Assert.StartsWith($"command-reject={OriginalTacticalCommandTiming.WaitingErrorCode}",
            (await Send(actor, turn, 5)).ResponseMetadata);

        clock.Timestamp += 42; // 48 ticks total.
        Assert.Contains("tactical-turn-ship-accepted", (await Send(actor, turn, 6)).ResponseMetadata);
    }


    [Theory]
    // constmsg group 0: row 23 撤退 実行所要時間1800G秒, rows 20/21 態勢変更・出撃 240G秒,
    // rows 6/7/10/11/13 攻撃・射撃・鼓舞・具申・停止 0G秒.
    [InlineData(0x0404, 1800u)]
    [InlineData(0x0b06, 240u)]
    [InlineData(0x0405, 0u)]
    [InlineData(0x0406, 0u)]
    [InlineData(0x0409, 0u)]
    [InlineData(0x0421, 0u)]
    [InlineData(0x040a, 0u)]
    // 移動 and 旋回 state a formula, not a number: unrecovered, so 0 rather than invented.
    [InlineData(0x0400, 0u)]
    [InlineData(0x0401, 0u)]
    public void The_duration_table_is_the_clients_own_number(ushort type, uint duration) =>
        Assert.Equal(duration, OriginalTacticalCommandTiming.DurationTicks(type));

    /// <summary>
    /// While a unit is carrying out a command with a recovered 実行所要時間, other
    /// commands are refused visibly - and 停止, whose own description is
    /// 「行動をキャンセルする」, is accepted anyway and cancels it.
    /// </summary>
    [Fact]
    public async Task A_unit_carrying_out_a_command_refuses_others_until_it_is_cancelled()
    {
        var clock = new OriginalPlayerFireCadenceTests.Clock();
        var battles = new OriginalTacticalBattleRegistry();
        var actor = OriginalPlayerCombatTests.Session(battles, 2, 2,
            clock: clock, gameClock: new OriginalGameClock(clock));
        await Send(actor, "0F02", 1);
        var turn = Convert.ToHexString(OriginalTacticalCommandCodec.EncodeTurnShipCommand(
            new(0, 0, 0, [new(2, 0f, 1f)])).AsSpan(4));
        const string stop = "040A000000000000000000000002010000000200";

        // 態勢変更's own 240 G秒, started directly: the departure path that would
        // start it needs a persisted unit, and this test is about the gate.
        battles.RecordExecuting(2, 0, 240);

        Assert.StartsWith($"command-reject={OriginalTacticalCommandTiming.ExecutingErrorCode}",
            (await Send(actor, turn, 2)).ResponseMetadata);
        Assert.Contains("tactical-command-accepted", (await Send(actor, stop, 3)).ResponseMetadata);
        Assert.Contains("tactical-turn-ship-accepted", (await Send(actor, turn, 4)).ResponseMetadata);
    }

    /// <summary>A duration that elapses on its own needs no 停止.</summary>
    [Fact]
    public async Task A_duration_ends_by_itself()
    {
        var clock = new OriginalPlayerFireCadenceTests.Clock();
        var battles = new OriginalTacticalBattleRegistry();
        var actor = OriginalPlayerCombatTests.Session(battles, 2, 2,
            clock: clock, gameClock: new OriginalGameClock(clock));
        await Send(actor, "0F02", 1);
        var turn = Convert.ToHexString(OriginalTacticalCommandCodec.EncodeTurnShipCommand(
            new(0, 0, 0, [new(2, 0f, 1f)])).AsSpan(4));
        battles.RecordExecuting(2, 0, 240);

        Assert.StartsWith($"command-reject={OriginalTacticalCommandTiming.ExecutingErrorCode}",
            (await Send(actor, turn, 2)).ResponseMetadata);
        clock.Timestamp = 10000; // 240 ticks
        Assert.Contains("tactical-turn-ship-accepted", (await Send(actor, turn, 3)).ResponseMetadata);
    }

    private static Task<NaturalAuthoritySessionResult> Send(NaturalAuthoritySession session, string hex, uint sequence) =>
        session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(Convert.FromHexString(hex),
            new byte[16], sequence), TestContext.Current.CancellationToken);
}
