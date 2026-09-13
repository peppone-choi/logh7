using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalTacticalActorLifeTests
{
    [Theory]
    [InlineData(false, false)]
    [InlineData(false, true)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public async Task Stop_and_powered_warp_require_a_living_actor(bool warp, bool destroyed)
    {
        var key = new byte[16];
        var battles = new OriginalTacticalBattleRegistry();
        var session = OriginalPlayerCombatTests.Session(battles, 2, 2);
        await OriginalWarpPowerGateTests.SetPower(session, key, 50);
        if (destroyed) battles.GetEncounter(101, 100).RecordUnitDamage(2, new(100, 100));
        var request = Convert.FromHexString(warp
            ? "04040000000000000000000000020100000002"
            : "040A000000000000000000000002010000000200");
        var result = await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(request, key, 2),
            TestContext.Current.CancellationToken);
        var frame = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, key, 0).Payload!;
        Assert.Equal(destroyed ? (ushort)0x500 : warp ? (ushort)0x425 : (ushort)0x40a,
            BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)));
        if (destroyed)
        {
            Assert.Contains("DESTROYED", result.ResponseMetadata);
            Assert.Empty(result.AdditionalResponses ?? []);
            Assert.Equal((ushort)100, battles.GetEncounter(101, 100).GetUnitDamage(2).Destroyed);
        }
    }
}
