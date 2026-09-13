using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// 撤退. A tactical warp is the only exit from a battlefield that does not
/// require winning it. Accepting 0x0404 without moving the unit left a player
/// who entered an unwinnable battle stranded, because the strategy card that
/// carries ワープ航行 is not on screen inside the tactical scene.
/// </summary>
public sealed class OriginalTacticalRetreatTests
{
    private const string WarpRequest = "0404F1234567DEADBEEF000000020100000002";

    [Fact]
    public async Task Retreat_moves_the_unit_to_its_base_grid_and_spends_the_warp_cruising()
    {
        var store = new RetreatStore(grid: 101, @base: 2);
        var (session, key) = await Session(store);
        var result = await Warp(session, key, 2);

        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        Assert.Equal((ushort)0x0404, result.ObservedApplicationType);
        Assert.Contains("tactical-warp-retreated;unit=2;source-cell=101;destination-cell=102",
            result.ResponseMetadata);
        Assert.Equal((ushort)0x0425, ResponseType(result, key));
        Assert.Equal((101u, 102u), store.LastMove);
        // The withdrawal belongs to the base it returned to, not to open space
        // beside it: the client refuses 態勢変更 when a unit has no base.
        Assert.Equal(2u, store.LastDestinationBase);
        Assert.Equal(2u, store.Unit.BaseId);
        // The withdrawal is a warp, so it costs the same cruising a strategic
        // one does; a free retreat would be an unpriced escape.
        Assert.Equal((102u, 9f), (store.Unit.CurrentCellId, store.Unit.Cruising));
    }

    [Fact]
    public async Task Retreat_from_the_units_own_base_grid_is_accepted_without_a_move()
    {
        var store = new RetreatStore(grid: 102, @base: 2);
        var (session, _) = await Session(store, grid: 102);
        var result = await Warp(session, new byte[16], 2);

        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        Assert.Contains("tactical-warp-accepted;unit=2;grid=102", result.ResponseMetadata);
        Assert.Null(store.LastMove);
        Assert.Equal((102u, 10f), (store.Unit.CurrentCellId, store.Unit.Cruising));
    }

    /// <summary>
    /// An intent the durable log has already consumed must not withdraw the
    /// unit a second time. The session refuses instead of reporting a
    /// withdrawal it did not perform.
    /// </summary>
    [Fact]
    public async Task A_retreat_whose_intent_was_already_consumed_is_refused()
    {
        var store = new RetreatStore(grid: 101, @base: 2) { Replay = true };
        var (session, key) = await Session(store);
        var replayed = await Warp(session, key, 2);
        Assert.Equal(NaturalAuthoritySessionStatus.Invalid, replayed.Status);
        Assert.Equal("original.tactical-warp.replay", replayed.ErrorCode);
        Assert.Equal(0, store.Moves);
        Assert.Equal(101u, store.Unit.CurrentCellId);
    }

    private static async Task<(NaturalAuthoritySession Session, byte[] Key)> Session(
        RetreatStore store, uint grid = 101)
    {
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var catalog = OriginalBattlefieldCatalog.Load(Path.Combine(
            AppContext.BaseDirectory, "battlefields", "fleet-skirmish.json"));
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(
            TimeProvider.System, key, catalog: catalog, store: store);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridCellId", grid);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridBaseId", 2u);
        OriginalWarpSessionClockTests.SetField(session, "_createdCharacter",
            new OriginalCreateCharacterCommand(4, 2, 2, 0, 0, "Pilot", "First", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, 0, "Flag", 0, [], ReturnBaseId: 2));
        await OriginalWarpPowerGateTests.SetPower(session, key, 50);
        return (session, key);
    }

    private static Task<NaturalAuthoritySessionResult> Warp(
        NaturalAuthoritySession session, byte[] key, uint sequence) =>
        session.ProcessAsync(0x0030, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString(WarpRequest), key, sequence), CancellationToken.None);

    private static ushort ResponseType(NaturalAuthoritySessionResult result, byte[] key) =>
        BinaryPrimitives.ReadUInt16BigEndian(OriginalClientInnerFrameCodec
            .Decode(result.ResponsePayload!, key, 0).Payload!.AsSpan(4));

    private sealed class RetreatStore(uint grid, uint @base)
        : OriginalWarpSessionClockTests.UnusedStore, IAccountStore
    {
        public OriginalGridUnitRecord Unit = new(2, 2,
            OriginalAuthoredPlayableCatalog.AuthorityCardId, grid, 1, BaseId: @base, Mode: 6);
        public (uint Source, uint Destination)? LastMove;
        public uint LastDestinationBase;
        public int Moves;
        public bool Replay;

        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid a, CancellationToken c) =>
            Task.FromResult<IReadOnlyList<CharacterReadRecord>>([
                new(2, 0, 2, 0, 0, "Pilot", "First", "Flag", 5, [1, 2, 3, 4, 5, 6, 7, 8], 20, 0, 0)]);

        public Task<OriginalGridUnitRecord?> FindOriginalGridUnitAsync(
            Guid a, long character, uint unit, CancellationToken c) =>
            Task.FromResult<OriginalGridUnitRecord?>(character == 2 && unit == 2 ? Unit : null);

        public Task<OriginalMoveGridStoreResult> MoveOriginalGridUnitAsync(
            Guid a, OriginalMoveGridWrite w, CancellationToken c)
        {
            if (Replay)
                return Task.FromResult(new OriginalMoveGridStoreResult(
                    OriginalMoveGridStoreStatus.Replayed, Unit, Unit.AuthorityVersion, null));
            LastMove = (w.SourceCellId, w.DestinationCellId);
            LastDestinationBase = w.DestinationBaseId;
            Moves++;
            Unit = Unit with
            {
                CurrentCellId = w.DestinationCellId,
                BaseId = w.DestinationBaseId,
                Cruising = Unit.Cruising - 1,
                AuthorityVersion = Unit.AuthorityVersion + 1,
            };
            return Task.FromResult(new OriginalMoveGridStoreResult(
                OriginalMoveGridStoreStatus.Moved, Unit, Unit.AuthorityVersion, null));
        }
    }
}
