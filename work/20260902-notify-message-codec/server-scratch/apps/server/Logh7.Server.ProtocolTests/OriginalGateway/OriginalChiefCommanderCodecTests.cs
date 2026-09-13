using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalChiefCommanderCodecTests
{
    [Fact]
    public void Chief_commander_notification_is_power_camp_and_character_without_padding()
    {
        //004A8120 reads two bytes then a network u32, not the expanded8-byte struct.
        var frame = OriginalWorldBootstrapCodec.EncodeNotifyTacticsChiefCommander(2, 1, 0x12345678u);
        Assert.Equal(Convert.FromHexString("000000000431020112345678"), frame);
    }
}
