using System.Buffers.Binary;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalTacticalEndingBoundaryTests
{
    [Theory]
    [InlineData(25, 0)]
    [InlineData(100, 100)]
    public void Tactical_casualties_do_not_emit_a_fabricated_campaign_ending(ushort damaged, ushort destroyed)
    {
        // 00433250: 035A begins character id/power/camp/state/age/session-age,
        // not time/victory/battle-id. The original manual separates tactical
        // completion (no enemies + objectives) from session ending.
        var attacked = new OriginalTacticalAttackedNotification(1234, 2, 1, 1,
            0x7f000001, damaged, destroyed, 0, 0, 100);
        var frames = OriginalTacticalCommandCodec.EncodeTacticalDamageUpdates(attacked,
            Convert.FromHexString("0000000003250000"),
            Convert.FromHexString("00000000033B0000"));

        Assert.Equal(new ushort[] { 0x0426, 0x0325, 0x033b },
            frames.Select(frame => BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4))).ToArray());
    }
}
