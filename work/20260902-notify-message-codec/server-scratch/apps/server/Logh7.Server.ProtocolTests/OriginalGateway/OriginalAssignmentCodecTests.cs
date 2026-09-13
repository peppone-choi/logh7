using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalAssignmentCodecTests
{
    [Fact]
    public void Encodes_exact_independent_fixture_with_application_prefix()
    {
        var value = new OriginalAssignmentCommand(1, 2, 3, 4, 5, 6, 7,
            [new(56, -2, 300)], [new(19, 2, -3)], -8,
            new([new(32, 3, 900)], [new(18, 1, 4)], 9),
            new([new(119, 2, 600)], [new(17, 3, 5)], 10));
        Assert.Equal(new byte[4].Concat(Fixture()).ToArray(), OriginalAssignmentCodec.Encode(value));
    }

    [Fact]
    public void Empty_arrays_have_exact_minimum_frame_and_missing_arrays_are_rejected()
    {
        var value = new OriginalAssignmentCommand(0, 0, 0, 0, 0, 0, 0,
            [], [], 0, new([], [], 0), new([], [], 0));
        var frame = OriginalAssignmentCodec.Encode(value);
        Assert.Equal(49, frame.Length);
        Assert.True(OriginalAssignmentCodec.TryDecode(frame.AsSpan(4), out _));
        Assert.Throws<ArgumentException>(() => OriginalAssignmentCodec.Encode(default));
        Assert.Throws<ArgumentException>(() => OriginalAssignmentCodec.Encode(value with { BaseResult = default }));
        Assert.Throws<ArgumentException>(() => OriginalAssignmentCodec.Encode(value with { OutfitResult = default }));
    }

    [Fact]
    public void All_arrays_accept_capacity_and_reject_one_over_capacity()
    {
        var stock = new OriginalAssignmentStock(
            Enumerable.Repeat(new OriginalWarehouseShip(65535, 255, 65535), 99).ToArray(),
            Enumerable.Repeat(new OriginalWarehouseTroop(65535, 255, 65535), 24).ToArray(), uint.MaxValue);
        var value = new OriginalAssignmentCommand(uint.MaxValue, 2, 3, 4, 5, 6, 255,
            Enumerable.Repeat(new OriginalReorganizationShip(65535, -128, 65535), 99).ToArray(),
            Enumerable.Repeat(new OriginalReorganizationTroop(65535, 255, short.MinValue), 24).ToArray(),
            int.MinValue, stock, stock);
        var frame = OriginalAssignmentCodec.Encode(value);
        Assert.Equal(49 + 5 * 369, frame.Length);
        Assert.True(OriginalAssignmentCodec.TryDecode(frame.AsSpan(4), out var decoded));
        Assert.Equal(value.Ships.ToArray(), decoded.Ships.ToArray());
        Assert.Equal(value.Troops.ToArray(), decoded.Troops.ToArray());
        Assert.Equal(int.MinValue, decoded.Supplies);
        Assert.Equal(uint.MaxValue, decoded.BaseResult.Supplies);
        Assert.Equal(stock.Ships.ToArray(), decoded.OutfitResult.Ships.ToArray());
        Assert.Equal(stock.Troops.ToArray(), decoded.OutfitResult.Troops.ToArray());
        foreach (var invalid in new[] {
            value with { Ships = [..value.Ships, default] },
            value with { Troops = [..value.Troops, default] },
            value with { BaseResult = stock with { Ships = [..stock.Ships, default] } },
            value with { BaseResult = stock with { Troops = [..stock.Troops, default] } },
            value with { OutfitResult = stock with { Ships = [..stock.Ships, default] } },
            value with { OutfitResult = stock with { Troops = [..stock.Troops, default] } }
        }) Assert.Throws<ArgumentException>(() => OriginalAssignmentCodec.Encode(invalid));
    }

    // Independent static-derived fixture: six distinct arrays, not encoder output.
    private static byte[] Fixture() => Convert.FromHexString(
        "0C0B00000001000000020000000300000004000000050000000607" +
        "010038FE012C" + "01001302FFFD" + "FFFFFFF8" +
        "010020030384" + "010012010004" + "00000009" +
        "010077020258" + "010011030005" + "0000000A");

    [Fact]
    public void Decodes_changes_and_both_result_warehouses_independently()
    {
        Assert.True(OriginalAssignmentCodec.TryDecode(Fixture(), out var value));
        Assert.Equal(1u, value.Time);
        Assert.Equal(2u, value.ActorId);
        Assert.Equal(3u, value.Pcp);
        Assert.Equal(4u, value.Mcp);
        Assert.Equal(5u, value.BaseId);
        Assert.Equal(6u, value.OutfitId);
        Assert.Equal((byte)7, value.Kind);
        Assert.Equal(new OriginalReorganizationShip(56, -2, 300), Assert.Single(value.Ships));
        Assert.Equal(new OriginalReorganizationTroop(19, 2, -3), Assert.Single(value.Troops));
        Assert.Equal(-8, value.Supplies);
        Assert.Equal(new OriginalWarehouseShip(32, 3, 900), Assert.Single(value.BaseResult.Ships));
        Assert.Equal(new OriginalWarehouseTroop(18, 1, 4), Assert.Single(value.BaseResult.Troops));
        Assert.Equal(9u, value.BaseResult.Supplies);
        Assert.Equal(new OriginalWarehouseShip(119, 2, 600), Assert.Single(value.OutfitResult.Ships));
        Assert.Equal(new OriginalWarehouseTroop(17, 3, 5), Assert.Single(value.OutfitResult.Troops));
        Assert.Equal(10u, value.OutfitResult.Supplies);
    }

    [Fact]
    public void Rejects_every_truncation_trailing_bytes_wrong_opcode_and_each_excess_count()
    {
        var bytes = Fixture();
        for (var size = 0; size < bytes.Length; size++)
            Assert.False(OriginalAssignmentCodec.TryDecode(bytes.AsSpan(0, size), out _));
        Assert.False(OriginalAssignmentCodec.TryDecode([.. bytes, 0], out _));
        bytes[1] = 0x0c;
        Assert.False(OriginalAssignmentCodec.TryDecode(bytes, out _));
        foreach (var (offset, count) in new[] { (27, 100), (33, 25), (43, 100), (49, 25), (59, 100), (65, 25) })
        {
            bytes = Fixture();
            bytes[offset] = (byte)count;
            Assert.False(OriginalAssignmentCodec.TryDecode(bytes, out _));
        }
    }
}
