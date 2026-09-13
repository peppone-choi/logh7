using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalMoveRouteAuthorizationTests
{
    [Fact]
    public async Task Accepted_route_unblocks_final_turn_after_the_ship_snapshot()
    {
        // E074: one waypoint produces turn/move/final-turn. 033B alone
        // authorizes only through the move; native cursor remains 2/1.
        // This is a real encrypted session test, not login/DB/native proof.
        var clock = new Clock();
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(clock, key);
        clock.Now = 2000;
        var result = await session.ProcessAsync(0x0030,
            OriginalClientInnerFrameCodec.Encode(Request(2), key, 1), CancellationToken.None);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        var frames = result.AdditionalResponses!.Select(push =>
            OriginalClientInnerFrameCodec.Decode(push.Payload, key, 0).Payload!).ToArray();
        Assert.Equal(new ushort[] { 0x033B, 0x0423 }, frames.Select(frame =>
            BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4))).ToArray());
        var authorization = frames[1];
        Assert.Equal(31, authorization.Length);
        Assert.Equal(48u, BinaryPrimitives.ReadUInt32BigEndian(authorization.AsSpan(6)));
        Assert.Equal(2u, BinaryPrimitives.ReadUInt32BigEndian(authorization.AsSpan(10)));
        Assert.Equal(1, authorization[30]); // Nonnegative out-of-waypoint-range: do not rebuild path.

        OriginalTacticalSegment[] path =
            [new(3, .1f, new(0, 1, 0)), new(2, 1, new(10, 0, 0)), new(3, .5f, new(0, 2, 0))];
        var frame = OriginalTacticalTrajectory.Step(new(new(0, 0, 0), 0), path,
            [new(10, 0, 0)], new(2, 0, 1, authorization[30]), .25f);
        Assert.Equal(2, frame.Cursor.AuthorizedSegment);
        Assert.Equal(.25f, frame.Cursor.Elapsed);
        Assert.False(frame.Completed); // Authorization is not an arrival event.
        var final = OriginalTacticalTrajectory.Step(new(new(0, 0, 0), 0), path,
            [new(10, 0, 0)], frame.Cursor, .5f);
        Assert.True(final.Completed);
        Assert.Equal(2f, final.Desired.Direction);
    }

    [Fact]
    public async Task Rejected_foreign_unit_gets_no_route_authorization()
    {
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(new Clock(), key);
        var result = await session.ProcessAsync(0x0030,
            OriginalClientInnerFrameCodec.Encode(Request(3), key, 1), CancellationToken.None);
        Assert.True(result.AdditionalResponses is null || result.AdditionalResponses.Count == 0);
    }

    private static byte[] Request(uint unit)
    {
        var bytes = new byte[56];
        BinaryPrimitives.WriteUInt16BigEndian(bytes, 0x0400);
        BinaryPrimitives.WriteUInt32BigEndian(bytes.AsSpan(10), 7);
        bytes[14] = 1;
        BinaryPrimitives.WriteUInt32BigEndian(bytes.AsSpan(15), unit);
        BinaryPrimitives.WriteSingleBigEndian(bytes.AsSpan(35), 1);
        BinaryPrimitives.WriteSingleBigEndian(bytes.AsSpan(39), 2);
        bytes[43] = 1;
        BinaryPrimitives.WriteSingleBigEndian(bytes.AsSpan(44), 10);
        return bytes;
    }

    private sealed class Clock : TimeProvider
    {
        public long Now;
        public override long TimestampFrequency => 1000;
        public override long GetTimestamp() => Now;
    }
}
