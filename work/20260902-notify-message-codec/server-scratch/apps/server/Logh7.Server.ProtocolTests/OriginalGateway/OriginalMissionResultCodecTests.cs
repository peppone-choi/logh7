using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalMissionResultCodecTests
{
    [Fact]
    public void Mission_result_uses_packed_fields_in_original_reader_order()
    {
        // 004A9AD0 reads u32,u32,u8,u8,u32, not the padded 16-byte C structure.
        // Distinct bytes catch endian swaps, field swaps and accidental padding.
        var frame = OriginalWorldBootstrapCodec.EncodeNotifyMissionResult(
            0x01020304, 0x11121314, 0x21, 0x32, 0x41424344);
        Assert.Equal(Convert.FromHexString("00000000043C0102030411121314213241424344"), frame);
    }
}
