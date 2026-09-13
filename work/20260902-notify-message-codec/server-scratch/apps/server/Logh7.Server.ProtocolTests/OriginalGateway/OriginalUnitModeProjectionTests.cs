using System.Buffers.Binary;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalUnitModeProjectionTests
{
    [Theory]
    [InlineData(4)]
    [InlineData(5)]
    [InlineData(6)]
    [InlineData(7)]
    public void Unit_refresh_keeps_mode_and_base_independent_instead_of_resetting_mode(byte mode)
    {
        // 0325: count2, id4, kind2, mode1. Two records prevent a hardcoded
        // per-frame mode or accidentally moving the base offset from passing.
        var first = new OriginalInformationUnitProjection(
            2, 102, 7, 100, 3, 1, 40, 50, 9, Kind: 3, Mode: mode);
        var second = new OriginalInformationUnitProjection(
            99, 101, 0, 90, 0, 0, 60, 70, 8, Kind: 93, Mode: 0);
        var frame = OriginalWorldEntryCodec.EncodeUnits([first, second]);
        Assert.Equal(92, frame.Length);
        Assert.Equal((ushort)0x325, BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)));
        Assert.Equal((ushort)2, BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(6)));
        Assert.Equal(mode, frame[14]);
        Assert.Equal(7u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(28)));
        Assert.Equal((byte)0, frame[56]);
        Assert.Equal(0u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(70)));
        var baseline = OriginalWorldEntryCodec.EncodeUnits([first with { Mode = 0 }, second]);
        frame[14] = 0;
        Assert.Equal(baseline, frame);
    }
}
