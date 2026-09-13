using Logh7.Server.Authority;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalNativeReturnSetupTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;
    [Fact(Skip="Requires isolated PostgreSQL",SkipUnless=nameof(HasTestDatabase))]
    public async Task Native_setup_sets_only_the_bound_character_and_produces_the_store_compatible_hash()
    {
        var source=Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema="native_setup_test_"+Guid.NewGuid().ToString("N");
        await using(var admin=NpgsqlDataSource.Create(source))
        await using(var create=admin.CreateCommand("CREATE SCHEMA "+schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        await using var data=NpgsqlDataSource.Create(new NpgsqlConnectionStringBuilder(source){SearchPath=schema}.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data,PostgresMigrationRunner.MigrationDirectory,CancellationToken.None);
        var account=Guid.NewGuid();
        await using(var seed=data.CreateCommand("""
            INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
            VALUES($1,'native_setup',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64));
            """))
        { seed.Parameters.AddWithValue(account); await seed.ExecuteNonQueryAsync(TestContext.Current.CancellationToken); }
        long character;
        await using(var seed=data.CreateCommand("""
            -- The one-use native script is deliberately bound to unit2.
            -- Mirror its actual character2 run; do not weaken or edit that historical script.
            INSERT INTO character(character_id,account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                last_name,first_name,flagship_name,face,ability_values,authority_version)
            OVERRIDING SYSTEM VALUE VALUES(2,$1,0,repeat('1',64),repeat('2',64),2,0,0,'Pilot','First','Flag',5,ARRAY[1,2,3,4,5,6,7,8]::smallint[],1)
            RETURNING character_id
            """))
        { seed.Parameters.AddWithValue(account); character=(long)(await seed.ExecuteScalarAsync(TestContext.Current.CancellationToken))!; }
        await using var connection=await data.OpenConnectionAsync(TestContext.Current.CancellationToken);
        var fingerprint=new string('f',64);
        await using(var configure=new NpgsqlCommand("""
            SELECT set_config('logh7.setup_expected_directory',current_setting('data_directory'),false),
                   set_config('logh7.setup_character',$1,false),
                   set_config('logh7.setup_fingerprint',$2,false)
            """,connection))
        {
            configure.Parameters.AddWithValue(character.ToString(System.Globalization.CultureInfo.InvariantCulture));
            configure.Parameters.AddWithValue(fingerprint);
            await configure.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        }
        var sql=await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory,"select-test-return-base.sql"),TestContext.Current.CancellationToken);
        await using(var wrong=new NpgsqlCommand("SELECT set_config('logh7.setup_expected_directory',current_setting('data_directory')||'/wrong',false)",connection))
            await wrong.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        await using(var denied=new NpgsqlCommand(sql,connection))
        {
            var error=await Assert.ThrowsAsync<PostgresException>(()=>denied.ExecuteNonQueryAsync(TestContext.Current.CancellationToken));
            Assert.Equal("NATIVE_SETUP_WRONG_CLUSTER",error.MessageText);
        }
        await using(var rollback=new NpgsqlCommand("ROLLBACK; SELECT set_config('logh7.setup_expected_directory',current_setting('data_directory'),false)",connection))
            await rollback.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        Assert.Equal(0u,Assert.Single(await new PostgresAccountStore(data).ListCharactersAsync(account,CancellationToken.None)).ReturnBaseId);
        await using(var run=new NpgsqlCommand(sql,connection))
            await run.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var store=new PostgresAccountStore(data);
        Assert.Equal(2u,Assert.Single(await store.ListCharactersAsync(account,CancellationToken.None)).ReturnBaseId);
        await using(var state=data.CreateCommand("SELECT authority_version,authority_state_hash FROM account WHERE account_id=$1"))
        {
            state.Parameters.AddWithValue(account);
            await using var reader=await state.ExecuteReaderAsync(TestContext.Current.CancellationToken);
            Assert.True(await reader.ReadAsync(TestContext.Current.CancellationToken));
            Assert.Equal(2L,reader.GetInt64(0));
            Assert.Equal(AuthorityStateHash.OriginalReturnBaseChanged(account,2,character,2,fingerprint),reader.GetString(1).Trim());
        }
        Assert.False((await store.SetOriginalReturnBaseAsync(account,new(fingerprint,character,2),CancellationToken.None)).Updated);
        await using(var duplicate=new NpgsqlCommand(sql,connection))
        {
            var error=await Assert.ThrowsAsync<PostgresException>(()=>duplicate.ExecuteNonQueryAsync(TestContext.Current.CancellationToken));
            Assert.Equal("NATIVE_SETUP_STATE_MISMATCH",error.MessageText);
        }
        await using(var rollback=new NpgsqlCommand("ROLLBACK",connection))
            await rollback.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        await using var count=data.CreateCommand("SELECT count(*) FROM domain_event WHERE event_type='OriginalReturnBaseChanged'");
        Assert.Equal(1L,(long)(await count.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
    }
}
