using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalFleetUnitStoreTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;
    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Reopen_preserves_casualties_pose_and_revision_and_seed_never_resets_them()
    {
        var connection = Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema = "fleet_store_" + Guid.NewGuid().ToString("N");
        await using (var admin = NpgsqlDataSource.Create(connection))
        await using (var create = admin.CreateCommand("CREATE SCHEMA " + schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var config = new NpgsqlConnectionStringBuilder(connection) { SearchPath = schema };
        await using var data = NpgsqlDataSource.Create(config.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data, PostgresMigrationRunner.MigrationDirectory,
            TestContext.Current.CancellationToken);
        var store = new PostgresFleetUnitStore(data);
        Task<IReadOnlyList<uint>> Grids() => store.ReadOccupiedGridsAsync(TestContext.Current.CancellationToken);
        Assert.Empty(await Grids());
        var initial = new OriginalFleetUnitRecord(2113929217,2113929312,56,2,0,101,300,0,0,1,2,3,0,10, Supplies: uint.MaxValue);
        await store.EnsureCreatedAsync(initial, TestContext.Current.CancellationToken);
        Assert.Equal(new uint[] { 101 }, await Grids());
        Assert.Equal(initial, Assert.Single(await store.ReadGridAsync(101, TestContext.Current.CancellationToken)));
        var changed = initial with { Damaged = 30, Destroyed = 10, X = 12, Direction = 1.25f, Cruising = 7, Supplies = 0 };
        Assert.True(await store.SaveAsync(changed, TestContext.Current.CancellationToken));
        Assert.False(await store.SaveAsync(initial, TestContext.Current.CancellationToken));
        Assert.False(await store.SaveAsync(changed with { Revision = 2, Generation = 1 }, TestContext.Current.CancellationToken));
        await store.EnsureCreatedAsync(initial, TestContext.Current.CancellationToken);
        await using var reopened = NpgsqlDataSource.Create(config.ConnectionString);
        var restored = new PostgresFleetUnitStore(reopened);
        Assert.Equal(changed with { Revision = 2 }, Assert.Single(await restored.ReadGridAsync(101, TestContext.Current.CancellationToken)));
        Assert.Empty(await restored.ReadGridAsync(102, TestContext.Current.CancellationToken));
        Assert.True(await restored.SaveAsync(changed with { Revision = 2, GridId = 102 }, TestContext.Current.CancellationToken));
        Assert.Empty(await store.ReadGridAsync(101, TestContext.Current.CancellationToken));
        Assert.Equal(changed with { Revision = 3, GridId = 102 }, Assert.Single(await store.ReadGridAsync(102, TestContext.Current.CancellationToken)));
        Assert.Equal(new uint[] { 102 }, await Grids());
        await store.EnsureCreatedAsync(initial with { UnitId = initial.UnitId + 1, GridId = 102 }, TestContext.Current.CancellationToken);
        await store.EnsureCreatedAsync(initial with { UnitId = initial.UnitId + 2, GridId = 105 }, TestContext.Current.CancellationToken);
        Assert.Equal(new uint[] { 102, 105 }, await Grids());
    }
}
