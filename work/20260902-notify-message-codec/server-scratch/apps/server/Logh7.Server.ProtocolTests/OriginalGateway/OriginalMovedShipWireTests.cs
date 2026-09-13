using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalMovedShipWireTests
{
    [Theory]
    [InlineData(-128, "80")]
    [InlineData(-1, "FF")]
    [InlineData(0, "00")]
    [InlineData(127, "7F")]
    public void Moved_ship_has_25_packed_body_bytes_and_signed_route(int route, string routeHex)
    {
        var frame = OriginalTacticalCommandCodec.EncodeMovedShipNotification(
            new(0x01020304, 0x11223344, 1.5f, -10f, .25f,
                BitConverter.UInt32BitsToSingle(0x80000000), checked((sbyte)route)));

        // 004A5870 reads time/unit, four f32, one raw byte; no trailing padding.
        // 004A5A20 labels direction and route; 004BF870 tests route as signed.
        Assert.Equal(Convert.FromHexString(
            "00000000042301020304112233443FC00000C12000003E80000080000000" + routeHex), frame);
    }

    public static IEnumerable<object[]> NonFiniteComponents()
    {
        for (var component = 0; component < 4; component++)
        {
            yield return [component, float.NaN];
            yield return [component, float.PositiveInfinity];
            yield return [component, float.NegativeInfinity];
        }
    }

    [Theory]
    [MemberData(nameof(NonFiniteComponents))]
    public void Moved_ship_rejects_nonfinite_direction_or_position(int component, float value)
    {
        var fields = new[] { 1.5f, -10f, .25f, 0f };
        fields[component] = value;
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            OriginalTacticalCommandCodec.EncodeMovedShipNotification(
                new(1, 2, fields[0], fields[1], fields[2], fields[3], -1)));
    }
}
