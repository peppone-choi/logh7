using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// 隊列変更 = 0x040D CommandFileFleet, constmsg group 0 row 16
/// 「任意の隊列に変更する 実行待機時間48G秒 実行所要時間0G秒」.
/// </summary>
public sealed class OriginalFileFleetTests
{
    /// <summary>
    /// The body is the client's own: <c>_INF:CommandFileFleet#</c> (0x00496050)
    /// prints <c>time / wait / id / position[n]{id, direction, {x,y,z}} / kind</c>,
    /// the same 20-byte per-unit element 移動 carries plus one formation byte.
    /// </summary>
    [Fact]
    public void The_body_is_the_shape_the_clients_own_logger_names()
    {
        var command = new OriginalTacticalFileFleetCommand(
            0x11223344, 7, 2, [new(2u, 1f, 4f, 5f, 0f)], 3);

        var body = OriginalTacticalCommandCodec.EncodeFileFleetCommand(command)
            .AsSpan(OriginalLoginCodec.MessageCodeSize).ToArray();

        Assert.Equal(
            "040D" + "11223344" + "00000007" + "00000002" + "01" +
            "00000002" + "3F800000" + "40800000" + "40A00000" + "00000000" + "03",
            Convert.ToHexString(body));
        Assert.True(OriginalTacticalCommandCodec.TryDecodeFileFleetCommand(body, out var decoded));
        Assert.Equal(2u, decoded.Order);
        Assert.Equal(3, decoded.Kind);
        var position = Assert.Single(decoded.Positions);
        Assert.Equal(2u, position.UnitId);
        Assert.Equal(4f, position.X);
        Assert.Equal(5f, position.Y);
        Assert.Equal(1f, position.Direction);
        // One byte short is not a formation, and neither is another type.
        Assert.False(OriginalTacticalCommandCodec.TryDecodeFileFleetCommand(
            body.AsSpan(0, body.Length - 1), out _));
        body[1] = 0x00;
        Assert.False(OriginalTacticalCommandCodec.TryDecodeFileFleetCommand(body, out _));
    }

    /// <summary>
    /// The formation is accepted and its kind recorded - and the position array
    /// the body carries is NOT applied.
    /// </summary>
    /// <remarks>
    /// A first draft of this handler moved the ship to the last carried position,
    /// on the reasoning that a formation is where a unit stands. The live client
    /// falsified it on 2026-09-10: its 隊列変更 arrived with kind=7 and x/y/z of
    /// 7.02e-37 - denormal noise from a struct field it never fills - and the
    /// draft wrote that noise into the scene. This test pins the corrected
    /// behaviour by sending the same kind of junk the client sends.
    /// </remarks>
    [Fact]
    public async Task A_formation_records_its_kind_and_never_moves_the_ship()
    {
        var clock = new OriginalPlayerFireCadenceTests.Clock();
        var battles = new OriginalTacticalBattleRegistry();
        var actor = OriginalPlayerCombatTests.Session(battles, 2, 2,
            clock: clock, gameClock: new OriginalGameClock(clock));
        // Put the ship somewhere known first.
        var move = OriginalTacticalCommandCodec.EncodeMoveShipCommand(
            new(0, 0, 0, [new(2u, 0f, 0f, 0f, 0f)], 1f, 0.75f, [new(3f, 4f, 0f)]));
        Assert.Contains("x=3;y=4;z=0", (await Send(actor, Convert.ToHexString(move.AsSpan(4)), 1)).ResponseMetadata);
        clock.Timestamp += 2000; // clear 移動's own 48 G秒 wait

        // The denormal noise the live client actually sends.
        const float noise = 7.021136E-37f;
        var file = OriginalTacticalCommandCodec.EncodeFileFleetCommand(
            new(0, 0, 0, [new(2u, noise, noise, noise, noise)], 7));
        var answer = await Send(actor, Convert.ToHexString(file.AsSpan(4)), 2);

        Assert.Contains("tactical-file-fleet-accepted;unit=2", answer.ResponseMetadata);
        Assert.Contains("kind=7", answer.ResponseMetadata);
        Assert.Contains("positions=1", answer.ResponseMetadata);
        // Nothing about a place: the answer must not carry the junk anywhere.
        Assert.DoesNotContain("7.02", answer.ResponseMetadata);
        Assert.DoesNotContain("x=", answer.ResponseMetadata);

        // ...and the ship is still where 移動 put it. A second 移動 proves the
        // authority never took the formation's words as a position.
        clock.Timestamp += 2000;
        var again = OriginalTacticalCommandCodec.EncodeMoveShipCommand(
            new(0, 0, 0, [new(2u, 0.75f, 3f, 4f, 0f)], 1f, 0.75f, [new(5f, 6f, 0f)]));
        Assert.Contains("x=5;y=6;z=0", (await Send(actor, Convert.ToHexString(again.AsSpan(4)), 3)).ResponseMetadata);
    }

    [Fact]
    public async Task A_formation_for_a_fleet_the_player_does_not_command_is_refused_visibly()
    {
        var clock = new OriginalPlayerFireCadenceTests.Clock();
        var battles = new OriginalTacticalBattleRegistry();
        var actor = OriginalPlayerCombatTests.Session(battles, 2, 2,
            clock: clock, gameClock: new OriginalGameClock(clock));

        var file = OriginalTacticalCommandCodec.EncodeFileFleetCommand(
            new(0, 0, 0, [new(4242u, 0f, 0f, 0f, 0f)], 1));
        var answer = await Send(actor, Convert.ToHexString(file.AsSpan(4)), 1);

        Assert.StartsWith("command-reject=FILE_FLEET_UNIT_NOT_CONTROLLED", answer.ResponseMetadata);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, answer.Status);
    }

    /// <summary>Row 16's own numbers, and a command the client can really send.</summary>
    [Fact]
    public void It_takes_its_schedule_from_the_clients_tooltip()
    {
        Assert.Equal(48u, OriginalTacticalCommandTiming.WaitTicks(
            OriginalTacticalCommandCodec.FileFleetCommandType));
        Assert.Equal(0u, OriginalTacticalCommandTiming.DurationTicks(
            OriginalTacticalCommandCodec.FileFleetCommandType));
        Assert.Equal(98, OriginalTacticalCommandCatalog.SelectorOf(
            OriginalTacticalCommandCodec.FileFleetCommandType));
    }

    private static Task<NaturalAuthoritySessionResult> Send(
        NaturalAuthoritySession session, string hex, uint sequence) =>
        session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString(hex), new byte[16], sequence),
            TestContext.Current.CancellationToken);
}
