using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalFleetRosterPostgresTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Ordinary_units_persist_without_fake_characters_and_enforce_casualty_and_control_bounds()
    {
        var connection = Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema = "fleet_roster_" + Guid.NewGuid().ToString("N");
        await using (var admin = NpgsqlDataSource.Create(connection))
        await using (var create = admin.CreateCommand("CREATE SCHEMA " + schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var config = new NpgsqlConnectionStringBuilder(connection) { SearchPath = schema };
        await using var data = NpgsqlDataSource.Create(config.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data, PostgresMigrationRunner.MigrationDirectory,
            TestContext.Current.CancellationToken);
        await using (var exists = data.CreateCommand("SELECT to_regclass('original_fleet_unit') IS NOT NULL"))
            Assert.True((bool)(await exists.ExecuteScalarAsync(TestContext.Current.CancellationToken))!,
                "Ordinary fleet roster storage is missing");
        await using (var insert = data.CreateCommand("""
            INSERT INTO original_fleet_unit(unit_id,outfit_id,kind,power,camp,grid_id,unit_number,
                damaged,destroyed,x,y,z,direction,cruising)
            VALUES(2113929217,2113929312,56,2,0,101,300,25,10,1.5,2.5,0,0,10),
                  (2113929218,2113929312,56,2,0,101,300,0,0,3,4,0,0,10)
            """))
            Assert.Equal(2, await insert.ExecuteNonQueryAsync(TestContext.Current.CancellationToken));
        await using var reopened = NpgsqlDataSource.Create(config.ConnectionString);
        await using (var read = reopened.CreateCommand("""
            SELECT count(*),sum(unit_number-destroyed),count(controller_character_id)
            FROM original_fleet_unit
            """))
        await using (var rows = await read.ExecuteReaderAsync(TestContext.Current.CancellationToken))
        {
            Assert.True(await rows.ReadAsync(TestContext.Current.CancellationToken));
            Assert.Equal(2L, rows.GetInt64(0));
            Assert.Equal(590L, rows.GetInt64(1));
            Assert.Equal(0L, rows.GetInt64(2));
        }
        await using (var characters = data.CreateCommand("SELECT count(*) FROM character"))
            Assert.Equal(0L, (long)(await characters.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
        foreach (var update in new[] {
            "damaged=301", "destroyed=26", "unit_number=0", "autonomous=false",
            "controller_character_id=999", "x='NaN'::real", "direction='Infinity'::real" })
        {
            await using var invalid = data.CreateCommand("UPDATE original_fleet_unit SET " + update + " WHERE unit_id=2113929217");
            await Assert.ThrowsAsync<PostgresException>(() => invalid.ExecuteNonQueryAsync(TestContext.Current.CancellationToken));
        }
        await using var preserved = reopened.CreateCommand("SELECT damaged FROM original_fleet_unit WHERE unit_id=2113929217");
        Assert.Equal(25, (int)(await preserved.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
    }
}
