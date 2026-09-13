using System.Buffers.Binary;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalChangedAuthorityCodecTests
{
    [Fact]
    public void Notification_uses_packed_unit_ids_not_native_padding()
    {
        var frame = OriginalChangedAuthorityCodec.Encode(0x12345678, [0x11223344, 0x89abcdef]);
        Assert.Equal("043912345678021122334489ABCDEF",
            Convert.ToHexString(frame.AsSpan(OriginalLoginCodec.MessageCodeSize)));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(32)]
    public void Native_supported_counts_have_exact_wire_length(int count)
    {
        var ids = Enumerable.Range(1, count).Select(x => (uint)x).ToArray();
        var frame = OriginalChangedAuthorityCodec.Encode(17, ids);
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        Assert.Equal(7 + 4 * count, payload.Length);
        Assert.Equal((byte)count, payload[6]);
        if (count != 0)
            Assert.Equal(32u, BinaryPrimitives.ReadUInt32BigEndian(payload[^4..]));
    }

    [Fact]
    public void Count_above_native_capacity_is_rejected_before_encoding()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            OriginalChangedAuthorityCodec.Encode(17, new uint[33]));
    }

    [Fact]
    public void Missing_unit_collection_is_rejected()
    {
        Assert.Throws<ArgumentNullException>(() => OriginalChangedAuthorityCodec.Encode(17, null!));
    }
}
