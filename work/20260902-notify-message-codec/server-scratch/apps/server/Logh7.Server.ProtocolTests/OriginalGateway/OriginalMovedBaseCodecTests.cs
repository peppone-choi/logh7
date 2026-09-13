using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalMovedBaseCodecTests
{
    [Fact]
    public void Strategic_notification_packs_mode_base_spot_and_affected_character_ids()
    {
        var frame=OriginalMovedBaseCodec.Encode(new(
            0x01020304,0x11223344,0x55667788,0x0004,0x99AABBCC,0xDDEEFF00,
            [0x10203040,0x50607080]));
        // Independently transcribed reader0044BEE0/logger0044C310 layout.
        Assert.Equal("00000000"+"0B0B"+"01020304"+"11223344"+"55667788"+"0004"+
            "99AABBCC"+"DDEEFF00"+"02"+"10203040"+"50607080",Convert.ToHexString(frame));
    }

    [Fact]
    public void Empty_and_ten_character_notifications_respect_original_lengths()
    {
        var empty=OriginalMovedBaseCodec.Encode(new(0,2,0,4,0,0,[]));
        Assert.Equal(29,empty.Length);
        Assert.Equal((byte)0,empty[28]);
        var maximum=OriginalMovedBaseCodec.Encode(new(0,2,0,5,0,0,
            [1,2,3,4,5,6,7,8,9,0x12345678]));
        Assert.Equal(69,maximum.Length);
        Assert.Equal((byte)10,maximum[28]);
        Assert.Equal("12345678",Convert.ToHexString(maximum.AsSpan(65,4)));
    }

    [Fact]
    public void Missing_or_oversized_character_lists_cannot_be_encoded()
    {
        Assert.Throws<ArgumentNullException>(()=>OriginalMovedBaseCodec.Encode(new(0,2,0,4,0,0,null!)));
        Assert.Throws<ArgumentOutOfRangeException>(()=>OriginalMovedBaseCodec.Encode(new(0,2,0,4,0,0,
            [1,2,3,4,5,6,7,8,9,10,11])));
    }
}
