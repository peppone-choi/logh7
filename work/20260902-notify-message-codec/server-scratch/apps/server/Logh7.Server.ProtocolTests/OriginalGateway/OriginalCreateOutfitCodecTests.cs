using Logh7.Server.OriginalGateway;
using Xunit;
namespace Logh7.Server.ProtocolTests.OriginalGateway;
public sealed class OriginalCreateOutfitCodecTests
{
    private static byte[] Fixture()=>Convert.FromHexString(
        "090301020304112233440100000005000000060000000702010038FE012C01001302FFFD00000064000000C8010203050203000400100102030405060708090A");
    [Fact]
    public void Decodes_literal_signed_transfers_and_nested_outfit_without_strategy_field()
    {
        Assert.True(OriginalCreateOutfitCodec.TryDecode(Fixture(),out var c));
        Assert.Equal(0x01020304u,c.Time); Assert.Equal(0x11223344u,c.ActorId);
        Assert.Equal((byte)1,c.Mode); Assert.Equal(5u,c.Pcp); Assert.Equal(6u,c.Mcp);
        Assert.Equal(7u,c.BaseId); Assert.Equal((byte)2,c.Kind);
        Assert.Equal(new OriginalReorganizationShip(56,-2,300),Assert.Single(c.Ships));
        Assert.Equal(new OriginalReorganizationTroop(19,2,-3),Assert.Single(c.Troops));
        Assert.Equal(100u,c.MaxTroop); Assert.Equal(200u,c.MaxCrew);
        Assert.Equal(new OriginalInformationOutfit(0x01020305,2,3,0,4,16,0,1,2,3,4,5,6,7,8,9,10),c.Outfit);
    }
    [Fact]
    public void Encodes_literal_without_serializing_absent_strategy_id()
    {
        var c=new OriginalCreateOutfitCommand(0x01020304,0x11223344,1,5,6,7,2,
            [new(56,-2,300)],[new(19,2,-3)],100,200,
            new(0x01020305,2,3,0,4,16,999,1,2,3,4,5,6,7,8,9,10));
        Assert.Equal(new byte[]{0,0,0,0}.Concat(Fixture()),OriginalCreateOutfitCodec.Encode(c));
        Assert.Throws<ArgumentException>(()=>OriginalCreateOutfitCodec.Encode(c with { Ships=null! }));
        Assert.Throws<ArgumentException>(()=>OriginalCreateOutfitCodec.Encode(c with { Ships=new OriginalReorganizationShip[100] }));
        Assert.Throws<ArgumentException>(()=>OriginalCreateOutfitCodec.Encode(c with { Troops=new OriginalReorganizationTroop[25] }));
    }
    [Fact]
    public void Rejects_truncation_trailing_bytes_and_excess_counts()
    {
        var bytes=Fixture();
        for(var n=0;n<bytes.Length;n++) Assert.False(OriginalCreateOutfitCodec.TryDecode(bytes.AsSpan(0,n),out _));
        Assert.False(OriginalCreateOutfitCodec.TryDecode([..bytes,0],out _));
        bytes[24]=100; Assert.False(OriginalCreateOutfitCodec.TryDecode(bytes,out _));
        bytes=Fixture(); bytes[30]=25; Assert.False(OriginalCreateOutfitCodec.TryDecode(bytes,out _));
    }
}
