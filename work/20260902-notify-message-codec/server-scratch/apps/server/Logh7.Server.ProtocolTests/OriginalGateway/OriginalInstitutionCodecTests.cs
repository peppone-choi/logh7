using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalInstitutionCodecTests
{
    [Fact]
    public void Nested_fields_follow_original_reader_without_expanded_struct_padding()
    {
        var actual = OriginalInstitutionCodec.EncodeResponse([
            new(0x11223344, [new(4, 0x55667788, [new(6, 0x99AABBCC, 0x1234), new(13, 0x01020304, 0x5678)])]),
            new(0x10203040, [])]);
        // Reader004167F0: prefix/type, base count; base/id/count; kind/id/count; kind/id/file.
        Assert.Equal("00000000032102" + "1122334401" + "00045566778802" +
            "000699AABBCC1234" + "000D010203045678" + "1020304000", Convert.ToHexString(actual));
    }

    [Fact]
    public void Empty_response_clears_the_receiving_cache()
    {
        Assert.Equal("00000000032100", Convert.ToHexString(OriginalInstitutionCodec.EncodeResponse([])));
    }

    [Fact]
    public void Maximum_nested_arrays_fit_original_receiver()
    {
        var spots = Enumerable.Range(1, 20).Select(i => new OriginalInstitutionSpot(6, (uint)i, 7)).ToArray();
        var institutions = Enumerable.Range(1, 36).Select(i => new OriginalInstitution(4, (uint)i, spots)).ToArray();
        var bases = Enumerable.Range(1, 4).Select(i => new OriginalBaseInstitutions((uint)i, institutions)).ToArray();
        var frame = OriginalInstitutionCodec.EncodeResponse(bases);
        Assert.Equal(24075, frame.Length); // 6 + 1 + 4*(5 + 36*(7 + 20*8))
        Assert.Equal((byte)4, frame[6]);
        Assert.Equal((byte)36, frame[11]);
        Assert.Equal((byte)20, frame[18]);
    }

    [Fact]
    public void Null_records_and_overflows_are_rejected_before_transmission()
    {
        Assert.Throws<ArgumentNullException>(() => OriginalInstitutionCodec.EncodeResponse(null!));
        Assert.Throws<ArgumentNullException>(() => OriginalInstitutionCodec.EncodeResponse([null!]));
        Assert.Throws<ArgumentNullException>(() => OriginalInstitutionCodec.EncodeResponse([new(1, null!)]));
        Assert.Throws<ArgumentNullException>(() => OriginalInstitutionCodec.EncodeResponse([new(1, [null!])]));
        Assert.Throws<ArgumentNullException>(() => OriginalInstitutionCodec.EncodeResponse([new(1, [new(4, 1, null!)])]));
        Assert.Throws<ArgumentNullException>(() => OriginalInstitutionCodec.EncodeResponse([new(1, [new(4, 1, [null!])])]));
        Assert.Throws<ArgumentOutOfRangeException>(() => OriginalInstitutionCodec.EncodeResponse(
            Enumerable.Repeat(new OriginalBaseInstitutions(1, []), 5).ToArray()));
        Assert.Throws<ArgumentOutOfRangeException>(() => OriginalInstitutionCodec.EncodeResponse([
            new(1, Enumerable.Repeat(new OriginalInstitution(4, 1, []), 37).ToArray())]));
        Assert.Throws<ArgumentOutOfRangeException>(() => OriginalInstitutionCodec.EncodeResponse([
            new(1, [new(4, 1, Enumerable.Repeat(new OriginalInstitutionSpot(6, 1, 0), 21).ToArray())])]));
    }
}
