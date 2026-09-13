using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalWarehouseSessionTests
{
    private static readonly byte[] Key = new byte[16];
    private static readonly Guid Account = Guid.Parse("865662fc-e880-44b9-b38f-586f30dad4bd");

    [Fact]
    public async Task Encrypted_query_projects_independent_persisted_counters_without_writing()
    {
        var store = new StockStore();
        var session = Open(store);
        var result = await Send(session, "03260000000100000000");
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        var frame = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, Key, 0).Payload!;
        Assert.True(OriginalWarehouseCodec.TryDecodeResponse(frame.AsSpan(4), out var response));
        Assert.Equal(1u, response.BaseId);
        Assert.Equal(0u, response.OutfitId);
        Assert.Equal(0u, response.Index); // Explicit NEW_DESIGN placeholder, not database version 17.
        Assert.Equal(new OriginalWarehouseShip(7, 3, 500), response.Ships[0]);
        Assert.Equal(new OriginalWarehouseShip(8, 0, 9), response.Ships[1]);
        Assert.Equal(new OriginalWarehouseTroop(9, 2, 65535), Assert.Single(response.Troops));
        Assert.Equal(uint.MaxValue, response.Supplies);
        Assert.Equal(0u, response.Food);
        Assert.Equal(23u, response.Mineral);
        Assert.Contains("warehouse-version=17", result.ResponseMetadata);
        Assert.Equal(1, store.Reads);
    }

    [Theory]
    [InlineData("0326")]
    [InlineData("0326000000010000000000")]
    [InlineData("03260000000000000000")]
    public async Task Invalid_request_is_rejected_before_storage(string hex)
    {
        var store = new StockStore();
        var result = await Send(Open(store), hex);
        Assert.NotEqual(NaturalAuthoritySessionStatus.Success, result.Status);
        Assert.Equal(0, store.Reads);
    }

    [Fact]
    public async Task World_entry_is_required_before_a_stock_read()
    {
        var store = new StockStore();
        var session = Open(store);
        OriginalWarpSessionClockTests.SetField(session, "_worldEntered", false);
        var result = await Send(session, "03260000000100000000");
        Assert.NotEqual(NaturalAuthoritySessionStatus.Success, result.Status);
        Assert.Equal(0, store.Reads);
    }

    [Fact]
    public async Task Missing_grant_does_not_fabricate_an_empty_warehouse()
    {
        var store = new StockStore { Denied = true };
        var result = await Send(Open(store), "03260000000100000000");
        Assert.NotEqual(NaturalAuthoritySessionStatus.Success, result.Status);
        Assert.Equal("original.warehouse.access-denied", result.ErrorCode);
        Assert.Null(result.ResponsePayload);
    }

    [Fact]
    public async Task Storage_failure_is_not_replaced_by_successful_stock_data()
    {
        var store = new StockStore { Failure = true };
        await Assert.ThrowsAsync<IOException>(() => Send(Open(store), "03260000000100000000"));
    }

    private static NaturalAuthoritySession Open(StockStore store)
    {
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, Key, store: store);
        OriginalWarpSessionClockTests.SetField(session, "_accountId", Account);
        return session;
    }

    private static Task<NaturalAuthoritySessionResult> Send(NaturalAuthoritySession session, string hex) =>
        session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(Convert.FromHexString(hex), Key, 1), CancellationToken.None);

    private sealed class StockStore : OriginalWarpSessionClockTests.UnusedStore, IAccountStore
    {
        public int Reads;
        public bool Denied;
        public bool Failure;
        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid a, CancellationToken c) =>
            Task.FromResult<IReadOnlyList<CharacterReadRecord>>([new(2, 0, 2, 0, 0, "Pilot", "First", "Ship", 5,
                [1,2,3,4,5,6,7,8], 20, 0, 0, 2)]);
        public override Task<IReadOnlyList<CharacterCardRecord>> ListCharacterCardsAsync(Guid a, CancellationToken c) =>
            Task.FromResult<IReadOnlyList<CharacterCardRecord>>([]);
        public Task<OriginalWarehouseSnapshot> ReadOriginalWarehouseAsync(Guid a, long characterId,
            OriginalWarehouseKey key, CancellationToken cancellationToken)
        {
            Reads++;
            Assert.Equal(Account, a);
            Assert.Equal(2, characterId);
            Assert.Equal(new OriginalWarehouseKey(1, 0), key);
            if (Denied) throw new InvalidOperationException("WAREHOUSE_ACCESS_DENIED");
            if (Failure) throw new IOException("Test read failure");
            return Task.FromResult(new OriginalWarehouseSnapshot(key, 17, new Dictionary<OriginalStockKey, long>
            {
                [new(OriginalStockKind.ShipUnits, 7)] = 3,
                [new(OriginalStockKind.ShipBoats, 7)] = 500,
                [new(OriginalStockKind.ShipBoats, 8)] = 9,
                [new(OriginalStockKind.Troops, 9, 2)] = 65535,
                [new(OriginalStockKind.Supplies)] = uint.MaxValue,
                [new(OriginalStockKind.Mineral)] = 23
            }));
        }
    }
}
