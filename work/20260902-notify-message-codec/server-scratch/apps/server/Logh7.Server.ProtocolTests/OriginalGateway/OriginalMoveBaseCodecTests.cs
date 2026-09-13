using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalMoveBaseCodecTests
{
    private const string Packed = "0B0001020304112233445566778899AABBCCDDEE1234567887654321FEDC";

    [Fact]
    public void Admission_response_uses_command_shape_not_movement_notification()
    {
        var frame = OriginalMoveBaseCodec.EncodeResponse(new(0x01020304, 0x11223344, 0x55667788,
            0x99aa, 0xbbccddee, 0x12345678, 0x87654321, 0xfedc));
        Assert.Equal(Convert.FromHexString(
            "000000000B0001020304112233445566778899AABBCCDDEE1234567887654321FEDC"), frame);
    }

    [Fact]
    public void Packed_base_move_preserves_actor_wait_cost_fields_and_raw_mode()
    {
        Assert.True(OriginalMoveBaseCodec.TryDecodeRequest(Convert.FromHexString(Packed), out var request));
        Assert.Equal(0x01020304u, request.Time);
        Assert.Equal(0x11223344u, request.Wait);
        Assert.Equal(0x55667788u, request.ActorId);
        Assert.Equal((ushort)0x99aa, request.Card);
        Assert.Equal(0xbbccddeeu, request.Pcp);
        Assert.Equal(0x12345678u, request.Mcp);
        Assert.Equal(0x87654321u, request.BaseId);
        Assert.Equal((ushort)0xfedc, request.Mode);
    }

    [Fact]
    public void Rejects_wrong_type_truncation_and_expanded_struct_padding()
    {
        var bytes = Convert.FromHexString(Packed);
        for (var length = 0; length < bytes.Length; length++)
            Assert.False(OriginalMoveBaseCodec.TryDecodeRequest(bytes.AsSpan(0, length), out _));
        Assert.False(OriginalMoveBaseCodec.TryDecodeRequest([.. bytes, 0, 0], out _));
        bytes[1] = 1;
        Assert.False(OriginalMoveBaseCodec.TryDecodeRequest(bytes, out _));
    }
}
