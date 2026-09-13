using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// <c>CommandStopFleet</c> = 0x0415 - 停止 addressed to named fleets. Another of the
/// commands the client can send that used to be answered as unimplemented.
/// </summary>
public sealed class OriginalStopFleetTests
{
    /// <summary>
    /// It cancels what the named fleet is carrying out, which is exactly what the
    /// 実行所要時間 occupancy models and what 停止's own description says.
    /// </summary>
    [Fact]
    public async Task Stopping_a_named_fleet_cancels_what_it_is_carrying_out()
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

        // 0415 time wait id count unit
        const string stopFleet = "0415" + "000000000000000000000000" + "01" + "00000002";
        var answer = await Send(actor, stopFleet, 3);

        Assert.Contains("tactical-stop-fleet-accepted;units=2", answer.ResponseMetadata);
        Assert.Contains("tactical-turn-ship-accepted", (await Send(actor, turn, 4)).ResponseMetadata);
    }

    [Fact]
    public async Task A_fleet_the_player_does_not_command_is_refused_visibly()
    {
        var clock = new OriginalPlayerFireCadenceTests.Clock();
        var battles = new OriginalTacticalBattleRegistry();
        var actor = OriginalPlayerCombatTests.Session(battles, 2, 2,
            clock: clock, gameClock: new OriginalGameClock(clock));
        await Send(actor, "0F02", 1);

        var answer = await Send(actor, "0415" + "000000000000000000000000" + "01" + "00001092", 2);

        Assert.StartsWith("command-reject=STOP_FLEET_UNIT_NOT_CONTROLLED", answer.ResponseMetadata);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, answer.Status);
    }

    /// <summary>
    /// The body is the client's own: five commands share the bare
    /// 「header plus unit list」 shape, so the recovered 撤退 decoder reads them all.
    /// </summary>
    [Fact]
    public void The_body_is_the_one_five_of_the_clients_commands_share()
    {
        const string stopFleet = "0415" + "000000000000000000000007" + "02" + "00000002" + "00000003";
        Assert.True(OriginalTacticalCommandCodec.TryDecodeWarpCommand(
            Convert.FromHexString(stopFleet),
            OriginalTacticalCommandCodec.StopFleetCommandType, out var command));
        Assert.Equal(7u, command.Order);
        Assert.Equal([2u, 3u], command.UnitIds);
        // ...and it is not mistaken for 撤退, which is the same shape on another type.
        Assert.False(OriginalTacticalCommandCodec.TryDecodeWarpCommand(
            Convert.FromHexString(stopFleet), out _));
        Assert.Equal("StopFleet", OriginalTacticalCommandCatalog.NameOf(
            OriginalTacticalCommandCodec.StopFleetCommandType));
    }

    /// <summary>
    /// The original states no schedule for it - group 0 row 13 belongs to 停止
    /// 0x040A - so nothing gates it. An unrecovered schedule is never an invented
    /// wait.
    /// </summary>
    [Fact]
    public void An_unrecovered_schedule_does_not_gate_it()
    {
        Assert.Null(OriginalTacticalCommandTiming.For(
            OriginalTacticalCommandCodec.StopFleetCommandType));
        Assert.Equal(0u, OriginalTacticalCommandTiming.WaitTicks(
            OriginalTacticalCommandCodec.StopFleetCommandType));
    }

    private static Task<NaturalAuthoritySessionResult> Send(
        NaturalAuthoritySession session, string hex, uint sequence) =>
        session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString(hex), new byte[16], sequence),
            TestContext.Current.CancellationToken);
}
