using System.Net;
using Logh7.Server.Authority;
using Logh7.Server.Hosting;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Security;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

[CollectionDefinition("Headless catalog environment", DisableParallelization = true)]
public sealed class HeadlessCatalogCollection { }

[Collection("Headless catalog environment")]
public sealed class OriginalHeadlessBootstrapTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Server_start_restores_and_fights_fleets_without_any_client_connection()
    {
        var ct = TestContext.Current.CancellationToken;
        var connection = Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema = "headless_" + Guid.NewGuid().ToString("N");
        await using (var admin = NpgsqlDataSource.Create(connection))
        await using (var create = admin.CreateCommand("CREATE SCHEMA " + schema))
            await create.ExecuteNonQueryAsync(ct);
        await using var data = NpgsqlDataSource.Create(new NpgsqlConnectionStringBuilder(connection)
            { SearchPath = schema }.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data, PostgresMigrationRunner.MigrationDirectory, ct);
        var previous = Environment.GetEnvironmentVariable("LOGH7_BATTLEFIELD_CATALOG");
        Environment.SetEnvironmentVariable("LOGH7_BATTLEFIELD_CATALOG",
            Path.Combine(AppContext.BaseDirectory, "battlefields", "fleet-skirmish.json"));
        try
        {
            var store = new PostgresAccountStore(data);
            var receipt = new MetadataOnlyGatewayReceipt(TimeProvider.System);
            var handoffs = new HandoffRegistry(TimeProvider.System, TimeSpan.FromMinutes(1));
            var login = new OriginalLoginAuthority(new AccountAuthority(store, new Argon2PasswordHasher()), handoffs, receipt);
            var path = Path.Combine(Path.GetTempPath(), "headless-" + Guid.NewGuid().ToString("N") + ".jsonl");
            await using var server = new NaturalAuthorityServer(
                new(IPAddress.Loopback, 0, IPAddress.Loopback, 47900, path), login, handoffs, store, receipt);
            await server.StartAsync(ct);
            await using var rows = data.CreateCommand("SELECT count(*) FROM original_fleet_unit");
            Assert.True((long)(await rows.ExecuteScalarAsync(ct))! > 0,
                "Server started without restoring any authored fleet; no client should be required");
            var deadline = DateTime.UtcNow.AddSeconds(15);
            long damagedSides = 0;
            while (DateTime.UtcNow < deadline && damagedSides < 2)
            {
                await using var damage = data.CreateCommand(
                    "SELECT count(DISTINCT power) FROM original_fleet_unit WHERE damaged > 0");
                damagedSides = (long)(await damage.ExecuteScalarAsync(ct))!;
                if (damagedSides < 2) await Task.Delay(100, ct);
            }
            Assert.Equal(2, damagedSides);
            await server.StopAsync(ct);
            Assert.DoesNotContain("connection-accepted", File.ReadAllText(path));
            Assert.Contains("npc-ai", File.ReadAllText(path));

            // A surviving, undamaged unit may change grids without fighting.
            // Startup must not interpret it as an untouched authored spawn.
            var fleetStore = new PostgresFleetUnitStore(data);
            var savedRows = new List<OriginalFleetUnitRecord>();
            foreach (var grid in await fleetStore.ReadOccupiedGridsAsync(ct))
                savedRows.AddRange(await fleetStore.ReadGridAsync(grid, ct));
            var moved = savedRows[0] with { GridId = 105, X = 123, Y = 45, Damaged = 0, Destroyed = 0 };
            var defeated = savedRows[1] with { GridId = 106, Damaged = savedRows[1].Number,
                Destroyed = savedRows[1].Number };
            Assert.True(await fleetStore.SaveAsync(moved, ct));
            Assert.True(await fleetStore.SaveAsync(defeated, ct));
            var restartPath = Path.Combine(Path.GetTempPath(), "headless-restart-" + Guid.NewGuid().ToString("N") + ".jsonl");
            await using var restarted = new NaturalAuthorityServer(
                new(IPAddress.Loopback, 0, IPAddress.Loopback, 47900, restartPath), login, handoffs, store, receipt);
            await restarted.StartAsync(ct);
            await restarted.StopAsync(ct);
            Assert.Equal(moved with { Revision = moved.Revision + 1 },
                Assert.Single(await fleetStore.ReadGridAsync(105, ct)));
            Assert.Equal(defeated with { Revision = defeated.Revision + 1 },
                Assert.Single(await fleetStore.ReadGridAsync(106, ct)));
            var registry = (OriginalTacticalBattleRegistry)typeof(NaturalAuthorityServer)
                .GetField("_battles", System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!
                .GetValue(restarted)!;
            Assert.Equal(123f, registry.NpcSnapshot(105, moved.UnitId)!.Ship.X);
            Assert.NotNull(registry.NpcSnapshot(106, defeated.UnitId));
        }
        finally { Environment.SetEnvironmentVariable("LOGH7_BATTLEFIELD_CATALOG", previous); }
    }
}
