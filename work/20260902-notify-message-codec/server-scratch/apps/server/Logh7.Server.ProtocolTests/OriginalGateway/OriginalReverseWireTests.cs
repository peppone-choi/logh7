using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalReverseWireTests
{
    // E031/E032: real wire shape, not the padded 0x114-byte client structure.
    private static byte[] Pair => Convert.FromHexString(
        "0403" + "01020304" + "11223344" + "89ABCDEF" + "02" +
        "00000002" + "40000000" + "7F000001" + "3F800000" + "A5");

    [Fact]
    public void Reverse_decodes_two_unit_float_choices_and_keeps_the_separate_tail_byte()
    {
        var payload = Pair;
        var before = (byte[])payload.Clone();
        Assert.True(OriginalTacticalCommandCodec.TryDecodeReverseShipCommand(payload, out var command));
        Assert.Equal(0x01020304u, command.Time);
        Assert.Equal(0x11223344u, command.Wait);
        Assert.Equal(0x89ABCDEFu, command.Order);
        Assert.Equal((byte)0xA5, command.Direction);
        Assert.Collection(command.Units,
            unit => { Assert.Equal(2u, unit.UnitId); Assert.Equal(2f, unit.Direction); },
            unit => { Assert.Equal(0x7F000001u, unit.UnitId); Assert.Equal(1f, unit.Direction); });
        Assert.Equal(before, payload);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(32)]
    public void Reverse_wire_accepts_parser_count_bounds_without_granting_gameplay_authority(int count)
    {
        var header = "0403" + "00000000" + "00000000" + "00000001" + count.ToString("X2");
        var payload = Convert.FromHexString(header + string.Concat(Enumerable.Repeat("000000023F800000", count)) + "FF");
        Assert.True(OriginalTacticalCommandCodec.TryDecodeReverseShipCommand(payload, out var command));
        Assert.Equal(count, command.Units.Count);
        Assert.Equal((byte)255, command.Direction);
    }

    [Fact]
    public void Reverse_rejects_every_truncation_including_only_the_missing_tail()
    {
        var payload = Pair;
        for (var size = 0; size < payload.Length; size++)
        {
            Assert.False(OriginalTacticalCommandCodec.TryDecodeReverseShipCommand(payload.AsSpan(0, size), out var command));
            Assert.Null(command.Units);
        }
    }

    [Fact]
    public void Reverse_rejects_trailing_data_wrong_type_and_count_above_32()
    {
        Assert.False(OriginalTacticalCommandCodec.TryDecodeReverseShipCommand([.. Pair, 0], out _));
        var wrong = Pair;
        wrong[1] = 4;
        Assert.False(OriginalTacticalCommandCodec.TryDecodeReverseShipCommand(wrong, out _));
        var oversized = Convert.FromHexString("040300000000000000000000000021" +
            string.Concat(Enumerable.Repeat("000000023F800000", 33)) + "00");
        Assert.False(OriginalTacticalCommandCodec.TryDecodeReverseShipCommand(oversized, out _));
    }

    [Theory]
    [InlineData(0x7FC00000u)]
    [InlineData(0x7F800000u)]
    [InlineData(0xFF800000u)]
    public void Reverse_rejects_nonfinite_unit_direction_before_authority(uint bits)
    {
        var payload = Convert.FromHexString("04030000000000000000000000000100000002" + bits.ToString("X8") + "00");
        Assert.False(OriginalTacticalCommandCodec.TryDecodeReverseShipCommand(payload, out var command));
        Assert.Null(command.Units);
    }

    [Theory]
    [InlineData(0f, "00000000")]
    [InlineData(1.5f, "3FC00000")]
    [InlineData(-1.5f, "BFC00000")]
    [InlineData(7f, "40E00000")]
    [InlineData(-7f, "C0E00000")]
    public void Turned_encodes_original_time_unit_and_signed_float_without_normalizing(float direction, string bits)
    {
        var frame = OriginalTacticalCommandCodec.EncodeTurnedNotification(
            new OriginalTacticalTurnedNotification(0x01020304, 0x89ABCDEF, direction));
        Assert.Equal("00000000" + "0424" + "01020304" + "89ABCDEF" + bits, Convert.ToHexString(frame));
    }

    [Theory]
    [InlineData(float.NaN)]
    [InlineData(float.PositiveInfinity)]
    [InlineData(float.NegativeInfinity)]
    public void Turned_does_not_send_nonfinite_target_angles(float direction)
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => OriginalTacticalCommandCodec.EncodeTurnedNotification(
            new OriginalTacticalTurnedNotification(1, 2, direction)));
    }
}
