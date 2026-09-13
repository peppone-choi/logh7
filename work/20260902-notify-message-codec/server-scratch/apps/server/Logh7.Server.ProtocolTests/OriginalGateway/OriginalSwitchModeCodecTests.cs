using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalSwitchModeCodecTests
{
    // Hand-transcribed from 00448EA0: no C++ alignment padding on the wire.
    private const string CommandHex =
        "0B06" + "01020304" + "11223344" + "5566" + "778899AA" + "BBCCDDEE" +
        "0005" + "02" + "10203040" + "50607080" + "90A0B0C0" + "D0E0F001" +
        "02" + "12345678" + "9ABCDEF0";

    [Fact]
    public void Request_fields_and_both_variable_arrays_follow_original_writer()
    {
        Assert.True(OriginalSwitchModeCodec.TryDecodeRequest(Convert.FromHexString(CommandHex), out var command));
        Assert.Equal(0x01020304u, command.Time);
        Assert.Equal(0x11223344u, command.ActorId);
        Assert.Equal((ushort)0x5566, command.Card);
        Assert.Equal(0x778899AAu, command.Pcp);
        Assert.Equal(0xBBCCDDEEu, command.Mcp);
        Assert.Equal((ushort)5, command.Mode);
        Assert.Equal(new uint[] { 0x10203040, 0x50607080 }, command.Units);
        Assert.Equal(0x90A0B0C0u, command.Spot);
        Assert.Equal(0xD0E0F001u, command.SpotOwner);
        Assert.Equal(new uint[] { 0x12345678, 0x9ABCDEF0 }, command.MoveCharacters);
    }

    [Fact]
    public void Departure_with_no_explicit_units_or_moved_characters_is_valid()
    {
        var bytes = Convert.FromHexString(
            "0B06" + "00000000" + "00000002" + "0000" + "00000000" + "00000000" +
            "0004" + "00" + "00000000" + "00000000" + "00");
        Assert.True(OriginalSwitchModeCodec.TryDecodeRequest(bytes, out var command));
        Assert.Equal(2u, command.ActorId);
        Assert.Equal((ushort)4, command.Mode);
        Assert.Empty(command.Units);
        Assert.Empty(command.MoveCharacters);
    }

    [Fact]
    public void Request_rejects_every_truncation_trailing_data_wrong_type_and_count_overflow()
    {
        var good = Convert.FromHexString(CommandHex);
        for (var size = 0; size < good.Length; size++)
            Assert.False(OriginalSwitchModeCodec.TryDecodeRequest(good.AsSpan(0, size), out _));
        Assert.False(OriginalSwitchModeCodec.TryDecodeRequest([.. good, 0], out _));
        var wrongType = (byte[])good.Clone();
        wrongType[1] = 7;
        Assert.False(OriginalSwitchModeCodec.TryDecodeRequest(wrongType, out _));
        var tooManyUnits = new byte[32 + 71 * 4];
        tooManyUnits[0] = 0x0b;
        tooManyUnits[1] = 6;
        tooManyUnits[22] = 71;
        Assert.False(OriginalSwitchModeCodec.TryDecodeRequest(tooManyUnits, out _));
        var tooManyCharacters = new byte[32 + 11 * 4];
        tooManyCharacters[0] = 0x0b;
        tooManyCharacters[1] = 6;
        tooManyCharacters[31] = 11;
        Assert.False(OriginalSwitchModeCodec.TryDecodeRequest(tooManyCharacters, out _));
    }

    [Fact]
    public void Request_accepts_original_maximum_counts_without_losing_last_entries()
    {
        var bytes = new byte[352];
        bytes[0] = 0x0b;
        bytes[1] = 6;
        bytes[22] = 70;
        bytes[302] = 99; // Last unit's last byte.
        bytes[311] = 10;
        bytes[351] = 88;
        Assert.True(OriginalSwitchModeCodec.TryDecodeRequest(bytes, out var command));
        Assert.Equal(70, command.Units.Count);
        Assert.Equal(99u, command.Units[69]);
        Assert.Equal(10, command.MoveCharacters.Count);
        Assert.Equal(88u, command.MoveCharacters[9]);
    }

    [Fact]
    public void Notification_matches_original_reader_including_four_float_fields_and_tail()
    {
        var notification = new OriginalChangeModeNotification(
            0x01020304, 5, 0x11223344,
            [new(0x55667788, 1.5f, -2f, 3.25f, -0.5f)], 0x99AABBCC, 0xDDEEFF00);
        var bytes = OriginalSwitchModeCodec.EncodeNotification(notification);
        Assert.Equal(
            "00000000" + "042F" + "01020304" + "05" + "11223344" + "01" +
            "55667788" + "3FC00000" + "C0000000" + "40500000" + "BF000000" +
            "99AABBCC" + "DDEEFF00", Convert.ToHexString(bytes));
    }

    [Fact]
    public void Notification_handles_empty_and_maximum_count_and_rejects_overflow()
    {
        Assert.Equal("00000000042F000000000400000000000000000000000000",
            Convert.ToHexString(OriginalSwitchModeCodec.EncodeNotification(new(0, 4, 0, [], 0, 0))));
        var maximum = Enumerable.Range(0, 32)
            .Select(i => new OriginalChangeModeUnit((uint)(i + 1), 0, 0, 0, 0)).ToArray();
        var encoded = OriginalSwitchModeCodec.EncodeNotification(new(0, 4, 0, maximum, 0, 0));
        Assert.Equal(664, encoded.Length);
        Assert.Equal((byte)32, encoded[15]);
        Assert.Equal("00000020", Convert.ToHexString(encoded.AsSpan(636, 4)));
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            OriginalSwitchModeCodec.EncodeNotification(new(0, 4, 0, [.. maximum, new(33, 0, 0, 0, 0)], 0, 0)));
        Assert.Throws<ArgumentNullException>(() =>
            OriginalSwitchModeCodec.EncodeNotification(new(0, 4, 0, null!, 0, 0)));
    }
}
