using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalServiceCompletionCodecTests
{
    [Fact]
    public void Repair_completion_has_packed_u16_damage_without_struct_padding()
    {
        // Independent wire fixture: non-symmetric bytes expose endian/field swaps.
        Assert.Equal(Convert.FromHexString("00000000042D0102030411121314212223243132"),
            OriginalWorldBootstrapCodec.EncodeNotifyRepairFleet(0x01020304, 0x11121314, 0x21222324, 0x3132));
    }

    [Fact]
    public void Supply_completion_preserves_all_four_u32_fields()
    {
        Assert.Equal(Convert.FromHexString("00000000042E010203041112131421222324FFFFFFFF"),
            OriginalWorldBootstrapCodec.EncodeNotifySupplyFleet(0x01020304, 0x11121314, 0x21222324, uint.MaxValue));
    }
}
