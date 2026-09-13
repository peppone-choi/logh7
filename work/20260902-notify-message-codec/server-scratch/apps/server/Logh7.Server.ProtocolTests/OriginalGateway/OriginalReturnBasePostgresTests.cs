using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalReturnBasePostgresTests
{
    public static bool HasTestDatabase => !string.IsNullOrWhiteSpace(
        Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB"));

    [Fact(Skip = "Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless = nameof(HasTestDatabase))]
    public async Task Setting_is_durable_idempotent_owned_and_atomic_under_failure()
    {
        var connectionString = Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        // Only a fresh test-owned schema is written. No existing rows/schemas are deleted.
        var schema = "return_base_test_" + Guid.NewGuid().ToString("N");
        await using (var admin = NpgsqlDataSource.Create(connectionString))
        await using (var create = admin.CreateCommand("CREATE SCHEMA " + schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var builder = new NpgsqlConnectionStringBuilder(connectionString) { SearchPath = schema };
        await using var data = NpgsqlDataSource.Create(builder.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data, PostgresMigrationRunner.MigrationDirectory, CancellationToken.None);
        await PostgresMigrationRunner.ApplyAllAsync(data, PostgresMigrationRunner.MigrationDirectory, CancellationToken.None);
        var owner = Guid.NewGuid();
        var outsider = Guid.NewGuid();
        await using (var seed = data.CreateCommand("""
            INSERT INTO account(account_id, normalized_login, password_hash, password_salt,
                argon_memory_kib, argon_iterations, argon_parallelism, status, authority_version, authority_state_hash)
            VALUES ($1, 'test_owner', decode(repeat('00',32),'hex'), decode(repeat('00',16),'hex'), 8,1,1,'active',1,repeat('0',64)),
                   ($2, 'test_other', decode(repeat('00',32),'hex'), decode(repeat('00',16),'hex'), 8,1,1,'active',0,repeat('0',64));
            """))
        {
            seed.Parameters.AddWithValue(owner);
            seed.Parameters.AddWithValue(outsider);
            await seed.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        }
        long id;
        await using (var seed = data.CreateCommand("""
            INSERT INTO character(account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                last_name,first_name,flagship_name,face,ability_values,authority_version)
            VALUES($1,0,repeat('1',64),repeat('2',64),2,0,0,'Pilot','First','Flag',5,ARRAY[1,2,3,4,5,6,7,8]::smallint[],1)
            RETURNING character_id
            """))
        {
            seed.Parameters.AddWithValue(owner);
            id = (long)(await seed.ExecuteScalarAsync(TestContext.Current.CancellationToken))!;
        }

        IAccountStore store = new PostgresAccountStore(data);
        var noOp = new OriginalReturnBaseWrite(new string('0',64), id, 0);
        Assert.False((await store.SetOriginalReturnBaseAsync(owner,noOp,CancellationToken.None)).Updated);
        var first = new OriginalReturnBaseWrite(new string('a',64), id, 1);
        var changed = await store.SetOriginalReturnBaseAsync(owner, first, CancellationToken.None);
        Assert.True(changed.Updated);
        Assert.Equal(2, changed.AuthorityVersion);
        // Reopen the data source/store: no in-process preference is reused.
        await using (var reopened = NpgsqlDataSource.Create(builder.ConnectionString))
            Assert.Equal(1u, Assert.Single(await new PostgresAccountStore(reopened)
                .ListCharactersAsync(owner, CancellationToken.None)).ReturnBaseId);
        await store.SetOriginalReturnBaseAsync(owner, new(new string('b',64),id,2), CancellationToken.None);
        var replay = await store.SetOriginalReturnBaseAsync(owner, first, CancellationToken.None);
        Assert.False(replay.Updated);
        Assert.Equal(2u, replay.CurrentReturnBaseId);
        Assert.Equal(3, replay.AuthorityVersion);
        var noOpReplay = await store.SetOriginalReturnBaseAsync(owner,noOp,CancellationToken.None);
        Assert.False(noOpReplay.Updated);
        Assert.Equal(2u,noOpReplay.CurrentReturnBaseId);
        var same = new OriginalReturnBaseWrite(new string('c',64),id,1);
        var concurrent = await Task.WhenAll(Enumerable.Range(0,8).Select(_ =>
            store.SetOriginalReturnBaseAsync(owner,same,CancellationToken.None)));
        Assert.Single(concurrent, value => value.Updated);
        Assert.All(concurrent, value => Assert.Equal(1u,value.CurrentReturnBaseId));
        var denied = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            store.SetOriginalReturnBaseAsync(outsider,first,CancellationToken.None));
        Assert.Equal("CHARACTER_NOT_FOUND", denied.Message);

        // Force a failure after the character UPDATE but before commit.
        await using (var fail = data.CreateCommand("""
            ALTER TABLE domain_event ADD CONSTRAINT reject_return_change
            CHECK(event_type <> 'OriginalReturnBaseChanged') NOT VALID
            """))
            await fail.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var error = await Assert.ThrowsAsync<PostgresException>(() =>
            store.SetOriginalReturnBaseAsync(owner,new(new string('d',64),id,2),CancellationToken.None));
        Assert.Equal(PostgresErrorCodes.CheckViolation,error.SqlState);
        Assert.Equal(1u, Assert.Single(await store.ListCharactersAsync(owner,CancellationToken.None)).ReturnBaseId);
        await using (var counts = data.CreateCommand("""
            SELECT (SELECT count(*) FROM domain_event),
                   (SELECT count(*) FROM original_return_base_request),
                   (SELECT authority_version FROM account WHERE account_id=$1),
                   (SELECT authority_version FROM account WHERE account_id=$2)
            """))
        {
            counts.Parameters.AddWithValue(owner);
            counts.Parameters.AddWithValue(outsider);
            await using var reader = await counts.ExecuteReaderAsync(TestContext.Current.CancellationToken);
            Assert.True(await reader.ReadAsync(TestContext.Current.CancellationToken));
            Assert.Equal(3,reader.GetInt64(0));
            Assert.Equal(4,reader.GetInt64(1));
            Assert.Equal(4,reader.GetInt64(2));
            Assert.Equal(0,reader.GetInt64(3));
        }
        var deletion = await store.DeleteCharacterAsync(owner,
            new CharacterDeleteWrite(new string('e',64),id,1),CancellationToken.None);
        Assert.True(deletion.Deleted);
        Assert.Empty(await store.ListCharactersAsync(owner,CancellationToken.None));
        await using var history = data.CreateCommand("SELECT count(*) FROM original_return_base_request");
        Assert.Equal(4L,(long)(await history.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
    }
}
