using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalWarpCompletionIdentityTests
{
    [Theory]
    [InlineData(41u, true)]
    [InlineData(7u, false)]
    [InlineData(42u, false)]
    public async Task Request_actor_and_completion_identity_are_distinct_from_owned_unit(uint actor, bool allowed)
    {
        // Store double isolates wire projection. Distinct IDs catch confusing
        // SSCharacterIDResponce actor41 with owned InformationUnit7.
        var store = new MoveStore();
        var clock = new Clock();
        var key = new byte[16];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(clock, key, store: store);
        OriginalWarpSessionClockTests.SetField(session, "_worldCharacterId", 41u);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridUnitId", 7u);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridCellId", 102u);
        OriginalWarpSessionClockTests.SetField(session, "_persistedGridUnit", store.Unit);
        OriginalWarpSessionClockTests.SetField(session, "_createdCharacter",
            new OriginalCreateCharacterCommand(4, 41, 2, 0, 0, "Pilot", "First", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, 3, "", 0, []));
        clock.Timestamp = 1000;
        var request = new byte[33];
        BinaryPrimitives.WriteUInt16BigEndian(request, 0x0B01);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(10), actor);
        BinaryPrimitives.WriteUInt16BigEndian(request.AsSpan(14), 39);
        BinaryPrimitives.WriteUInt16BigEndian(request.AsSpan(24), 101);
        BinaryPrimitives.WriteUInt16BigEndian(request.AsSpan(31), 4);
        var result = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, 1), TestContext.Current.CancellationToken);
        if (!allowed)
        {
            Assert.NotEqual(NaturalAuthoritySessionStatus.Success, result.Status);
            Assert.Equal(102u, store.Unit.CurrentCellId);
            Assert.Equal(1, store.Unit.AuthorityVersion);
            return;
        }
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        var frame = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, key, 0).Payload!;
        Assert.Equal((ushort)0x0B07, BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)));
        Assert.Equal(41u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(10)));
        Assert.Equal(24u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(6)));
        Assert.Equal(101u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(14)));
        // The arrival joins the destination grid's own base; the completion
        // reports it, not a zero the client would read as open space.
        Assert.Equal(1u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(18)));
        Assert.Equal((ushort)4, BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(22)));
        Assert.Equal((byte)1, frame[24]);
        Assert.Equal(7u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(25)));
        Assert.Equal(9f, BinaryPrimitives.ReadSingleBigEndian(frame.AsSpan(29)));
    }

    private sealed class Clock : TimeProvider
    {
        public long Timestamp;
        public override long TimestampFrequency => 1000;
        public override long GetTimestamp() => Timestamp;
    }

    private sealed class MoveStore : OriginalWarpSessionClockTests.UnusedStore, IAccountStore
    {
        public OriginalGridUnitRecord Unit = new(41, 7, 39, 102, 1, Mode: 4);
        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid a, CancellationToken c) =>
            Task.FromResult<IReadOnlyList<CharacterReadRecord>>([
                new(41, 0, 2, 0, 0, "Pilot", "First", "", 5, [1,2,3,4,5,6,7,8], 20, 0, 0)]);
        public Task<OriginalGridUnitRecord?> FindOriginalGridUnitAsync(Guid a, long character, uint unit, CancellationToken c) =>
            Task.FromResult<OriginalGridUnitRecord?>(character == 41 && unit == 7 ? Unit : null);
        public Task<OriginalMoveGridStoreResult> MoveOriginalGridUnitAsync(Guid a, OriginalMoveGridWrite w, CancellationToken c)
        {
            Assert.Equal((41L,7u,102u,101u),(w.CharacterId,w.UnitId,w.SourceCellId,w.DestinationCellId));
            Unit = Unit with { CurrentCellId = 101, BaseId = w.DestinationBaseId, Cruising = 9, AuthorityVersion = 2 };
            return Task.FromResult(new OriginalMoveGridStoreResult(OriginalMoveGridStoreStatus.Moved, Unit, 2, null));
        }
    }
}
