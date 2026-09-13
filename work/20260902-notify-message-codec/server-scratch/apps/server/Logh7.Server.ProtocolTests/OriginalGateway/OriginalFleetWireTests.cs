using System.Buffers.Binary;
using Xunit;
using Logh7.Server.OriginalGateway;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalFleetWireTests
{
    [Fact]
    public void Unit_membership_reaches_the_native_outfit_and_boarding_fields()
    {
        var unit = new OriginalInformationUnitProjection(7, 101, 0, 100, 0, 0, 100, 100, 10,
            Outfit: 0x01020304, BoardingShip: 0x11223344);
        var frame = OriginalWorldEntryCodec.EncodeUnits([unit]);
        Assert.Equal(0x01020304u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(19)));
        Assert.Equal(0x11223344u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(23)));
    }

    [Fact]
    public void Outfit_response_omits_native_padding_and_preserves_all_fields()
    {
        var row = new OriginalInformationOutfit(0x01020304, 5, 6, 7, 8,
            0x090a, 0x0b0c0d0e, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24);
        Assert.Equal("00000000032B010102030405060708090A0B0C0D0E0F101112131415161718",
            Convert.ToHexString(OriginalInformationOutfitCodec.Encode([row])));
    }

    [Fact]
    public void Outfit_response_supports_empty_and_full_native_capacity()
    {
        Assert.Equal("00000000032B00", Convert.ToHexString(OriginalInformationOutfitCodec.Encode([])));
        var frame = OriginalInformationOutfitCodec.Encode(new OriginalInformationOutfit[100]);
        Assert.Equal(2407, frame.Length);
        Assert.Equal(100, frame[6]);
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            OriginalInformationOutfitCodec.Encode(new OriginalInformationOutfit[101]));
        Assert.Throws<ArgumentNullException>(() => OriginalInformationOutfitCodec.Encode(null!));
    }
}
