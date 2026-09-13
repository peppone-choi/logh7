using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// 平行移動 (0x0402) and 反転 (0x0403): two palette commands the client can send
/// that this authority answered with the not-implemented refusal until now.
/// </summary>
public sealed class OriginalParallelMoveAndReverseTests
{
    /// <summary>
    /// 平行移動 is 移動 without the turn. constmsg group 0 row 12 is
    /// 「選択ユニットを平行移動する」 against row 4's 「移動させる」, and the client's own two
    /// loggers print the identical field list, so the same body decodes for both
    /// and only the heading treatment differs.
    /// </summary>
    [Fact]
    public async Task A_parallel_move_takes_the_destination_and_leaves_the_heading()
    {
        var clock = new OriginalPlayerFireCadenceTests.Clock();
        var battles = new OriginalTacticalBattleRegistry();
        var actor = OriginalPlayerCombatTests.Session(battles, 2, 2,
            clock: clock, gameClock: new OriginalGameClock(clock));
        await Send(actor, Convert.ToHexString(
            OriginalTacticalCommandCodec.EncodeTurnShipCommand(
                new(0, 0, 0, [new(2, 0f, 1.25f)])).AsSpan(4)), 1);
        clock.Timestamp += 2000; // clear 旋回's own 48 G秒 wait

        var parallel = OriginalTacticalCommandCodec.EncodeMoveShipCommand(
            new(0, 0, 0, [new(2, 1.25f, 0f, 0f, 0f)], 1f, -2.5f, [new(4f, 5f, 0f)]),
            OriginalTacticalCommandCodec.ParallelMoveShipCommandType);
        var answer = await Send(actor, Convert.ToHexString(parallel.AsSpan(4)), 2);

        Assert.Contains("tactical-parallel-move-ship-accepted", answer.ResponseMetadata);
        Assert.Contains("x=4;y=5;z=0", answer.ResponseMetadata);
        // The command asked for -2.5 and 平行移動 must ignore it.
        Assert.Contains("direction=1.25", answer.ResponseMetadata);
        Assert.DoesNotContain("direction=-2.5", answer.ResponseMetadata);
    }

    /// <summary>The same body sent as 移動 does take the requested heading.</summary>
    [Fact]
    public async Task A_move_takes_the_heading_the_same_body_carries()
    {
        var clock = new OriginalPlayerFireCadenceTests.Clock();
        var battles = new OriginalTacticalBattleRegistry();
        var actor = OriginalPlayerCombatTests.Session(battles, 2, 2,
            clock: clock, gameClock: new OriginalGameClock(clock));

        var move = OriginalTacticalCommandCodec.EncodeMoveShipCommand(
            new(0, 0, 0, [new(2, 0f, 0f, 0f, 0f)], 1f, -2.5f, [new(4f, 5f, 0f)]));
        var answer = await Send(actor, Convert.ToHexString(move.AsSpan(4)), 1);

        Assert.Contains("tactical-move-ship-accepted", answer.ResponseMetadata);
        Assert.Contains("direction=-2.5", answer.ResponseMetadata);
    }

    /// <summary>
    /// 反転, constmsg group 0 row 14 「旗艦を中心に反転する」: the heading becomes the
    /// opposite one. The body was recovered long ago; only the handler was
    /// missing, so the command used to be refused as unimplemented.
    /// </summary>
    [Fact]
    public async Task A_reverse_turns_the_unit_to_face_the_other_way()
    {
        var clock = new OriginalPlayerFireCadenceTests.Clock();
        var battles = new OriginalTacticalBattleRegistry();
        var actor = OriginalPlayerCombatTests.Session(battles, 2, 2,
            clock: clock, gameClock: new OriginalGameClock(clock));
        await Send(actor, Convert.ToHexString(
            OriginalTacticalCommandCodec.EncodeTurnShipCommand(
                new(0, 0, 0, [new(2, 0f, 0.5f)])).AsSpan(4)), 1);

        // 0403 time wait order count unit direction(float) side(byte)
        const string reverse = "0403" + "000000000000000000000000" + "01"
            + "00000002" + "3F000000" + "00";
        var answer = await Send(actor, reverse, 2);

        Assert.Contains("tactical-reverse-ship-accepted", answer.ResponseMetadata);
        Assert.Contains("from=0.5", answer.ResponseMetadata);
        // 0.5 + pi, normalised into (-pi, pi] = 0.5 - pi.
        Assert.Contains("direction=-2.64", answer.ResponseMetadata);
    }

    /// <summary>Both carry the original's own 48 G秒 wait, and neither an invented duration.</summary>
    [Fact]
    public void The_two_commands_take_their_schedule_from_the_clients_tooltip()
    {
        foreach (var type in new[]
                 {
                     OriginalTacticalCommandCodec.ParallelMoveShipCommandType,
                     OriginalTacticalCommandCodec.ReverseShipCommandType,
                 })
        {
            Assert.Equal(48u, OriginalTacticalCommandTiming.WaitTicks(type));
            // 「目標地点まで継続」 and 「旋回性能+機動で算出」 are formulas, not numbers.
            Assert.Null(OriginalTacticalCommandTiming.For(type)!.Value.DurationGameSeconds);
            Assert.Equal(0u, OriginalTacticalCommandTiming.DurationTicks(type));
        }
    }

    private static Task<NaturalAuthoritySessionResult> Send(
        NaturalAuthoritySession session, string hex, uint sequence) =>
        session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString(hex), new byte[16], sequence),
            TestContext.Current.CancellationToken);
}
