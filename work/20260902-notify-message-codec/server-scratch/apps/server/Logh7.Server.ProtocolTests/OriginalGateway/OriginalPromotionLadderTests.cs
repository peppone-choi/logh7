using System.Buffers.Binary;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalPromotionLadderTests
{
    [Theory]
    [InlineData(1, 0)]
    [InlineData(2, 1)]
    [InlineData(20, 1)]
    public void Ladder_omits_step_when_no_higher_rank_exists(ushort rank, byte count)
    {
        var frames = OriginalSimpleRankCodec.EncodePromotionTransaction(rank, new byte[29]);
        Assert.Equal(3, frames.Count);
        Assert.Equal(0x1209, BinaryPrimitives.ReadUInt16BigEndian(frames[1].AsSpan(4)));
        Assert.Equal(count, frames[1][6]);
        Assert.Equal(7 + count * 2, frames[1].Length);
        if (count != 0)
            Assert.Equal(rank, BinaryPrimitives.ReadUInt16LittleEndian(frames[1].AsSpan(7)));
        Assert.Equal(0x1201, BinaryPrimitives.ReadUInt16BigEndian(frames[2].AsSpan(4)));
    }
}
