using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalWarehouseCodecTests
{
    // Original reader0041A870 and logger0041AFF0, independently hand-packed.
    // Counts occupy one byte; row padding in expanded0x300 is not wire data.
    private const string Response = "0327" + "01020304" + "11223344" + "55667788" +
        "02" + "1234FE012C" + "000C030001" +
        "01" + "008904FFFE" + "00010203" + "04050607" + "08090A0B";

    [Fact]
    public void Request_round_trips_exact_base_and_outfit_without_response_index()
    {
        Assert.True(OriginalWarehouseCodec.TryDecodeRequest(
            Convert.FromHexString("03260102030411223344"), out var request));
        Assert.Equal(new OriginalWarehouseRequest(0x01020304, 0x11223344), request);
        Assert.Equal("0000000003260102030411223344",
            Convert.ToHexString(OriginalWarehouseCodec.EncodeRequest(request)));
    }

    [Fact]
    public void Response_decodes_unsigned_stock_not_signed_command_deltas()
    {
        Assert.True(OriginalWarehouseCodec.TryDecodeResponse(Convert.FromHexString(Response), out var r));
        Assert.Equal(0x01020304u, r.BaseId);
        Assert.Equal(0x11223344u, r.OutfitId);
        Assert.Equal(0x55667788u, r.Index);
        Assert.Equal(new OriginalWarehouseShip(0x1234, 254, 300), r.Ships[0]);
        Assert.Equal(new OriginalWarehouseShip(12, 3, 1), r.Ships[1]);
        Assert.Equal(new OriginalWarehouseTroop(137, 4, 65534), Assert.Single(r.Troops));
        Assert.Equal(66051u, r.Supplies);
        Assert.Equal(0x04050607u, r.Food);
        Assert.Equal(0x08090a0bu, r.Mineral);
    }

    [Fact]
    public void Response_encoding_matches_original_literal_without_expanded_padding()
    {
        var r = new OriginalWarehouseResponse(0x01020304, 0x11223344, 0x55667788,
            [new(0x1234, 254, 300), new(12, 3, 1)], [new(137, 4, 65534)],
            66051, 0x04050607, 0x08090a0b);
        Assert.Equal("00000000" + Response, Convert.ToHexString(OriginalWarehouseCodec.EncodeResponse(r)));
    }

    [Fact]
    public void Empty_base_warehouse_preserves_identity_and_is_not_an_absent_response()
    {
        var frame = OriginalWarehouseCodec.EncodeResponse(new(2, 0, 7, [], [], 0, 0, 0));
        Assert.Equal("0000000003270000000200000000000000070000000000000000000000000000",
            Convert.ToHexString(frame));
        Assert.True(OriginalWarehouseCodec.TryDecodeResponse(frame.AsSpan(4), out var r));
        Assert.Equal(2u, r.BaseId);
        Assert.Equal(0u, r.OutfitId);
        Assert.Empty(r.Ships);
        Assert.Empty(r.Troops);
    }

    [Fact]
    public void Maximum_original_counts_and_full_unsigned_resource_values_survive()
    {
        var r = new OriginalWarehouseResponse(1, 2, uint.MaxValue,
            Enumerable.Range(0, 99).Select(i => new OriginalWarehouseShip((ushort)i, 255, 65535)).ToArray(),
            Enumerable.Range(0, 24).Select(i => new OriginalWarehouseTroop((ushort)i, 255, 65535)).ToArray(),
            uint.MaxValue, uint.MaxValue, uint.MaxValue);
        var frame = OriginalWarehouseCodec.EncodeResponse(r);
        Assert.Equal(647, frame.Length);
        Assert.True(OriginalWarehouseCodec.TryDecodeResponse(frame.AsSpan(4), out var decoded));
        Assert.Equal(r.Ships, decoded.Ships);
        Assert.Equal(r.Troops, decoded.Troops);
        Assert.Equal(uint.MaxValue, decoded.Index);
        Assert.Equal(uint.MaxValue, decoded.Supplies);
        Assert.Equal(uint.MaxValue, decoded.Food);
        Assert.Equal(uint.MaxValue, decoded.Mineral);
    }

    [Fact]
    public void Request_rejects_all_truncations_trailing_bytes_and_wrong_type()
    {
        var bytes = Convert.FromHexString("03260102030411223344");
        for (var n = 0; n < bytes.Length; n++)
            Assert.False(OriginalWarehouseCodec.TryDecodeRequest(bytes.AsSpan(0, n), out _));
        Assert.False(OriginalWarehouseCodec.TryDecodeRequest([..bytes, 0], out _));
        bytes[1] = 0x27;
        Assert.False(OriginalWarehouseCodec.TryDecodeRequest(bytes, out _));
    }

    [Fact]
    public void Response_rejects_all_truncations_trailing_bytes_and_wrong_type()
    {
        var bytes = Convert.FromHexString(Response);
        for (var n = 0; n < bytes.Length; n++)
            Assert.False(OriginalWarehouseCodec.TryDecodeResponse(bytes.AsSpan(0, n), out _));
        Assert.False(OriginalWarehouseCodec.TryDecodeResponse([..bytes, 0], out _));
        bytes[1] = 0x26;
        Assert.False(OriginalWarehouseCodec.TryDecodeResponse(bytes, out _));
    }

    [Theory]
    [InlineData(100, 0)]
    [InlineData(0, 25)]
    public void Response_decoder_rejects_over_capacity_even_when_body_is_complete(int ships, int troops)
    {
        var bytes = new byte[28 + 5 * (ships + troops)];
        bytes[0] = 3; bytes[1] = 0x27; bytes[14] = (byte)ships;
        bytes[15 + 5 * ships] = (byte)troops;
        Assert.False(OriginalWarehouseCodec.TryDecodeResponse(bytes, out _));
    }

    [Fact]
    public void Encoder_rejects_missing_lists_and_over_capacity()
    {
        Assert.Throws<ArgumentException>(() => OriginalWarehouseCodec.EncodeResponse(default));
        Assert.Throws<ArgumentException>(() => OriginalWarehouseCodec.EncodeResponse(
            new(1, 2, 0, new OriginalWarehouseShip[100], [], 0, 0, 0)));
        Assert.Throws<ArgumentException>(() => OriginalWarehouseCodec.EncodeResponse(
            new(1, 2, 0, [], new OriginalWarehouseTroop[25], 0, 0, 0)));
    }
}
