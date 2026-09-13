using System.Buffers.Binary;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalCharacterCommandPointTests
{
    [Theory]
    [InlineData("", 55)]
    [InlineData("保存艦", 61)]
    [InlineData("ABCDEFGHIJKLM", 81)]
    public void Character_response_carries_authority_points_after_variable_flagship_name(string name, int offset)
    {
        var character = new OriginalCreateCharacterCommand(4, 7, 2, 0, 0, "Last", "First",
            20, 1, 1, 5, [1,2,3,4,5,6,7,8], 0, 0, 0, 20, 0, 0, name, 0, []);
        var frame = OriginalWorldEntryCodec.EncodeCharacter(7, 9, 3, character,
            pcp: 0x12345678, mcp: uint.MaxValue);
        // Original packed layout: prefix6 + fixed36 + pstr(1+2N)
        // + strategy/coup_conduct/coup12, then PCP and MCP u32 big-endian.
        Assert.Equal(0x12345678u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(offset)));
        Assert.Equal(uint.MaxValue, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(offset + 4)));
        var zero = OriginalWorldEntryCodec.EncodeCharacter(7, 9, 3, character);
        Assert.Equal(zero.Length, frame.Length);
        Assert.Equal(zero[..offset], frame[..offset]);
        Assert.Equal(zero[(offset + 8)..], frame[(offset + 8)..]);
        Assert.Equal(new byte[8], zero[offset..(offset + 8)]);
    }
}
