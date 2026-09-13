using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalFleetRepairStoreTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Repair_consumes_supplies_and_restores_only_surviving_damaged_hulls_on_reopen()
    {
        await using var data = await CreateAsync();
        var store = new PostgresFleetUnitStore(data);
        var first = Unit(101) with { Damaged = 275, Destroyed = 10, Supplies = uint.MaxValue };
        var second = Unit(102) with { Damaged = 300, Destroyed = 300, Supplies = 23 };
        await SeedAsync(store, first, second);
        Assert.True(await store.RepairOrdinaryUnitsAsync([second, first], TestContext.Current.CancellationToken));
        await using var reopened = NpgsqlDataSource.Create(data.ConnectionString);
        Assert.Equal(new[] { first with { Damaged = 10, Supplies = 0, Revision = 2 },
            second with { Supplies = 0, Revision = 2 } },
            await new PostgresFleetUnitStore(reopened).ReadGridAsync(101, TestContext.Current.CancellationToken));
        Assert.False(await store.RepairOrdinaryUnitsAsync([first, second], TestContext.Current.CancellationToken));
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData("revision")]
    [InlineData("generation")]
    [InlineData("grid")]
    [InlineData("outfit")]
    [InlineData("missing")]
    public async Task Conflict_on_later_unit_rolls_back_earlier_repair_and_supplies(string conflict)
    {
        await using var data = await CreateAsync();
        var store = new PostgresFleetUnitStore(data);
        var first = Unit(101);
        var second = Unit(102);
        await SeedAsync(store, first, second);
        var requested = conflict switch
        {
            "revision" => second with { Revision = 0 },
            "generation" => second with { Generation = 1 },
            "grid" => second with { GridId = 102 },
            "outfit" => second with { OutfitId = 8 },
            _ => second with { UnitId = 103 }
        };
        Assert.False(await store.RepairOrdinaryUnitsAsync([first, requested], TestContext.Current.CancellationToken));
        Assert.Equal(new[] { first, second }, await store.ReadGridAsync(101, TestContext.Current.CancellationToken));
        // A rolled-back transaction must release locks and leave the original versions usable.
        Assert.True(await store.RepairOrdinaryUnitsAsync([first, second], TestContext.Current.CancellationToken));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Duplicate_and_empty_batches_do_not_consume_resources()
    {
        await using var data = await CreateAsync();
        var store = new PostgresFleetUnitStore(data);
        var unit = Unit(101);
        await SeedAsync(store, unit);
        Assert.False(await store.RepairOrdinaryUnitsAsync([], TestContext.Current.CancellationToken));
        Assert.False(await store.RepairOrdinaryUnitsAsync([unit, unit], TestContext.Current.CancellationToken));
        Assert.Equal(unit, Assert.Single(await store.ReadGridAsync(101, TestContext.Current.CancellationToken)));
        Assert.True(await store.RepairOrdinaryUnitsAsync([unit], TestContext.Current.CancellationToken));
    }

    private static OriginalFleetUnitRecord Unit(uint id) =>
        new(id, 7, 56, 2, 0, 101, 300, 275, 0, 1, 2, 3, 0.75f, 7, Supplies: 100);

    private static async Task SeedAsync(PostgresFleetUnitStore store, params OriginalFleetUnitRecord[] units)
    {
        foreach (var unit in units)
            await store.EnsureCreatedAsync(unit, TestContext.Current.CancellationToken);
    }

    private static async Task<NpgsqlDataSource> CreateAsync()
    {
        var connection = Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema = "fleet_repair_" + Guid.NewGuid().ToString("N");
        await using (var admin = NpgsqlDataSource.Create(connection))
        await using (var create = admin.CreateCommand("CREATE SCHEMA " + schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var data = NpgsqlDataSource.Create(new NpgsqlConnectionStringBuilder(connection) { SearchPath = schema }.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data, PostgresMigrationRunner.MigrationDirectory,
            TestContext.Current.CancellationToken);
        return data;
    }
}
