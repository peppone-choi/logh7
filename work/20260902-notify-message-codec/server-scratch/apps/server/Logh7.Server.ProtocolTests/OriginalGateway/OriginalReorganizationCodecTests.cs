using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalReorganizationCodecTests
{
    // Hand-derived from original 00551A80/00555EB0, not from the codec.
    // 0C02; time,id,mode,pcp,mcp,base,outfit,kind; two ships; one troop; maxTroop,maxCrew,supplies.
    private const string Packet =
        "0C02" + "01020304" + "11223344" + "02" + "00000050" + "00000060" +
        "00000002" + "00000027" + "FF" + "02" +
        "1234" + "FE" + "012C" + "000C" + "03" + "0001" +
        "01" + "0089" + "04" + "FFFE" + "FFFFFF9C" + "0000012C" + "00010203";

    [Fact]
    public void DecodesPackedFieldsAndSignedTransfersWithoutExpandedPadding()
    {
        Assert.True(OriginalReorganizationCodec.TryDecode(Convert.FromHexString(Packet), out var command));
        Assert.Equal(0x01020304u, command.Time);
        Assert.Equal(0x11223344u, command.ActorId);
        Assert.Equal((byte)2, command.Mode);
        Assert.Equal(80u, command.Pcp);
        Assert.Equal(96u, command.Mcp);
        Assert.Equal(2u, command.BaseId);
        Assert.Equal(39u, command.OutfitId);
        Assert.Equal((byte)255, command.Kind);
        Assert.Equal(new OriginalReorganizationShip(0x1234, -2, 300), command.Ships[0]);
        Assert.Equal(new OriginalReorganizationShip(12, 3, 1), command.Ships[1]);
        Assert.Equal(new OriginalReorganizationTroop(137, 4, -2), Assert.Single(command.Troops));
        Assert.Equal(-100, command.MaxTroop);
        Assert.Equal(300, command.MaxCrew);
        Assert.Equal(66051u, command.Supplies);
    }

    [Fact]
    public void EncodesLiteralWireLayoutWithOriginalMessageCodePrefix()
    {
        var command = new OriginalReorganizationCommand(0x01020304, 0x11223344, 2,
            80, 96, 2, 39, 255,
            [new(0x1234, -2, 300), new(12, 3, 1)],
            [new(137, 4, -2)], -100, 300, 66051);
        Assert.Equal("00000000" + Packet,
            Convert.ToHexString(OriginalReorganizationCodec.Encode(command)));
    }

    [Fact]
    public void AcceptsEmptyTransferListsWithoutTreatingThemAsInvalidCommandModes()
    {
        var payload = new byte[42];
        payload[0] = 0x0c; payload[1] = 2;
        Assert.True(OriginalReorganizationCodec.TryDecode(payload, out var command));
        Assert.Empty(command.Ships);
        Assert.Empty(command.Troops);
        Assert.Equal(46, OriginalReorganizationCodec.Encode(command).Length);
    }

    [Fact]
    public void RejectsEveryTruncationTrailingBytesAndOtherMessageType()
    {
        var payload = Convert.FromHexString(Packet);
        for (var length = 0; length < payload.Length; length++)
            Assert.False(OriginalReorganizationCodec.TryDecode(payload.AsSpan(0, length), out _));
        Assert.False(OriginalReorganizationCodec.TryDecode([..payload, 0], out _));
        payload[1] = 5;
        Assert.False(OriginalReorganizationCodec.TryDecode(payload, out _));
    }

    [Fact]
    public void AcceptsOriginalMaximumCountsAndSignedExtremes()
    {
        var payload = new byte[657]; // 2-byte type + 40-byte body + 5*(99+24)
        payload[0] = 0x0c; payload[1] = 2;
        payload[28] = 99;
        for (var i = 0; i < 99; i++)
        {
            var at = 29 + i * 5;
            payload[at] = 0xff; payload[at+1] = 0xff;
            payload[at+2] = i % 2 == 0 ? (byte)0x80 : (byte)0x7f;
            payload[at+3] = 0xff; payload[at+4] = 0xff;
        }
        payload[524] = 24;
        for (var i = 0; i < 24; i++)
        {
            var at = 525 + i * 5;
            payload[at+2] = 0xff;
            payload[at+3] = i % 2 == 0 ? (byte)0x80 : (byte)0x7f;
            payload[at+4] = i % 2 == 0 ? (byte)0x00 : (byte)0xff;
        }
        Assert.True(OriginalReorganizationCodec.TryDecode(payload, out var command));
        Assert.Equal(99, command.Ships.Count);
        Assert.Equal(24, command.Troops.Count);
        Assert.Equal(sbyte.MinValue, command.Ships[0].UnitNumber);
        Assert.Equal(sbyte.MaxValue, command.Ships[1].UnitNumber);
        Assert.Equal(ushort.MaxValue, command.Ships[0].BoatNumber);
        Assert.Equal(short.MinValue, command.Troops[0].UnitNumber);
        Assert.Equal(short.MaxValue, command.Troops[1].UnitNumber);
        Assert.Equal(payload, OriginalReorganizationCodec.Encode(command)[4..]);
    }

    [Fact]
    public void RejectsCountsBeyondOriginalArraysBeforeReadingEntries()
    {
        var payload = new byte[42];
        payload[0] = 0x0c; payload[1] = 2;
        payload[28] = 100;
        Assert.False(OriginalReorganizationCodec.TryDecode(payload, out _));
        payload[28] = 0; payload[29] = 25;
        Assert.False(OriginalReorganizationCodec.TryDecode(payload, out _));
    }

    [Fact]
    public void EncoderRejectsNullAndOverCapacityLists()
    {
        var command = new OriginalReorganizationCommand(0,0,0,0,0,0,0,0,[],[],0,0,0);
        Assert.Throws<ArgumentException>(() => OriginalReorganizationCodec.Encode(
            command with { Ships = new OriginalReorganizationShip[100] }));
        Assert.Throws<ArgumentException>(() => OriginalReorganizationCodec.Encode(
            command with { Troops = new OriginalReorganizationTroop[25] }));
        Assert.Throws<ArgumentException>(() => OriginalReorganizationCodec.Encode(command with { Ships = null! }));
        Assert.Throws<ArgumentException>(() => OriginalReorganizationCodec.Encode(command with { Troops = null! }));
    }
}
