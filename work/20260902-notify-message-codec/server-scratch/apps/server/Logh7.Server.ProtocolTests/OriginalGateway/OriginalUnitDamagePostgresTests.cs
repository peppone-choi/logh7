using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalUnitDamagePostgresTests
{
    public static bool HasTestDatabase=>OriginalReturnBasePostgresTests.HasTestDatabase;

    [Fact(Skip="Requires isolated PostgreSQL",SkipUnless=nameof(HasTestDatabase))]
    public async Task Damage_is_durable_monotone_idempotent_and_bound_to_the_current_ship()
    {
        var connection=Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema="damage_"+Guid.NewGuid().ToString("N");
        await using(var admin=NpgsqlDataSource.Create(connection))
        await using(var create=admin.CreateCommand("CREATE SCHEMA "+schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var builder=new NpgsqlConnectionStringBuilder(connection){SearchPath=schema};
        await using var data=NpgsqlDataSource.Create(builder.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data,PostgresMigrationRunner.MigrationDirectory,CancellationToken.None);
        var owner=Guid.NewGuid();
        await using(var seed=data.CreateCommand("""
            INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
            VALUES($1,'departure',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64))
            """))
        {
            seed.Parameters.AddWithValue(owner);
            await seed.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        }
        long character;
        await using(var seed=data.CreateCommand("""
            INSERT INTO character(account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                last_name,first_name,flagship_name,face,ability_values,authority_version)
            VALUES($1,0,repeat('1',64),repeat('2',64),2,0,0,'Pilot','First','Ship',5,
                ARRAY[1,2,3,4,5,6,7,8]::smallint[],1) RETURNING character_id
            """))
        {
            seed.Parameters.AddWithValue(owner);
            character=(long)(await seed.ExecuteScalarAsync(TestContext.Current.CancellationToken))!;
        }
        IAccountStore store=new PostgresAccountStore(data);
        var id=checked((uint)character);
        await using(var stock=data.CreateCommand("UPDATE original_grid_unit SET supplies=23 WHERE account_id=$1 AND unit_id=$2"))
        {
            stock.Parameters.AddWithValue(owner);
            stock.Parameters.AddWithValue((long)id);
            Assert.Equal(1,await stock.ExecuteNonQueryAsync(TestContext.Current.CancellationToken));
        }
        var before=Assert.IsType<OriginalGridUnitRecord>(await store.FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));
        Assert.Equal(23u,before.Supplies);
        var write=new OriginalUnitDamageWrite(character,id,before.CurrentCellId,before.ShipGeneration,100,25,0);
        var concurrent=await Task.WhenAll(Enumerable.Range(0,8).Select(_=>
            store.SaveOriginalUnitDamageAsync(owner,write,CancellationToken.None)));
        var first=Assert.Single(concurrent,r=>r.Updated);
        Assert.All(concurrent,r=>Assert.Equal(first.Unit,r.Unit));
        Assert.True(first.Updated);
        Assert.Equal(before with { Damaged=25,AuthorityVersion=2 },first.Unit);
        var repeated=await store.SaveOriginalUnitDamageAsync(owner,write,CancellationToken.None);
        Assert.False(repeated.Updated);
        Assert.Equal(first.Unit,repeated.Unit);
        await using(var block=data.CreateCommand(
            "ALTER TABLE domain_event ADD CONSTRAINT reject_damage CHECK(event_type<>'OriginalUnitDamaged') NOT VALID"))
            await block.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<PostgresException>(()=>store.SaveOriginalUnitDamageAsync(owner,
            write with { Damaged=100,Destroyed=100 },CancellationToken.None));
        Assert.Equal(first.Unit,await store.FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));
        await using(var allow=data.CreateCommand("ALTER TABLE domain_event DROP CONSTRAINT reject_damage"))
            await allow.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var final=await store.SaveOriginalUnitDamageAsync(owner,write with { Damaged=100,Destroyed=100 },CancellationToken.None);
        // Every hull of the complement is destroyed, so morale is spent with it.
        Assert.Equal(before with { Damaged=100,Destroyed=100,AuthorityVersion=3,Morale=0 },final.Unit);
        await using(var reopened=NpgsqlDataSource.Create(builder.ConnectionString))
            Assert.Equal(final.Unit,await new PostgresAccountStore(reopened).FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));
        await Assert.ThrowsAsync<InvalidOperationException>(()=>store.SaveOriginalUnitDamageAsync(owner,write,CancellationToken.None));
        await Assert.ThrowsAsync<InvalidOperationException>(()=>store.SaveOriginalUnitDamageAsync(owner,
            write with { ShipGeneration=before.ShipGeneration+1 },CancellationToken.None));
        await Assert.ThrowsAsync<InvalidOperationException>(()=>store.SaveOriginalUnitDamageAsync(Guid.NewGuid(),write,CancellationToken.None));
        await Assert.ThrowsAsync<InvalidOperationException>(()=>store.SaveOriginalUnitDamageAsync(owner,
            write with { Grid=999 },CancellationToken.None));
        await Assert.ThrowsAsync<ArgumentException>(()=>store.SaveOriginalUnitDamageAsync(owner,
            write with { Damaged=101 },CancellationToken.None));
        await Assert.ThrowsAsync<ArgumentException>(()=>store.SaveOriginalUnitDamageAsync(owner,
            write with { Damaged=0,Destroyed=1 },CancellationToken.None));
        Assert.Equal(final.Unit,await store.FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));
        await using(var count=data.CreateCommand("SELECT count(*) FROM domain_event WHERE event_type='OriginalUnitDamaged'"))
            Assert.Equal(2L,(long)(await count.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
    }
}
