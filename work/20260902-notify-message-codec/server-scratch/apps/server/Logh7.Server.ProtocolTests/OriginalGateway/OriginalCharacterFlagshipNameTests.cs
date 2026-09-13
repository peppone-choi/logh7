using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalCharacterFlagshipNameTests
{
    [Fact]
    public async Task Unnamed_authored_enemy_does_not_copy_the_viewers_flagship_name()
    {
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(
            TimeProvider.System, key, store: new NamedCharacterStore());
        var result = await session.ProcessAsync(0x0030,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("03227F000002"), key, 1),
            CancellationToken.None);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        var decoded = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, key, 0);
        Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
        var frame = decoded.Payload!;
        Assert.Equal(0x7f000002u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(6)));
        Assert.Equal(0x7f000001u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(38)));
        Assert.Equal(0, frame[42]);
        Assert.Equal(new byte[8], frame[55..63]); // viewer balances must not leak to enemy
    }

    [Fact]
    public async Task Fresh_sessions_restore_the_store_name_into_encrypted_character_responses()
    {
        // Store-boundary fixture, not PostgreSQL/login/native evidence.
        // No preloaded _createdCharacter: the real restoration branch must run.
        var store = new NamedCharacterStore();
        for (var connection = 0; connection < 2; connection++)
        {
            var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
            var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(
                TimeProvider.System, key, store: store);
            var result = await session.ProcessAsync(0x0030,
                OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("032200000002"), key, 1),
                CancellationToken.None);
            Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
            var decoded = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, key, 0);
            Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
            var frame = decoded.Payload!;
            Assert.Equal(0x0323, BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)));
            Assert.Equal(2u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(6)));
            Assert.Equal(2u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(38)));
            Assert.Equal(Convert.FromHexString("034FDD5B588266"), frame[42..49]);
            Assert.Equal(321u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(61)));
            Assert.Equal(uint.MaxValue, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(65)));
        }
    }

    private sealed class NamedCharacterStore : OriginalWarpSessionClockTests.UnusedStore
    {
        public uint Pcp = 321;
        public uint Mcp = uint.MaxValue;
        public short Rank = 20;
        public uint Achievement;
        public bool HasCharacter = true;
        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(
            Guid accountId, CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<CharacterReadRecord>>(HasCharacter ? [
                new CharacterReadRecord(2, 0, 2, 0, 0, "Last", "First", "保存艦", 5,
                    [1, 2, 3, 4, 5, 6, 7, 8], Rank, 0, 0, Pcp: Pcp, Mcp: Mcp, Achievement: Achievement)] : []);

        public override Task<IReadOnlyList<CharacterCardRecord>> ListCharacterCardsAsync(
            Guid accountId, CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<CharacterCardRecord>>([]);
    }

    [Fact]
    public async Task Existing_session_refreshes_balances_after_authority_changes()
    {
        var store = new NamedCharacterStore();
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,store:store);
        var first = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("032200000002"),key,1),
            TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,first.Status);
        store.Pcp = 161;
        store.Mcp = 23;
        store.Rank = 7;
        store.Achievement = 1234;
        var second = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("032200000002"),key,2),
            TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,second.Status);
        var decoded = OriginalClientInnerFrameCodec.Decode(second.ResponsePayload!,key,0);
        Assert.Equal(OriginalClientInnerFrameStatus.Success,decoded.Status);
        Assert.Equal(161u,BinaryPrimitives.ReadUInt32BigEndian(decoded.Payload!.AsSpan(61)));
        Assert.Equal(23u,BinaryPrimitives.ReadUInt32BigEndian(decoded.Payload.AsSpan(65)));
        var refreshedFrame = decoded.Payload!;
        Assert.Equal((ushort)7, BinaryPrimitives.ReadUInt16BigEndian(refreshedFrame.AsSpan(refreshedFrame.Length - 62)));
        Assert.Equal(1234u, BinaryPrimitives.ReadUInt32BigEndian(refreshedFrame.AsSpan(refreshedFrame.Length - 47)));
        store.HasCharacter = false;
        var missing = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("032200000002"),key,3),
            TestContext.Current.CancellationToken);
        Assert.NotEqual(NaturalAuthoritySessionStatus.Success,missing.Status);
        Assert.Null(missing.ResponsePayload);
    }

    [Theory]
    [InlineData("", "00")]
    [InlineData("X", "010058")]
    [InlineData("テスト艦", "0430C630B930C88266")]
    [InlineData("ABCDEFGHIJKLM", "0D004100420043004400450046004700480049004A004B004C004D")]
    public void Information_character_preserves_flagship_name_without_shifting_other_fields(
        string name, string expectedPstrHex)
    {
        var command = new OriginalCreateCharacterCommand(
            4, 7, 2, 0, 0, "Last", "First", 20, 1, 1, 5,
            [1, 2, 3, 4, 5, 6, 7, 8], 0, 0, 0, 20, 0, 0, name, 0, []);

        var frame = OriginalWorldEntryCodec.EncodeCharacter(7, 0x10203040, 9, command);

        // Original 00417390 packed reader: 36 bytes precede this pstr.
        // 00419300 labels expanded +24 flagship, +28 count / +2A name.
        // Frame prefix is u32 message-code + u16 message type.
        const int nameOffset = 42;
        var expectedPstr = Convert.FromHexString(expectedPstrHex);
        Assert.Equal(0x0323, BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)));
        Assert.Equal(0x10203040u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(38)));
        Assert.Equal(expectedPstr, frame[nameOffset..(nameOffset + expectedPstr.Length)]);

        // A name update must not corrupt any following variable-width field.
        var empty = OriginalWorldEntryCodec.EncodeCharacter(7, 0x10203040, 9,
            command with { FlagshipName = string.Empty });
        Assert.Equal(empty[..nameOffset], frame[..nameOffset]);
        Assert.Equal(empty[(nameOffset + 1)..], frame[(nameOffset + expectedPstr.Length)..]);
        Assert.Equal(empty.Length + expectedPstr.Length - 1, frame.Length);
    }
}
