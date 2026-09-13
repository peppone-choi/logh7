using System.Buffers.Binary;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalDepartureMenuTests
{
    // Missing either catalog means the native card panel cannot consistently
    // select command60 and construct its flow. Parse packed wire independently.
    [Theory]
    [InlineData(0x0305)]
    [InlineData(0x0307)]
    public void Authored_commander_exposes_departure_once_in_both_native_catalogs(int type)
    {
        var frame=type==0x0305 ? OriginalWorldBootstrapCodec.EncodeStaticCards()
            : OriginalWorldBootstrapCodec.EncodeStaticCardCommands();
        Assert.Equal((ushort)type,BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)));
        var records=BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(6));
        var cursor=8;
        ushort[]? commander=null;
        for(var row=0;row<records;row++)
        {
            var card=BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(cursor));
            var count=frame[cursor+(type==0x0305?18:2)];
            Assert.InRange(count,(byte)0,(byte)24);
            cursor+=type==0x0305?19:3;
            var commands=new ushort[count];
            for(var entry=0;entry<count;entry++)
            {
                commands[entry]=BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(cursor));
                cursor+=type==0x0305?2:8;
            }
            if(card==39) commander=commands;
            else Assert.DoesNotContain((ushort)60,commands);
        }
        Assert.Equal(frame.Length,cursor);
        Assert.NotNull(commander);
        Assert.Single(commander,id=>id==60);
        Assert.Contains((ushort)43,commander);
        Assert.Contains((ushort)5,commander);
        Assert.Contains((ushort)0,commander);
    }
}
