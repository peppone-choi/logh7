using System.Buffers.Binary;
using System.Reflection;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalParticipantRosterTests
{
    private static readonly byte[] Key = new byte[16];

    [Fact]
    public async Task BootstrapContainsPreviouslyJoinedPlayerAcrossEveryRequiredRecordFamily()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var actor = Session(battles, 3);
        await Send(actor, "0F02", 1);
        var observer = Session(battles, 2);
        var frames = Frames(await Send(observer, "0F02", 1));
        var ships = Assert.Single(frames, f => Type(f) == 0x33B);
        Assert.True(OriginalSystemSceneCodec.TryDecodeTacticalUnitShips(ships.AsSpan(4), out var decoded));
        Assert.Equal(new uint[] { 2, 0x7F000001, 3 }, decoded.Records.Select(r => r.Id));
        Assert.Equal(3u, decoded.Records[2].Character);
        foreach (var type in new ushort[] { 0x325, 0x337, 0x33F, 0x341, 0x349 })
            Assert.Equal((ushort)3, BinaryPrimitives.ReadUInt16BigEndian(
                Assert.Single(frames, f => Type(f) == type).AsSpan(6)));
        var actorCharacter = Assert.Single(frames, f => Type(f) == 0x323 &&
            BinaryPrimitives.ReadUInt32BigEndian(f.AsSpan(6)) == 3);
        Assert.True(actorCharacter.AsSpan().IndexOf(System.Text.Encoding.Unicode.GetBytes("Flag3")) >= 0);
    }

    [Theory]
    [InlineData("033A000100000003", 0x33B)]
    [InlineData("033E000100000003", 0x33F)]
    [InlineData("0340000100000003", 0x341)]
    [InlineData("0348000100000003", 0x349)]
    public async Task ScopedQueryCanFindAnotherJoinedPlayer(string request, ushort responseType)
    {
        var battles = new OriginalTacticalBattleRegistry();
        await Send(Session(battles, 3), "0F02", 1);
        var result = await Send(Session(battles, 2), request, 1);
        var frame = Frames(result)[0];
        Assert.Equal(responseType, Type(frame));
        Assert.Equal((ushort)1, BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(6)));
        Assert.Equal(3u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(8)));
    }

    [Fact]
    public async Task CharacterDetailQueryUsesOtherPlayersStoredIdentity()
    {
        var battles = new OriginalTacticalBattleRegistry();
        await Send(Session(battles, 3), "0F02", 1);
        var result = await Send(Session(battles, 2), "032200000003", 1);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        var frame = Frames(result)[0];
        Assert.Equal((ushort)0x323, Type(frame));
        Assert.Equal(3u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(6)));
    }

    private static NaturalAuthoritySession Session(OriginalTacticalBattleRegistry battles, uint id)
    {
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, Key,
            store: new RosterStore(id), battles: battles);
        OriginalWarpSessionClockTests.SetField(session, "_worldCharacterId", id);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridUnitId", id);
        OriginalWarpSessionClockTests.SetField(session, "_createdCharacter",
            new OriginalCreateCharacterCommand(4, id, 2, 0, 0, $"Pilot{id}", "First", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, 0, $"Flag{id}", 0, []));
        return session;
    }

    private static Task<NaturalAuthoritySessionResult> Send(NaturalAuthoritySession session, string hex, uint sequence) =>
        session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(Convert.FromHexString(hex), Key, sequence),
            TestContext.Current.CancellationToken);

    private static List<byte[]> Frames(NaturalAuthoritySessionResult result)
    {
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        var frames = new List<byte[]> { OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, Key, 0).Payload! };
        if (result.AdditionalResponses is not null)
            frames.AddRange(result.AdditionalResponses.Select(p => OriginalClientInnerFrameCodec.Decode(p.Payload, Key, 0).Payload!));
        return frames;
    }

    private static ushort Type(byte[] frame) => BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4));

    private sealed class RosterStore(uint id) : OriginalWarpSessionClockTests.UnusedStore
    {
        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid account,
            CancellationToken ct) => Task.FromResult<IReadOnlyList<CharacterReadRecord>>([
                new CharacterReadRecord(id, 0, 2, 0, 0, $"Pilot{id}", "First", $"Flag{id}", 5,
                    [1, 2, 3, 4, 5, 6, 7, 8], 20, 0, 0)]);
    }
}
