using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// A warp costs 320 MCP, so regeneration has to actually run somewhere or the
/// player eventually cannot move at all. It is settled on the 情報 query the
/// HUD draws from, and the answer carries the settled balances.
/// </summary>
public sealed class OriginalCommandPointRegenerationSessionTests
{
    private const string InformationCharacterRequest = "032200000002";

    [Fact]
    public async Task The_character_query_settles_regeneration_and_serves_the_new_balances()
    {
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var store = new PointStore { Political = 1234, Military = 5678 };
        var (session, clock) = Session(key, store);

        var served = await Query(session, key, 1);

        Assert.Equal(NaturalAuthoritySessionStatus.Success, served.Status);
        Assert.Equal(1, store.Settlements);
        // The fixture's grid holds an enemy, and the approved policy suspends
        // regeneration inside a tactical battle, so the session must say so.
        Assert.Equal((clock.GetUtcNow(), true), store.LastSettlement);
        // ORIGINAL_STATIC 00419300: the served character body carries PCP then
        // MCP as adjacent big-endian u32 after the flagship name.
        var frame = OriginalClientInnerFrameCodec.Decode(served.ResponsePayload!, key, 0).Payload!;
        Assert.Contains(Balances(1234, 5678), Windows(frame, 8));
    }

    /// <summary>
    /// The client polls this query; opening a locking transaction on every poll
    /// would be the wrong price for a passive effect. The grant is computed from
    /// the stored timestamp, so a throttled attempt loses nothing.
    /// </summary>
    [Fact]
    public async Task Repeated_polls_do_not_open_a_settlement_transaction_each_time()
    {
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var store = new PointStore { Political = 10, Military = 20 };
        var (session, clock) = Session(key, store);

        await Query(session, key, 1);
        clock.Advance(TimeSpan.FromSeconds(5));
        await Query(session, key, 2);
        Assert.Equal(1, store.Settlements);

        clock.Advance(TimeSpan.FromSeconds(30));
        await Query(session, key, 3);
        Assert.Equal(2, store.Settlements);
    }

    /// <summary>
    /// A store from before the economy must keep serving the character. The
    /// balances simply stay where they are.
    /// </summary>
    [Fact]
    public async Task A_store_without_the_economy_still_answers_the_query()
    {
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var (session, _) = Session(key, new RosterOnlyStore());

        var served = await Query(session, key, 1);

        Assert.Equal(NaturalAuthoritySessionStatus.Success, served.Status);
    }


    /// <summary>
    /// Out of a battle the same query settles with the tactical flag clear, so
    /// the approved policy actually grants the elapsed intervals.
    /// </summary>
    [Fact]
    public async Task Quiet_space_settles_with_regeneration_running()
    {
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var store = new PointStore { Political = 1, Military = 2 };
        var catalog = OriginalBattlefieldCatalog.Load(Path.Combine(
            AppContext.BaseDirectory, "battlefields", "fleet-skirmish.json"));
        var clock = new TestClock();
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(clock, key, catalog: catalog,
            store: store, gameClock: new OriginalGameClock(clock));
        OriginalWarpSessionClockTests.SetField(session, "_worldGridCellId", 102u);
        OriginalWarpSessionClockTests.SetField(session, "_createdCharacter",
            new OriginalCreateCharacterCommand(4, 2, 2, 0, 0, "Pilot", "First", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, 0, "Flag", 0, []));

        Assert.Equal(NaturalAuthoritySessionStatus.Success, (await Query(session, key, 1)).Status);
        Assert.Equal((clock.GetUtcNow(), false), store.LastSettlement);
    }


    /// <summary>
    /// The client polls the clock on its own and nothing else, so regeneration
    /// has to be settled there too or a player who never opens the card would
    /// never get their points back.
    /// </summary>
    [Fact]
    public async Task The_clock_request_settles_regeneration_too()
    {
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var store = new PointStore { Political = 7, Military = 8 };
        var (session, clock) = Session(key, store);

        var tick = await session.ProcessAsync(0x0030,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0300"), key, 1),
            CancellationToken.None);

        Assert.Equal(NaturalAuthoritySessionStatus.Success, tick.Status);
        Assert.Contains("epoch=server-process", tick.ResponseMetadata);
        Assert.Equal(1, store.Settlements);
        Assert.Equal(clock.GetUtcNow(), store.LastSettlement.Now);
    }

    private static (NaturalAuthoritySession Session, TestClock Clock) Session(byte[] key, IAccountStore store)
    {
        var clock = new TestClock();
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(clock, key, store: store,
            gameClock: new OriginalGameClock(clock));
        OriginalWarpSessionClockTests.SetField(session, "_createdCharacter",
            new OriginalCreateCharacterCommand(4, 2, 2, 0, 0, "Pilot", "First", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, 0, "Flag", 0, []));
        return (session, clock);
    }

    private static Task<NaturalAuthoritySessionResult> Query(
        NaturalAuthoritySession session, byte[] key, uint sequence) =>
        session.ProcessAsync(0x0030, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString(InformationCharacterRequest), key, sequence), CancellationToken.None);

    private static byte[] Balances(uint political, uint military)
    {
        var expected = new byte[8];
        BinaryPrimitives.WriteUInt32BigEndian(expected, political);
        BinaryPrimitives.WriteUInt32BigEndian(expected.AsSpan(4), military);
        return expected;
    }

    private static IEnumerable<byte[]> Windows(byte[] frame, int width)
    {
        for (var offset = 0; offset + width <= frame.Length; offset++)
            yield return frame[offset..(offset + width)];
    }

    private sealed class TestClock : TimeProvider
    {
        private long _timestamp;
        private DateTimeOffset _now = new(2026, 9, 9, 0, 0, 0, TimeSpan.Zero);
        public override long TimestampFrequency => TimeSpan.TicksPerSecond;
        public override long GetTimestamp() => _timestamp;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan span)
        {
            _timestamp += span.Ticks;
            _now += span;
        }
    }

    private class RosterOnlyStore : OriginalWarpSessionClockTests.UnusedStore
    {
        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid a, CancellationToken c) =>
            Task.FromResult<IReadOnlyList<CharacterReadRecord>>([
                new(2, 0, 2, 0, 0, "Pilot", "First", "Flag", 5, [1, 2, 3, 4, 5, 6, 7, 8], 20, 0, 0)]);
    }

    private sealed class PointStore : RosterOnlyStore, IAccountStore
    {
        public uint Political;
        public uint Military;
        public int Settlements;
        public (DateTimeOffset Now, bool InTactics) LastSettlement;

        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid a, CancellationToken c) =>
            Task.FromResult<IReadOnlyList<CharacterReadRecord>>([
                new(2, 0, 2, 0, 0, "Pilot", "First", "Flag", 5, [1, 2, 3, 4, 5, 6, 7, 8], 20, 0, 0,
                    Pcp: Political, Mcp: Military)]);

        public Task<OriginalCommandPointState> AccrueCommandPointsAsync(Guid account, long character,
            OriginalCommandPointPolicy policy, DateTimeOffset now, bool inTactics, CancellationToken c)
        {
            Assert.Equal(2, character);
            Settlements++;
            LastSettlement = (now, inTactics);
            return Task.FromResult(new OriginalCommandPointState(Political, Military, 0, 0, 1, true));
        }
    }
}
