using System.Buffers.Binary;
using System.Threading.Channels;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalNpcAssignmentCharacterTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Reassigned_ship_does_not_answer_with_the_old_commanders_character(bool knownController)
    {
        var ct = TestContext.Current.CancellationToken;
        var battles = new OriginalTacticalBattleRegistry();
        // Lower unit ID sorts first, before the actual controller's unit.
        battles.RegisterNpc(OriginalNpcAiTests.Actor(1, 2, 0),
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities, OriginalAuthoredPlayableCatalog.TacticalArms);
        var controller = OriginalNpcAiTests.Actor(2, 2, 10);
        if (knownController)
            battles.UpdateParticipant(Channel.CreateUnbounded<OriginalTacticalNotificationBatch>().Writer, controller);
        Assert.True(battles.ApplyNpcControlAssignment(101, 1, 2, controller.Corps, false));
        var session = OriginalPlayerCombatTests.Session(battles, 3, 2);
        var key = new byte[16];
        var answer = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("032200000002"), key, 1), ct);
        if (knownController)
        {
            Assert.Equal(NaturalAuthoritySessionStatus.Success, answer.Status);
            var decoded = OriginalClientInnerFrameCodec.Decode(answer.ResponsePayload!, key, 0);
            Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
            Assert.Equal(2u, BinaryPrimitives.ReadUInt32BigEndian(decoded.Payload!.AsSpan(6)));
            Assert.Equal(controller.CharacterFrame.ToArray(), decoded.Payload);
        }
        else
        {
            Assert.Equal(NaturalAuthoritySessionStatus.Invalid, answer.Status);
            Assert.Equal("original.information-character.unknown-id", answer.ErrorCode);
        }
        Assert.True(battles.NpcSnapshot(101, 1)!.CharacterFrame.IsEmpty);
    }
}
