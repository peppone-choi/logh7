using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalInformationBaseTests
{
    [Fact]
    public void Battlefield_projects_base_ownership_only_for_its_actual_grid()
    {
        var template = OriginalBattlefieldCatalog.LoadDefault().Resolve(101) with
        {
            BaseInformation = [new(1, 2, 0, 101), new(2, 3, 1, 102)],
        };
        Assert.True(OriginalInformationBaseCodec.TryEncodeResponse(Convert.FromHexString("031E020000000100000002"),
            template.ProjectBaseInformation(102), out var response));
        Assert.Equal("00000000031F0100000002030100000066", Convert.ToHexString(response[..17]));
        Assert.Equal(89, response.Length);
    }

    [Fact]
    public void Missing_ownership_does_not_fabricate_an_owned_base()
    {
        var template = OriginalBattlefieldCatalog.LoadDefault().Resolve(101) with { BaseInformation = null };
        Assert.Empty(template.ProjectBaseInformation(101));
        Assert.Null(template.BaseInformation); // retains unknown versus explicit empty
    }

    private static OriginalInformationBaseRecord NonzeroRecord() => new(0x01020304, 2, 3, 101)
    {
        DefenseOutfit = 1, PriceIndex = 1.0f, Antiaircraft = 2, Supplies = 3,
        OutfitSupplies = [4, 5], TransportSupplies = [6], PatrolSupplies = 7,
        GroundSupplies = 8, DefenceSupplies = 9, Population = 10, AdultPopulation = 11,
        Tax = 12, Budgeting = [13, 14], Budget = [15], Approval = 16,
        Peace = 17, Thought = 18, Religion = 19, Food = 20, Living = 21,
        Commodity = [22], AvailabilityRatio = 0.5f, Atmosphere = 1, Habitability = 2,
        Armor = 23, CannonAngle = 0x34, CannonStart = 24,
    };

    [Fact]
    public void Encodes_all_base_fields_in_original_compact_order_without_struct_padding()
    {
        // Literal sequence independently transcribed from00414C70 +004161F0.
        const string expected = "00000000031F01" +
            "01020304020300000065000000013F8000000000000200000003" +
            "0200000004000000050100000006" +
            "0000000700000008000000090000000A0000000B000C" +
            "02000D000E010000000F00100011001200130000001400000015" +
            "01000000163F000000010200173400000018";
        Assert.Equal(expected, Convert.ToHexString(
            OriginalInformationBaseCodec.EncodeResponse([NonzeroRecord()])));
    }

    [Theory]
    [InlineData("031E00")]
    [InlineData("031E0100000063")]
    public void Unknown_or_empty_base_request_returns_no_invented_base(string request)
    {
        Assert.True(OriginalInformationBaseCodec.TryEncodeResponse(Convert.FromHexString(request),
            [NonzeroRecord()], out var response));
        Assert.Equal("00000000031F00", Convert.ToHexString(response));
    }

    [Fact]
    public void Request_returns_only_the_requested_owner_record()
    {
        Assert.True(OriginalInformationBaseCodec.TryEncodeResponse(
            Convert.FromHexString("031E0101020304"),
            [new(99, 1, 0, 102), NonzeroRecord()], out var response));
        Assert.Equal("00000000031F0101020304020300000065", Convert.ToHexString(response[..17]));
        Assert.Equal(113, response.Length); //82 fixed +24 dynamic bytes +7 header/count
    }

    [Theory]
    [InlineData("031E")]
    [InlineData("031E010000")]
    [InlineData("031E0000")]
    [InlineData("034400")]
    [InlineData("031E050000000100000002000000030000000400000005")]
    public void Rejects_malformed_or_wrong_type_request(string request) =>
        Assert.False(OriginalInformationBaseCodec.TryEncodeResponse(
            Convert.FromHexString(request), [NonzeroRecord()], out _));

    [Theory]
    [InlineData("outfit")]
    [InlineData("transport")]
    [InlineData("budgeting")]
    [InlineData("budget")]
    [InlineData("commodity")]
    public void Rejects_arrays_larger_than_original_parser_capacity(string field)
    {
        var record = field switch
        {
            "outfit" => NonzeroRecord() with { OutfitSupplies = new uint[31] },
            "transport" => NonzeroRecord() with { TransportSupplies = new uint[31] },
            "budgeting" => NonzeroRecord() with { Budgeting = new ushort[7] },
            "budget" => NonzeroRecord() with { Budget = new uint[6] },
            _ => NonzeroRecord() with { Commodity = new uint[4] },
        };
        Assert.Throws<ArgumentOutOfRangeException>(() => OriginalInformationBaseCodec.EncodeResponse([record]));
    }

    [Fact]
    public void Rejects_more_than_four_base_records() =>
        Assert.Throws<ArgumentOutOfRangeException>(() => OriginalInformationBaseCodec.EncodeResponse(
            Enumerable.Range(1, 5).Select(id => new OriginalInformationBaseRecord((uint)id, 2, 0, 101)).ToArray()));
}
