using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalWarpPowerGateTests
{
    [Fact]
    public async Task Default_warp_power_cannot_produce_a_completion_notification()
    {
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key);
        AssertPowerRejected(await Warp(session, key, 1), key);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(10)]
    [InlineData(49)]
    public async Task Accepted_control_below_fifty_does_not_authorize_retreat(byte power)
    {
        // Break caught: reading the initial/default power instead of the
        // accepted 040C state, or checking only controlled-unit identity.
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key);
        await SetPower(session, key, power);
        AssertPowerRejected(await Warp(session, key, 2), key);
    }

    [Fact]
    public async Task Lowering_power_after_fifty_revokes_the_power_prerequisite()
    {
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key);
        await SetPower(session, key, 50);
        var eligible = await Warp(session, key, 2);
        Assert.Equal((ushort)0x0425, ResponseType(eligible, key));
        await SetPower(session, key, 49, 3);
        AssertPowerRejected(await Warp(session, key, 4), key);
        // Existing immediate completion is characterized, not endorsed as a
        // complete retreat lifecycle. No native input or DB is involved.
    }

    internal static async Task SetPower(NaturalAuthoritySession session, byte[] key, byte power,
        uint sequence = 1)
    {
        // Literal original compact control shape: actor2/unit2/condenser50,
        // beam0/gun0/shields4,4,3,3,3,3/engine20/warp/sensor10. Sum <=100.
        var payload = Convert.FromHexString(
            "040C000000000000000000000002000000020032000004040303030314000A");
        payload[29] = power;
        var result = await session.ProcessAsync(0x0030,
            OriginalClientInnerFrameCodec.Encode(payload, key, sequence), CancellationToken.None);
        Assert.Contains("tactical-control-accepted", result.ResponseMetadata);
        Assert.Equal((ushort)0x040C, ResponseType(result, key));
    }

    private static Task<NaturalAuthoritySessionResult> Warp(NaturalAuthoritySession session, byte[] key,
        uint sequence) =>
        session.ProcessAsync(0x0030, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0404F1234567DEADBEEF000000020100000002"), key, sequence),
            CancellationToken.None);

    private static void AssertPowerRejected(NaturalAuthoritySessionResult result, byte[] key)
    {
        Assert.Contains("TACTICAL_WARP_POWER_INSUFFICIENT", result.ResponseMetadata);
        Assert.Equal((ushort)0x0500, ResponseType(result, key));
        Assert.True(result.AdditionalResponses is null || result.AdditionalResponses.Count == 0);
    }

    private static ushort ResponseType(NaturalAuthoritySessionResult result, byte[] key)
    {
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        var decoded = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, key, 0);
        Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
        return BinaryPrimitives.ReadUInt16BigEndian(decoded.Payload!.AsSpan(4));
    }
}
