using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalUnitShipLogisticsTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void Full_bootstrap_and_complement_overrides_preserve_manual_crew(bool overrideComplements)
    {
        var frame = overrideComplements
            ? OriginalWorldBootstrapCodec.EncodeStaticUnitShipsWithComplements(
                new Dictionary<ushort, ushort> { [32] = 123, [56] = 124, [119] = 125 })
            : OriginalWorldBootstrapCodec.EncodeStaticUnitShips();
        var expected = new Dictionary<ushort, ushort> { [32] = 5, [56] = 1, [119] = 4 };
        var seen = new HashSet<ushort>();
        var cursor = 7;
        for (var i = 0; i < frame[6]; i++)
        {
            var kind = System.Buffers.Binary.BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(cursor));
            var numberOffset = cursor + 9 + frame[cursor + 8] * 2;
            if (expected.TryGetValue(kind, out var crew))
            {
                Assert.True(seen.Add(kind));
                Assert.Equal(crew, System.Buffers.Binary.BinaryPrimitives.ReadUInt16BigEndian(
                    frame.AsSpan(numberOffset + 14)));
                var number = overrideComplements ? kind switch { 32 => 123, 56 => 124, _ => 125 } : 300;
                Assert.Equal(number, System.Buffers.Binary.BinaryPrimitives.ReadUInt16BigEndian(
                    frame.AsSpan(numberOffset)));
            }
            cursor += 106 + frame[cursor + 8] * 2;
        }
        Assert.Equal(frame.Length, cursor);
        Assert.Equal(3, seen.Count);
    }

    [Theory]
    [InlineData(56, "0001")]
    [InlineData(32, "0005")]
    [InlineData(119, "0004")]
    public void Catalog_serves_manual_crew_units_without_changing_other_fields(int kind, string expected)
    {
        var template = Assert.Single(OriginalSubordinateShipCatalog.Templates, t => t.Kind == kind);
        var frame = OriginalWorldBootstrapCodec.EncodeStaticUnitShips([template]);
        // Manual PDF pages79/82/90: crew UNIT counts, not per-hull multipliers.
        Assert.Equal(expected, Convert.ToHexString(frame.AsSpan(32, 2)));
        var legacy = OriginalWorldBootstrapCodec.EncodeStaticUnitShips([template with { Logistics = null }]);
        frame[32] = 0;
        frame[33] = 0;
        Assert.Equal(legacy, frame);
        // Kinds57/58 are authored support overrides, not original destroyer II/III here.
        Assert.Null(Assert.Single(OriginalSubordinateShipCatalog.Templates,
            t => t.Kind == OriginalSubordinateShipCatalog.RepairShipKind).Logistics);
    }

    [Fact]
    public void Logistics_fields_reach_the_original_wire_without_changing_neighboring_fields()
    {
        // Original 004109A0 packed reader / 00411A60 field logger.
        // Empty name contains one u16 NUL. number begins at frame+18;
        // price:u32/resources:u16/cost:u16/term:u16/existence:u16/crew:u16/power:u16.
        var capabilities = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities with
        {
            Number = 0x1122, Existence = 0x3344, TotalPower = 0x5566
        };
        var frame = OriginalWorldBootstrapCodec.EncodeStaticUnitShips(
            [new(1, 0, 0, 0, 0, string.Empty, capabilities,
                new(0x01020304, 0x0506, 0x0708, 0x090a, 0x0b0c))]);

        Assert.Equal("11220102030405060708090A33440B0C5566",
            Convert.ToHexString(frame.AsSpan(18, 18)));
    }

    [Fact]
    public void Distinct_ship_kinds_keep_distinct_crew_requirements()
    {
        var first = new OriginalStaticUnitShipTemplate(1, 0, 0, 0, 0, string.Empty,
            Logistics: new(1, 2, 3, 4, 123));
        var second = new OriginalStaticUnitShipTemplate(2, 0, 0, 0, 0, string.Empty,
            Logistics: new(5, 6, 7, 8, 456));
        var one = OriginalWorldBootstrapCodec.EncodeStaticUnitShips([first]);
        var two = OriginalWorldBootstrapCodec.EncodeStaticUnitShips([second]);
        var both = OriginalWorldBootstrapCodec.EncodeStaticUnitShips([first, second]);

        Assert.Equal("007B", Convert.ToHexString(one.AsSpan(32, 2)));
        Assert.Equal("01C8", Convert.ToHexString(two.AsSpan(32, 2)));
        Assert.Equal(one.AsSpan(7).ToArray(), both.AsSpan(7, one.Length - 7).ToArray());
        Assert.Equal(two.AsSpan(7).ToArray(), both.AsSpan(one.Length).ToArray());
    }

    [Fact]
    public void Missing_logistics_retains_zero_fixture_and_does_not_invent_crew_or_stock()
    {
        var template = new OriginalStaticUnitShipTemplate(1, 0, 0, 0, 0, string.Empty);
        var absent = OriginalWorldBootstrapCodec.EncodeStaticUnitShips([template]);
        var explicitZero = OriginalWorldBootstrapCodec.EncodeStaticUnitShips(
            [template with { Logistics = new(0, 0, 0, 0, 0) }]);
        Assert.Equal(explicitZero, absent);
        Assert.All(absent.AsSpan(20, 14).ToArray(), value => Assert.Equal((byte)0, value));
    }

    [Fact]
    public void Full_original_unsigned_widths_are_preserved()
    {
        var frame = OriginalWorldBootstrapCodec.EncodeStaticUnitShips(
            [new(1, 0, 0, 0, 0, string.Empty,
                Logistics: new(uint.MaxValue, ushort.MaxValue, ushort.MaxValue,
                    ushort.MaxValue, ushort.MaxValue))]);
        Assert.Equal("FFFFFFFFFFFFFFFFFFFF0000FFFF",
            Convert.ToHexString(frame.AsSpan(20, 14)));
    }
}
