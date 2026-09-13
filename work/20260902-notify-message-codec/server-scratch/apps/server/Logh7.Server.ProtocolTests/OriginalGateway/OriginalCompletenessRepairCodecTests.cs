using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalCompletenessRepairCodecTests
{
    // Independent literal from v238 writer/logger: four u32, count, three u32 per entry.
    private const string Packet = "0C000102030411223344000000508000000002" +
        "000000020000011300010203" + "7E000101FFFFFFFF00000000";

    [Fact]
    public void DecodesEveryHeaderAndResultFieldWithoutExpandedPadding()
    {
        Assert.True(OriginalCompletenessRepairCodec.TryDecode(Convert.FromHexString(Packet), out var value));
        Assert.Equal(0x01020304u, value.Time);
        Assert.Equal(0x11223344u, value.ActorId);
        Assert.Equal(80u, value.Pcp);
        Assert.Equal(0x80000000u, value.Mcp);
        Assert.Equal(2, value.Ships.Count);
        Assert.Equal(new OriginalCompletenessRepairShip(2,275,66051), value.Ships[0]);
        Assert.Equal(new OriginalCompletenessRepairShip(0x7e000101,uint.MaxValue,0), value.Ships[1]);
    }

    [Fact]
    public void EncodesLiteralLayoutIncludingMessageCodePrefix()
    {
        var value = new OriginalCompletenessRepairCommand(0x01020304,0x11223344,80,0x80000000,
            [new(2,275,66051),new(0x7e000101,uint.MaxValue,0)]);
        Assert.Equal("00000000"+Packet, Convert.ToHexString(OriginalCompletenessRepairCodec.Encode(value)));
    }

    [Theory]
    [InlineData(0,19)]
    [InlineData(70,859)]
    public void AcceptsEmptyAndMaximumLists(int count,int length)
    {
        var payload=new byte[length];payload[0]=0x0c;payload[18]=(byte)count;
        if(count>0) Array.Fill(payload,(byte)255,19,length-19);
        Assert.True(OriginalCompletenessRepairCodec.TryDecode(payload,out var value));
        Assert.Equal(count,value.Ships.Count);
        if(count>0) Assert.All(value.Ships,ship=>Assert.Equal(new OriginalCompletenessRepairShip(uint.MaxValue,uint.MaxValue,uint.MaxValue),ship));
        Assert.Equal(payload,OriginalCompletenessRepairCodec.Encode(value)[4..]);
    }

    [Fact]
    public void RejectsEveryTruncationTrailingBytesAndWrongType()
    {
        var payload=Convert.FromHexString(Packet);
        for(var length=0;length<payload.Length;length++)
            Assert.False(OriginalCompletenessRepairCodec.TryDecode(payload.AsSpan(0,length),out _));
        Assert.False(OriginalCompletenessRepairCodec.TryDecode([..payload,0],out _));
        payload[1]=2;
        Assert.False(OriginalCompletenessRepairCodec.TryDecode(payload,out _));
    }

    [Fact]
    public void RejectsOverCapacityCountEvenWithMatchingLength()
    {
        var payload=new byte[871];payload[0]=0x0c;payload[18]=71;
        Assert.False(OriginalCompletenessRepairCodec.TryDecode(payload,out _));
    }

    [Fact]
    public void EncoderRejectsNullAndOverCapacityLists()
    {
        var value=new OriginalCompletenessRepairCommand(0,0,0,0,[]);
        Assert.Throws<ArgumentException>(()=>OriginalCompletenessRepairCodec.Encode(value with {Ships=null!}));
        Assert.Throws<ArgumentException>(()=>OriginalCompletenessRepairCodec.Encode(value with {Ships=new OriginalCompletenessRepairShip[71]}));
    }
}
