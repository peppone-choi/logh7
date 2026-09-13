using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalReturnBaseTests
{
    private static readonly byte[] Key = new byte[16];

    [Theory]
    [InlineData("00000001", 1u)]
    [InlineData("00000002", 2u)]
    public async Task Return_base_command_echoes_full_record_instead_of_status_only(string baseHex, uint baseId)
    {
        var store = new ReturnStore();
        var session = Session(store);
        var result = await Send(session, "0F1A0000007B00000002" + baseHex, 1);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        var frame = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, Key, 0).Payload!;
        Assert.Equal(Convert.FromHexString("0F1A0000007B00000002" + baseHex), frame.AsSpan(4).ToArray());
        var info = Frame(await Send(session, "032200000002", 2));
        Assert.Equal(baseId, BinaryPrimitives.ReadUInt32BigEndian(info.AsSpan(26)));
        Assert.Equal(2u, BinaryPrimitives.ReadUInt32BigEndian(info.AsSpan(6)));
        Assert.Equal(2u, BinaryPrimitives.ReadUInt32BigEndian(info.AsSpan(38)));
        Assert.Equal(1, store.Writes);
    }

    [Fact]
    public async Task New_session_restores_return_base_from_storage()
    {
        var store = new ReturnStore();
        store.Character = store.Character with { ReturnBaseId = 1 };
        var info = Frame(await Send(Session(store), "032200000002", 1));
        Assert.Equal(1u, BinaryPrimitives.ReadUInt32BigEndian(info.AsSpan(26)));
        Assert.Equal(0, store.Writes);
    }

    [Fact]
    public async Task Npc_does_not_inherit_viewer_return_base()
    {
        var store = new ReturnStore();
        store.Character = store.Character with { ReturnBaseId = 1 };
        var info = Frame(await Send(Session(store), "0322" +
            OriginalAuthoredPlayableCatalog.TacticalEnemyCharacterId.ToString("X8"), 1));
        Assert.Equal(0u, BinaryPrimitives.ReadUInt32BigEndian(info.AsSpan(26)));
    }

    [Fact]
    public async Task Zero_clears_the_preference_without_relocating_the_character()
    {
        var store = new ReturnStore();
        store.Character = store.Character with { ReturnBaseId = 1 };
        var session = Session(store);
        Frame(await Send(session, "0F1A0000007B0000000200000000", 1));
        var info = Frame(await Send(session, "032200000002", 2));
        Assert.Equal(0u, BinaryPrimitives.ReadUInt32BigEndian(info.AsSpan(26)));
        Assert.Equal(2u, BinaryPrimitives.ReadUInt32BigEndian(info.AsSpan(38)));
    }

    [Theory]
    [InlineData("0F1A0000007B0000000300000001", "character")]
    [InlineData("0F1A0000007B00000002000000FF", "base")]
    [InlineData("0F1A0000007B0000000200000001FF", "payload")]
    [InlineData("0F1A0000007B00000002", "payload")]
    public async Task Invalid_selection_is_rejected_without_writing(string request, string reason)
    {
        var store = new ReturnStore();
        var result = await Send(Session(store), request, 1);
        Assert.Equal(NaturalAuthoritySessionStatus.Invalid, result.Status);
        Assert.Equal("original.return-base." + reason, result.ErrorCode);
        Assert.Equal(0, store.Writes);
    }

    [Theory]
    [InlineData("00000001")]
    [InlineData("00000002")]
    public async Task Enemy_owned_return_base_is_rejected(string baseHex)
    {
        var store = new ReturnStore();
        store.Character = store.Character with { Faction = 3 };
        var result = await Send(Session(store), "0F1A0000007B00000002" + baseHex, 1);
        Assert.Equal("original.return-base.base", result.ErrorCode);
        Assert.Equal(0, store.Writes);
    }

    [Fact]
    public async Task Failed_storage_does_not_publish_success_or_change_current_character()
    {
        var store = new ReturnStore { FailWrite = true };
        var session = Session(store);
        await Assert.ThrowsAsync<IOException>(() => Send(session, "0F1A0000007B0000000200000001", 1));
        var info = Frame(await Send(session, "032200000002", 2));
        Assert.Equal(0u, BinaryPrimitives.ReadUInt32BigEndian(info.AsSpan(26)));
    }

    private static NaturalAuthoritySession Session(ReturnStore store) =>
        OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, Key, store: store);

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Fresh_identical_command_after_another_setting_is_not_a_retransmission(bool reconnect)
    {
        var store = new ReturnStore();
        var session = Session(store);
        // Original time is not established as a unique request id. Distinct
        // inner sequences are fresh commands even when payload/time are equal.
        Frame(await Send(session, "0F1A000000000000000200000000", 1));
        Frame(await Send(session, "0F1A000000000000000200000001", 2));
        if (reconnect) session = Session(store);
        Frame(await Send(session, "0F1A000000000000000200000000", reconnect ? 1u : 3u));
        var info = Frame(await Send(session, "032200000002", reconnect ? 2u : 4u));
        Assert.Equal(0u, BinaryPrimitives.ReadUInt32BigEndian(info.AsSpan(26)));
    }

    private static byte[] Frame(NaturalAuthoritySessionResult result)
    {
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        var decoded = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, Key, 0);
        Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
        return decoded.Payload!;
    }

    // Only storage is substituted; session decrypt/authorization/encoding are real.
    private sealed class ReturnStore : OriginalWarpSessionClockTests.UnusedStore, IAccountStore
    {
        public CharacterReadRecord Character = new(2, 0, 2, 0, 0, "Pilot", "First", "Flag", 5,
            [1,2,3,4,5,6,7,8], 20, 0, 0);
        public bool FailWrite;
        public int Writes;
        private readonly HashSet<string> _processed = [];
        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid account, CancellationToken ct) =>
            Task.FromResult<IReadOnlyList<CharacterReadRecord>>([Character]);
        public override Task<IReadOnlyList<CharacterCardRecord>> ListCharacterCardsAsync(Guid account, CancellationToken ct) =>
            Task.FromResult<IReadOnlyList<CharacterCardRecord>>([]);
        public Task<OriginalReturnBaseStoreResult> SetOriginalReturnBaseAsync(Guid account, OriginalReturnBaseWrite write,
            CancellationToken ct)
        {
            if (FailWrite) throw new IOException("Storage unavailable");
            if (write.CharacterId != Character.CharacterId) throw new InvalidOperationException("CHARACTER_NOT_FOUND");
            Assert.Equal(64, write.RequestFingerprint.Length);
            // Mirrors the independently tested PostgreSQL request identity contract.
            if (!_processed.Add(write.RequestFingerprint))
                return Task.FromResult(new OriginalReturnBaseStoreResult(Character.ReturnBaseId, false, Writes));
            Writes++;
            Character = Character with { ReturnBaseId = write.ReturnBaseId };
            return Task.FromResult(new OriginalReturnBaseStoreResult(Character.ReturnBaseId, true, Writes));
        }
    }

    private static Task<NaturalAuthoritySessionResult> Send(NaturalAuthoritySession session, string hex, uint sequence) =>
        session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(Convert.FromHexString(hex), Key, sequence),
            CancellationToken.None);
}
