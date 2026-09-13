using System.Buffers.Binary;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalPowerDistributionCodecTests
{
    [Fact]
    public void Static_power_distribution_uses_the_original_compact_layout()
    {
        Assert.True(OriginalWorldBootstrapCodec.TryEncodeResponse(
            Convert.FromHexString("0308"),
            out var frame));

        // Input_ResponseStaticInformationPowerDistribution::input_from_stream
        // (0x00410370) consumes 1,370 wire bytes. The expanded client object is
        // 0x55c bytes only because it contains two bytes of alignment padding.
        Assert.Equal(1_376, frame.Length);
        Assert.Equal("000000000309", Convert.ToHexString(frame.AsSpan(0, 6)));

        Assert.Equal(BitConverter.SingleToUInt32Bits(1), ReadUInt32(frame, 6));
        Assert.Equal((byte)1, frame[50]);
        Assert.Equal((byte)1, frame[51]);
        Assert.Equal(BitConverter.SingleToUInt32Bits(1), ReadUInt32(frame, 52));
        Assert.Equal((uint)100, ReadUInt32(frame, 68));
        Assert.Equal((ushort)100, ReadUInt16(frame, BeamValueOffset(3, 7)));
        Assert.Equal((ushort)100, ReadUInt16(frame, GunValueOffset(10, 15)));
    }

    private static int BeamValueOffset(int row, int column) =>
        6 + (11 * sizeof(float)) + 2 + (4 * sizeof(float)) +
        (11 * 9 * sizeof(uint)) + ((row * 20 + column) * sizeof(ushort));

    private static int GunValueOffset(int row, int column) =>
        6 + (11 * sizeof(float)) + 2 + (4 * sizeof(float)) +
        (11 * 9 * sizeof(uint)) + (14 * 20 * sizeof(ushort)) +
        ((row * 16 + column) * sizeof(ushort));

    private static ushort ReadUInt16(byte[] bytes, int offset) =>
        BinaryPrimitives.ReadUInt16BigEndian(bytes.AsSpan(offset));

    private static uint ReadUInt32(byte[] bytes, int offset) =>
        BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(offset));
}
