using System.Buffers.Binary;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalWeaponMaskTests
{
    [Theory]
    [InlineData(0, 0x40)]
    [InlineData(0, 0xB4)]
    [InlineData(1, 0x80)]
    [InlineData(2, 0xFF)]
    public void Static_weapon_arcs_reject_bits_outside_the_six_original_directions(int family, byte mask)
    {
        var capabilities = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities with
        {
            BeamAngleMask = family == 0 ? mask : (byte)0x34,
            GunAngleMask = family == 1 ? mask : (byte)0,
            MissileAngleMask = family == 2 ? mask : (byte)0,
        };
        Assert.Throws<ArgumentOutOfRangeException>(() => OriginalWorldBootstrapCodec.EncodeStaticUnitShips(
            [new OriginalStaticUnitShipTemplate(0, 0, 0, 0, 0, string.Empty, capabilities)]));
    }

    [Fact]
    public void Authored_ship_shield_selects_a_valid_recovery_table_column()
    {
        var frame = OriginalWorldBootstrapCodec.EncodeStaticUnitShips();
        var shield = BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(84));
        // Original 004C1700 requires column = (template.shield - 10)/10 in 0..8.
        Assert.InRange(((int)shield - 10) / 10, 0, 8);
    }

    [Fact]
    public void Authored_beam_arc_preserves_its_six_used_bits_without_high_bit_garbage()
    {
        var frame = OriginalWorldBootstrapCodec.EncodeStaticUnitShips();
        // Previous B4 transmitted six active bits 110100. It is NOT 180 degrees.
        Assert.Equal((byte)0x34, frame[91]);
    }
}
