using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalAttackSessionClockTests
{
    [Theory]
    [InlineData("0405", "0100000000", "00000000")]
    [InlineData("0405", "0100000000", "F1234567")]
    [InlineData("0405", "0200000000", "00000000")]
    [InlineData("0405", "0200000000", "F1234567")]
    [InlineData("0406", "00017F000001", "00000000")]
    [InlineData("0406", "00017F000001", "F1234567")]
    public async Task Applied_damage_and_command_reply_share_authority_time(
        string type, string targetFields, string requestTime)
    {
        // Breaks caught: copying request Time into 0426, preserving a future
        // Time+Wait in 0405/0406, or using a fixed/connection-start timestamp.
        // This characterizes existing immediate authored damage, not cadence.
        var clock = new TestClock();
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(clock, key,
            catalog: OriginalSharedBattleTests.CloseCombatCatalog());
        var request = Convert.FromHexString(type + requestTime + "DEADBEEF000000020100000002" + targetFields);
        for (uint sequence = 1; sequence <= 2; sequence++)
        {
            clock.Timestamp = sequence == 1 ? 1000 : 4000;
            var encrypted = OriginalClientInnerFrameCodec.Encode(request, key, sequence);
            var original = encrypted.ToArray();
            var result = await session.ProcessAsync(0x0030, encrypted, CancellationToken.None);
            Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
            Assert.Equal(original, encrypted);
            var pushes = Assert.IsAssignableFrom<IReadOnlyList<NaturalAuthorityPush>>(result.AdditionalResponses);
            Assert.Equal(3, pushes.Count);
            var damage = Decode(pushes[0].Payload, key);
            Assert.Equal((ushort)0x0426, BinaryPrimitives.ReadUInt16BigEndian(damage.AsSpan(4)));
            Assert.Equal(sequence == 1 ? 24u : 96u, BinaryPrimitives.ReadUInt32BigEndian(damage.AsSpan(6)));
            Assert.Equal(sequence == 1 ? (ushort)25 : (ushort)50,
                BinaryPrimitives.ReadUInt16BigEndian(damage.AsSpan(20)));
            Assert.Equal((ushort)0, BinaryPrimitives.ReadUInt16BigEndian(damage.AsSpan(22)));
            var reply = Decode(result.ResponsePayload!, key);
            Assert.Equal(type, Convert.ToHexString(reply.AsSpan(4, 2)));
            Assert.Equal(sequence == 1 ? 24u : 96u, BinaryPrimitives.ReadUInt32BigEndian(reply.AsSpan(6)));
            Assert.Equal(0u, BinaryPrimitives.ReadUInt32BigEndian(reply.AsSpan(10)));
            Assert.Equal(request.AsSpan(10).ToArray(), reply.AsSpan(14).ToArray());
        }
    }

    [Fact]
    public async Task Cease_fire_reply_is_due_without_emitting_damage()
    {
        var clock = new TestClock();
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(clock, key);
        clock.Timestamp = 1000;
        var request = Convert.FromHexString("0405F1234567DEADBEEF0000000201000000020000000000");
        var result = await session.ProcessAsync(0x0030,
            OriginalClientInnerFrameCodec.Encode(request, key, 1), CancellationToken.None);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        Assert.True(result.AdditionalResponses is null || result.AdditionalResponses.Count == 0);
        var reply = Decode(result.ResponsePayload!, key);
        Assert.Equal("00000000040500000018000000000000000201000000020000000000",
            Convert.ToHexString(reply));
    }

    private static byte[] Decode(byte[] encrypted, byte[] key)
    {
        var decoded = OriginalClientInnerFrameCodec.Decode(encrypted, key, 0);
        Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
        return decoded.Payload!;
    }

    private sealed class TestClock : TimeProvider
    {
        public long Timestamp;
        public override long TimestampFrequency => 1000;
        public override long GetTimestamp() => Timestamp;
    }
}
