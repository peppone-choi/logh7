using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalOutfitPartyCodecTests
{
    // Hand-packed from reader0041CBA0/logger0041EAA0; includes every variable row family.
    private const string Mixed = "032F0102030411223344A5FE7F5566778899AABBCC" +
        "01" + "0000002A0304030041D800DC00" +
        "01" + "1234FE012C02" + "00000009FFFFFFFF" +
        "01" + "008904FFFE" + "00010203040506070809" +
        "01" + "0A0B0C0D0E0F1011" + "01" + "1213141516171819" + "1A1B1C" +
        "01" + "20210322230124252627" + "01" + "28292A2B2C";

    [Fact]
    public void Request_preserves_outfit_base_and_raw_mode_in_original_order()
    {
        Assert.True(OriginalOutfitPartyCodec.TryDecodeRequest(Convert.FromHexString("032E0102030411223344FE"), out var request));
        Assert.Equal(new OriginalOutfitPartyRequest(0x01020304, 0x11223344, 254), request);
        Assert.Equal("00000000032E0102030411223344FE", Convert.ToHexString(OriginalOutfitPartyCodec.EncodeRequest(request)));
    }

    [Fact]
    public void Mixed_response_decodes_every_row_and_keeps_unit_number_distinct_from_unit_list_count()
    {
        Assert.True(OriginalOutfitPartyCodec.TryDecodeResponse(Convert.FromHexString(Mixed), out var r));
        Assert.Equal(0x01020304u, r.OutfitId);
        Assert.Equal(0x11223344u, r.BaseId);
        Assert.Equal((byte)0xa5, r.Mode);
        Assert.Equal((byte)254, r.Power);
        Assert.Equal((byte)127, r.Camp);
        Assert.Equal(0x55667788u, r.Kind);
        Assert.Equal(0x99aabbccu, r.Index);
        Assert.Equal(new OriginalOutfitPartyCharacter(42, 3, 4, "A\ud800\udc00"), Assert.Single(r.Characters));
        var ship = Assert.Single(r.Ships);
        Assert.Equal((ushort)0x1234, ship.Kind);
        Assert.Equal((byte)254, ship.UnitNumber);
        Assert.Equal((ushort)300, ship.BoatNumber);
        Assert.Equal(new uint[] { 9, uint.MaxValue }, ship.Units);
        Assert.Equal(new OriginalWarehouseTroop(137, 4, 65534), Assert.Single(r.Troops));
        Assert.Equal(0x00010203u, r.Supplies);
        Assert.Equal(0x04050607u, r.MaxSupplies);
        Assert.Equal((ushort)0x0809, r.Package);
        Assert.Equal(new OriginalOutfitPartyPackage(10, 0x0b0c, 13, 0x0e0f1011), Assert.Single(r.OtherPackages));
        Assert.Equal(new OriginalOutfitPartyPackage(18, 0x1314, 21, 0x16171819), Assert.Single(r.TroopPackages));
        Assert.Equal((byte)26, r.TransportPackageEmpty);
        Assert.Equal((byte)27, r.TroopTransportPackageEmpty);
        Assert.Equal((byte)28, r.Carrying);
        var separate = Assert.Single(r.NotTogetherShips);
        Assert.Equal((ushort)0x2021, separate.Kind);
        Assert.Equal((byte)3, separate.UnitNumber);
        Assert.Equal((ushort)0x2223, separate.BoatNumber);
        Assert.Equal(new uint[] { 0x24252627 }, separate.Units);
        Assert.Equal(new OriginalWarehouseTroop(0x2829, 42, 0x2b2c), Assert.Single(r.NotTogetherTroops));
    }

    [Fact]
    public void Encoder_matches_hand_packed_mixed_response_without_padding_or_counter_normalization()
    {
        var response = new OriginalOutfitPartyResponse(0x01020304, 0x11223344, 0xa5, 254, 127, 0x55667788, 0x99aabbcc,
            [new(42, 3, 4, "A\ud800\udc00")], [new(0x1234, 254, 300, [9, uint.MaxValue])], [new(137, 4, 65534)],
            0x00010203, 0x04050607, 0x0809, [new(10, 0x0b0c, 13, 0x0e0f1011)], [new(18, 0x1314, 21, 0x16171819)],
            26, 27, 28, [new(0x2021, 3, 0x2223, [0x24252627])], [new(0x2829, 42, 0x2b2c)]);
        var encoded = OriginalOutfitPartyCodec.EncodeResponse(response);
        Assert.Equal(108, encoded.Length);
        Assert.Equal("00000000" + Mixed, Convert.ToHexString(encoded));
    }

    [Fact]
    public void Empty_response_has_39_byte_body_and_keeps_all_empty_sections()
    {
        var encoded = OriginalOutfitPartyCodec.EncodeResponse(Empty);
        Assert.Equal(45, encoded.Length);
        Assert.Equal("00000000032F" + new string('0', 78), Convert.ToHexString(encoded));
        Assert.True(OriginalOutfitPartyCodec.TryDecodeResponse(encoded.AsSpan(4), out var r));
        Assert.Empty(r.Characters);
        Assert.Empty(r.Ships);
        Assert.Empty(r.Troops);
        Assert.Empty(r.OtherPackages);
        Assert.Empty(r.TroopPackages);
        Assert.Empty(r.NotTogetherShips);
        Assert.Empty(r.NotTogetherTroops);
    }

    [Fact]
    public void Raw_utf16_code_units_include_unpaired_surrogates_without_replacement()
    {
        var encoded = OriginalOutfitPartyCodec.EncodeResponse(Empty with
        {
            Characters = [new(0, 0, 0, "\ud800A\udc00")]
        });
        Assert.Equal("D8000041DC00", Convert.ToHexString(encoded.AsSpan(33, 6)));
        Assert.True(OriginalOutfitPartyCodec.TryDecodeResponse(encoded.AsSpan(4), out var decoded));
        Assert.Equal("\ud800A\udc00", Assert.Single(decoded.Characters).Name);
    }

    [Fact]
    public void Maximum_original_counts_names_units_and_unsigned_fields_are_accepted()
    {
        var character = new OriginalOutfitPartyCharacter(uint.MaxValue, 255, 255, new string('\uffff', 13));
        var ship = new OriginalOutfitPartyShip(65535, 255, 65535, Enumerable.Repeat(uint.MaxValue, 70).ToArray());
        var troop = new OriginalWarehouseTroop(65535, 255, 65535);
        var package = new OriginalOutfitPartyPackage(255, 65535, 255, uint.MaxValue);
        var response = new OriginalOutfitPartyResponse(uint.MaxValue, uint.MaxValue, 255, 255, 255, uint.MaxValue, uint.MaxValue,
            Enumerable.Repeat(character, 10).ToArray(), Enumerable.Repeat(ship, 60).ToArray(), Enumerable.Repeat(troop, 24).ToArray(),
            uint.MaxValue, uint.MaxValue, 65535, Enumerable.Repeat(package, 3).ToArray(), Enumerable.Repeat(package, 24).ToArray(),
            255, 255, 255, Enumerable.Repeat(ship, 60).ToArray(), Enumerable.Repeat(troop, 24).ToArray());
        var encoded = OriginalOutfitPartyCodec.EncodeResponse(response);
        Assert.Equal(35151, encoded.Length);
        Assert.True(OriginalOutfitPartyCodec.TryDecodeResponse(encoded.AsSpan(4), out var r));
        Assert.Equal(10, r.Characters.Count);
        Assert.Equal(character, r.Characters[9]);
        Assert.Equal(60, r.Ships.Count);
        Assert.Equal(70, r.Ships[59].Units.Count);
        Assert.Equal(uint.MaxValue, r.Ships[59].Units[69]);
        Assert.Equal(24, r.Troops.Count);
        Assert.Equal(troop, r.Troops[23]);
        Assert.Equal(3, r.OtherPackages.Count);
        Assert.Equal(package, r.OtherPackages[2]);
        Assert.Equal(24, r.TroopPackages.Count);
        Assert.Equal(package, r.TroopPackages[23]);
        Assert.Equal(60, r.NotTogetherShips.Count);
        Assert.Equal(70, r.NotTogetherShips[59].Units.Count);
        Assert.Equal(24, r.NotTogetherTroops.Count);
        Assert.Equal(uint.MaxValue, r.Supplies);
        Assert.Equal(uint.MaxValue, r.MaxSupplies);
        Assert.Equal((ushort)65535, r.Package);
        Assert.Equal((byte)255, r.Carrying);
    }

    [Fact]
    public void Decoder_rejects_every_truncation_trailing_bytes_and_wrong_opcode()
    {
        var request = Convert.FromHexString("032E0102030411223344FE");
        for (var length = 0; length < request.Length; length++)
            Assert.False(OriginalOutfitPartyCodec.TryDecodeRequest(request.AsSpan(0, length), out _));
        Assert.False(OriginalOutfitPartyCodec.TryDecodeRequest([..request, 0], out _));
        request[1] = 0x2f;
        Assert.False(OriginalOutfitPartyCodec.TryDecodeRequest(request, out _));
        var response = Convert.FromHexString(Mixed);
        for (var length = 0; length < response.Length; length++)
            Assert.False(OriginalOutfitPartyCodec.TryDecodeResponse(response.AsSpan(0, length), out _));
        Assert.False(OriginalOutfitPartyCodec.TryDecodeResponse([..response, 0], out _));
        response[1] = 0x2e;
        Assert.False(OriginalOutfitPartyCodec.TryDecodeResponse(response, out _));
    }

    [Theory]
    [InlineData(21, 11, 7)]
    [InlineData(22, 61, 6)]
    [InlineData(23, 25, 5)]
    [InlineData(34, 4, 8)]
    [InlineData(35, 25, 8)]
    [InlineData(39, 61, 6)]
    [InlineData(40, 25, 5)]
    public void Decoder_rejects_over_capacity_lists_even_with_complete_rows(int offset, int count, int size)
    {
        Assert.False(OriginalOutfitPartyCodec.TryDecodeResponse(CompleteRows(offset, count, size), out _));
    }

    [Theory]
    [InlineData(21, 7, 28, 14, 2)]
    [InlineData(22, 6, 28, 71, 4)]
    [InlineData(39, 6, 45, 71, 4)]
    public void Decoder_rejects_oversize_names_and_unit_lists_even_with_complete_data(int outerOffset, int rowSize, int innerOffset, int count, int size)
    {
        var outer = CompleteRows(outerOffset, 1, rowSize);
        outer[innerOffset] = (byte)count;
        var bytes = outer[..(innerOffset + 1)].Concat(new byte[count * size]).Concat(outer[(innerOffset + 1)..]).ToArray();
        Assert.False(OriginalOutfitPartyCodec.TryDecodeResponse(bytes, out _));
    }

    [Fact]
    public void Encoder_rejects_null_lists_null_nested_fields_and_all_over_capacity_arrays()
    {
        Assert.Throws<ArgumentException>(() => OriginalOutfitPartyCodec.EncodeResponse(default));
        OriginalOutfitPartyResponse[] invalid =
        [
            Empty with { Characters = null! }, Empty with { Ships = null! }, Empty with { Troops = null! },
            Empty with { OtherPackages = null! }, Empty with { TroopPackages = null! },
            Empty with { NotTogetherShips = null! }, Empty with { NotTogetherTroops = null! },
            Empty with { Characters = Enumerable.Repeat(new OriginalOutfitPartyCharacter(0, 0, 0, ""), 11).ToArray() },
            Empty with { Ships = Enumerable.Repeat(new OriginalOutfitPartyShip(0, 0, 0, []), 61).ToArray() },
            Empty with { Troops = new OriginalWarehouseTroop[25] },
            Empty with { OtherPackages = new OriginalOutfitPartyPackage[4] },
            Empty with { TroopPackages = new OriginalOutfitPartyPackage[25] },
            Empty with { NotTogetherShips = Enumerable.Repeat(new OriginalOutfitPartyShip(0, 0, 0, []), 61).ToArray() },
            Empty with { NotTogetherTroops = new OriginalWarehouseTroop[25] },
            Empty with { Characters = [new(0, 0, 0, new string('A', 14))] },
            Empty with { Characters = [new(0, 0, 0, null!)] },
            Empty with { Ships = [new(0, 0, 0, null!)] },
            Empty with { Ships = [new(0, 0, 0, new uint[71])] },
            Empty with { NotTogetherShips = [new(0, 0, 0, null!)] },
            Empty with { NotTogetherShips = [new(0, 0, 0, new uint[71])] }
        ];
        foreach (var response in invalid)
            Assert.Throws<ArgumentException>(() => OriginalOutfitPartyCodec.EncodeResponse(response));
    }

    private static OriginalOutfitPartyResponse Empty => new(0, 0, 0, 0, 0, 0, 0, [], [], [], 0, 0, 0, [], [], 0, 0, 0, [], []);

    private static byte[] CompleteRows(int countOffset, int count, int rowSize)
    {
        // Independent all-zero baseline: opcode + 39 body bytes; insert complete rows after its count.
        var bytes = new byte[41 + count * rowSize];
        bytes[0] = 3;
        bytes[1] = 0x2f;
        bytes[countOffset] = (byte)count;
        return bytes;
    }
}
