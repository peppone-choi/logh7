using System.Buffers.Binary;
using System.Reflection;
using Logh7.Server.Authority;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalWarpSessionClockTests
{
    [Fact]
    public async Task Session_dates_warp_completion_from_its_injected_clock()
    {
        // The fixture starts at the authenticated/world-entered boundary.
        // It tests the real session decrypt/dispatch/encode path, not login or
        // PostgreSQL persistence. No store or account calls are allowed here.
        var time = new TestClock();
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var session = CreateWorldEnteredSession(time, key);
        await OriginalWarpPowerGateTests.SetPower(session, key, 50);
        time.Timestamp = 1000;
        var request = Convert.FromHexString("0404F1234567DEADBEEF000000020100000002");
        var encrypted = OriginalClientInnerFrameCodec.Encode(request, key, 2);

        var result = await session.ProcessAsync(0x0030, encrypted, CancellationToken.None);

        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        Assert.Equal((ushort)0x0404, result.ObservedApplicationType);
        var decoded = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, key, 0);
        Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
        Assert.Equal((ushort)0x0425, BinaryPrimitives.ReadUInt16BigEndian(decoded.Payload!.AsSpan(4)));
        Assert.Equal(24u, BinaryPrimitives.ReadUInt32BigEndian(decoded.Payload.AsSpan(6)));
        Assert.Equal(101u, BinaryPrimitives.ReadUInt32BigEndian(decoded.Payload.AsSpan(10)));
        Assert.Equal(1u, BinaryPrimitives.ReadUInt32BigEndian(decoded.Payload.AsSpan(14)));
    }

    internal static NaturalAuthoritySession CreateWorldEnteredSession(TimeProvider time, byte[] key,
        OriginalBattlefieldCatalog? catalog = null, IAccountStore? store = null,
        OriginalTacticalBattleRegistry? battles = null,
        OriginalStaticArmsTable? staticArms = null, OriginalGameClock? gameClock = null)
    {
        var receipt = new MetadataOnlyGatewayReceipt(time);
        var handoffs = new HandoffRegistry(time, TimeSpan.FromMinutes(1));
        var session = new NaturalAuthoritySession(key, 0, 0, 47900,
            new OriginalLoginAuthority(new UnusedAccountAuthority(), handoffs, receipt),
            handoffs, store ?? new UnusedStore(), receipt, gameClock ?? new OriginalGameClock(time),
            battlefieldCatalog: catalog, battles: battles, staticArms: staticArms);
        SetField(session, "_worldEntered", true);
        SetField(session, "_worldCharacterId", 2u);
        SetField(session, "_worldGridUnitId", 2u);
        SetField(session, "_clientOutboundKey", key);
        typeof(NaturalAuthoritySession).GetProperty(nameof(NaturalAuthoritySession.State))!
            .SetValue(session, NaturalAuthoritySessionState.SessionServerReady);
        return session;
    }

    internal static void SetField(object target, string name, object value) =>
        target.GetType().GetField(name, BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(target, value);

    private sealed class TestClock : TimeProvider
    {
        public long Timestamp;
        public override long TimestampFrequency => 1000;
        public override long GetTimestamp() => Timestamp;
    }

    private sealed class UnusedAccountAuthority : IAccountAuthority
    {
        public Task<LoginDecision> VerifyAsync(ReadOnlyMemory<ushort> accountElements,
            ReadOnlyMemory<ushort> passwordElements, CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Clock test must not attempt authentication");
    }

    internal class UnusedStore : IAccountStore
    {
        private static Exception Unexpected() => new InvalidOperationException("Clock test must not access storage");
        public Task<AccountRecord> ProvisionAsync(AccountProvision p, CancellationToken c) => throw Unexpected();
        public Task<AccountRecord?> FindAccountAsync(string n, CancellationToken c) => throw Unexpected();
        public Task<int> CountCharactersAsync(Guid a, CancellationToken c) => throw Unexpected();
        public virtual Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid a, CancellationToken c) => throw Unexpected();
        public Task<CharacterCreateStoreResult> CreateCharacterAsync(Guid a, CharacterCreateWrite w, CancellationToken c) => throw Unexpected();
        public virtual Task<IReadOnlyList<CharacterCardRecord>> ListCharacterCardsAsync(Guid a, CancellationToken c) => throw Unexpected();
        public Task<CardAppointmentStoreResult> AppointCardAsync(Guid a, CardAppointmentWrite w, CancellationToken c) => throw Unexpected();
        public Task<CardDismissalStoreResult> DismissCardAsync(Guid a, CardDismissalWrite w, CancellationToken c) => throw Unexpected();
        public Task<CardResignationStoreResult> ResignCardAsync(Guid a, CardResignationWrite w, int d, CancellationToken c) => throw Unexpected();
        public Task<OriginalCharacterLotteryEntryStoreResult> EnterOriginalCharacterLotteryAsync(Guid a, OriginalCharacterLotteryEntryWrite w, CancellationToken c) => throw Unexpected();
        public Task<OriginalCharacterLotteryEntryRecord?> FindPendingOriginalCharacterLotteryAsync(Guid a, CancellationToken c) => throw Unexpected();
        public Task<OriginalCharacterLotteryAwardStoreResult> AwardOriginalCharacterLotteryAsync(Guid a, OriginalCharacterLotteryAwardWrite w, CancellationToken c) => throw Unexpected();
    }
}
