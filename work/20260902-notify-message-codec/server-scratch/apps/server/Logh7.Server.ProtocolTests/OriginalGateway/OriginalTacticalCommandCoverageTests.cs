using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// Coverage against the client's own tactical command table, enforced rather than
/// reported: every one of the 35 types is driven through a real session and must
/// behave the way <see cref="OriginalTacticalCommandCoverage"/> claims.
/// </summary>
/// <remarks>
/// The point is that the claim cannot drift. Add a handler without listing its
/// type, or list a type without a handler, and this fails.
/// </remarks>
public sealed class OriginalTacticalCommandCoverageTests
{
    /// <summary>
    /// A type this authority claims to handle must never answer with the
    /// not-implemented refusal. It may accept, or refuse for a reason of its own,
    /// or reject the shape - all of those mean a handler ran.
    /// </summary>
    [Fact]
    public async Task Every_handled_type_answers_on_its_own_terms()
    {
        foreach (var type in OriginalTacticalCommandCoverage.Handled.OrderBy(t => t))
        {
            var answer = await SendMinimalAsync(type);
            Assert.DoesNotContain(OriginalTacticalCommandCatalog.NotImplementedErrorCode,
                answer.ResponseMetadata ?? string.Empty);
        }
    }

    /// <summary>
    /// Every catalogued type this authority does not handle must be answered
    /// visibly - never dropped, and never silently swallowed - with its body kept
    /// on the wire so one live press recovers the native shape.
    /// </summary>
    [Fact]
    public async Task Every_unhandled_type_is_refused_visibly_with_its_body()
    {
        var seen = 0;
        foreach (var type in OriginalTacticalCommandCoverage.Unhandled())
        {
            seen++;
            var body = Body(type);
            var answer = await SendAsync(body);
            Assert.Equal(NaturalAuthoritySessionStatus.Success, answer.Status);
            Assert.Contains(OriginalTacticalCommandCatalog.NotImplementedErrorCode,
                answer.ResponseMetadata ?? string.Empty);
            // The command's own name and its body, so a live sweep recovers shapes.
            Assert.Contains("name=" + OriginalTacticalCommandCatalog.NameOf(type),
                answer.ResponseMetadata!);
            Assert.Contains("payloadHex=" + body, answer.ResponseMetadata!);
        }
        Assert.Equal(0, seen);
    }

    /// <summary>The two sets together are the client's whole table, with no overlap.</summary>
    [Fact]
    public void The_two_sets_partition_the_clients_table()
    {
        var handled = OriginalTacticalCommandCoverage.Handled;
        var unhandled = OriginalTacticalCommandCoverage.Unhandled().ToHashSet();
        Assert.Equal(35, handled.Count);
        Assert.Empty(unhandled);
        Assert.Empty(handled.Intersect(unhandled));
        for (var type = OriginalTacticalCommandCatalog.FirstType;
             type <= OriginalTacticalCommandCatalog.LastType;
             type++)
        {
            Assert.True(handled.Contains(type) || unhandled.Contains(type),
                $"0x{type:X4} is in the client's table but in neither coverage set");
        }
        // Every handled type is one the client's own table names.
        Assert.All(handled, type => Assert.True(OriginalTacticalCommandCatalog.IsTacticalCommand(type)));
    }

    /// <summary>
    /// Coverage is about handling, not about live receipts. The commands with a
    /// live receipt from the shipped client are a much smaller set, and this test
    /// exists so the two are never confused: it names them explicitly.
    /// </summary>
    [Fact]
    public void Handling_is_not_the_same_claim_as_a_live_receipt()
    {
        // Captured from the shipped client and recorded in
        // work/20260904-warp-state-reverse/evidence.
        ushort[] liveReceipts =
        [
            OriginalTacticalCommandCodec.MoveShipCommandType,         // move-ship-accepted
            OriginalTacticalCommandCodec.TurnShipCommandType,         // turn-ship-accepted
            OriginalTacticalCommandCodec.ParallelMoveShipCommandType, // parallel-move v381
            OriginalTacticalCommandCodec.ReverseShipCommandType,      // reverse x2, v386
            OriginalTacticalCommandCodec.WarpCommandType,             // retreat, v370
            OriginalTacticalCommandCodec.AttackShipCommandType,       // attack accepted
            OriginalTacticalCommandCodec.ShootShipCommandType,        // shoot, v376
            OriginalEncourageFlagshipCodec.CommandType,               // encourage, v374
            OriginalTacticalCommandCodec.StopCommandType,             // stop accepted
            OriginalTacticalCommandCodec.FileFleetCommandType,        // file-fleet, v387
            OriginalTacticalCommandCodec.MissionCommandType,          // mission, v379
        ];
        Assert.All(liveReceipts, type =>
            Assert.True(OriginalTacticalCommandCoverage.IsHandled(type)));
        // Fewer live receipts than handled types: 修理, 補給, 具申, 態勢変更, 出撃,
        // StopFleet and 出力配分 are handled without one.
        Assert.True(liveReceipts.Length < OriginalTacticalCommandCoverage.Handled.Count);
    }

    private static string Body(ushort type) =>
        type.ToString("X4", System.Globalization.CultureInfo.InvariantCulture) +
        "08D2473800000002";

    private static Task<NaturalAuthoritySessionResult> SendMinimalAsync(ushort type) =>
        SendAsync(Body(type));

    private static async Task<NaturalAuthoritySessionResult> SendAsync(string body)
    {
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key);
        return await session.ProcessAsync(0x0030, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString(body), key, 1), TestContext.Current.CancellationToken);
    }

    /// <summary>
    /// "Unhandled" must never mean "unknown": every type this authority refuses
    /// says what is actually missing, and every one of those statements is about a
    /// game model rather than about the wire.
    /// </summary>
    [Fact]
    public void Every_unhandled_type_names_the_model_it_is_missing()
    {
        foreach (var type in OriginalTacticalCommandCoverage.Unhandled())
        {
            var reason = OriginalTacticalCommandCoverage.MissingModelFor(type);
            Assert.False(string.IsNullOrWhiteSpace(reason),
                $"0x{type:X4} is unhandled but does not say what it is missing");
        }
        // A handled type has nothing to declare.
        Assert.Null(OriginalTacticalCommandCoverage.MissingModelFor(
            OriginalTacticalCommandCodec.MoveShipCommandType));
    }
}
