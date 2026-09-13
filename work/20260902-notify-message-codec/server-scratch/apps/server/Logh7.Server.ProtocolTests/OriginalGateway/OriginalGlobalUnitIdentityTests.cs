using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalGlobalUnitIdentityTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(2)]
    public async Task Player_and_ordinary_unit_cannot_claim_same_identity(int ordering)
    {
        var connection = Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema = "unit_identity_" + Guid.NewGuid().ToString("N");
        await using (var admin = NpgsqlDataSource.Create(connection))
        await using (var create = admin.CreateCommand("CREATE SCHEMA " + schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var config = new NpgsqlConnectionStringBuilder(connection) { SearchPath = schema };
        await using var data = NpgsqlDataSource.Create(config.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data, PostgresMigrationRunner.MigrationDirectory,
            TestContext.Current.CancellationToken);
        var account = Guid.NewGuid();
        await using (var seed = data.CreateCommand("""
            INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
            VALUES($1,'identity',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64))
            """))
        {
            seed.Parameters.AddWithValue(account);
            await seed.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        }
        async Task<bool> Player()
        {
            await using var insert = data.CreateCommand("""
                INSERT INTO character(character_id,account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                    last_name,first_name,flagship_name,face,ability_values,authority_version)
                OVERRIDING SYSTEM VALUE VALUES(700,$1,0,repeat('1',64),repeat('2',64),2,0,0,
                    'Pilot','First','Ship',5,ARRAY[1,2,3,4,5,6,7,8]::smallint[],1)
                """);
            insert.Parameters.AddWithValue(account);
            return await Insert(insert);
        }
        async Task<bool> Fleet()
        {
            await using var insert = data.CreateCommand("""
                INSERT INTO original_fleet_unit(unit_id,outfit_id,kind,power,camp,grid_id,unit_number,x,y,z,direction,cruising)
                VALUES(700,2113929312,56,2,0,101,300,0,0,0,0,10)
                """);
            return await Insert(insert);
        }
        static async Task<bool> Insert(NpgsqlCommand command)
        {
            try { await command.ExecuteNonQueryAsync(TestContext.Current.CancellationToken); return true; }
            catch (PostgresException error)
            {
                Assert.Equal("23505", error.SqlState);
                Assert.Equal("original_unit_identity_namespace", error.ConstraintName);
                return false;
            }
        }
        bool[] outcomes;
        if (ordering == 0) outcomes = [await Player(), await Fleet()];
        else if (ordering == 1) outcomes = [await Fleet(), await Player()];
        else outcomes = await Task.WhenAll(Player(), Fleet());
        Assert.Single(outcomes, succeeded => succeeded);
        await using var count = data.CreateCommand("""
            SELECT (SELECT count(*) FROM original_grid_unit WHERE unit_id=700) +
                   (SELECT count(*) FROM original_fleet_unit WHERE unit_id=700)
            """);
        Assert.Equal(1L, (long)(await count.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
    }
}
