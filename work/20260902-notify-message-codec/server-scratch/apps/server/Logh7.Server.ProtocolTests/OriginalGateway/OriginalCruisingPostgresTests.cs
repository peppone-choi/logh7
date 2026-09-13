using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalCruisingPostgresTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;

    [Fact(Skip="Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless=nameof(HasTestDatabase))]
    public async Task Upgrade_preserves_existing_unit_and_initializes_only_legacy_cruising_projection()
    {
        var connection=Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema="cruising_upgrade_"+Guid.NewGuid().ToString("N");
        await using(var admin=NpgsqlDataSource.Create(connection))
        await using(var create=admin.CreateCommand("CREATE SCHEMA "+schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var builder=new NpgsqlConnectionStringBuilder(connection){SearchPath=schema};
        await using var data=NpgsqlDataSource.Create(builder.ConnectionString);
        foreach(var migration in Directory.GetFiles(PostgresMigrationRunner.MigrationDirectory,"*.sql").Order(StringComparer.Ordinal))
            if(string.CompareOrdinal(Path.GetFileName(migration),"0023_original_grid_cruising.sql")<0)
                await PostgresMigrationRunner.ApplyAsync(data,migration,CancellationToken.None);
        var owner=Guid.NewGuid();
        await using(var seed=data.CreateCommand("""
            INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
            VALUES($1,'cruising_upgrade',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64))
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
        await using(var stage=data.CreateCommand("UPDATE original_grid_unit SET current_cell_id=102,base_id=2,damaged=25,destroyed=10,ship_generation=4"))
            await stage.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        object? before;
        await using(var read=data.CreateCommand("SELECT to_jsonb(u)-'cruising'-'mode'-'unit_number'-'supplies'-'morale' FROM original_grid_unit u"))
            before=await read.ExecuteScalarAsync(TestContext.Current.CancellationToken);
        await PostgresMigrationRunner.ApplyAllAsync(data,PostgresMigrationRunner.MigrationDirectory,CancellationToken.None);
        await using(var read=data.CreateCommand("SELECT to_jsonb(u)-'cruising'-'mode'-'unit_number'-'supplies'-'morale' FROM original_grid_unit u"))
            Assert.Equal(before,await read.ExecuteScalarAsync(TestContext.Current.CancellationToken));
        IAccountStore store=new PostgresAccountStore(data);
        var unit=await store.FindOriginalGridUnitAsync(owner,character,checked((uint)character),CancellationToken.None);
        Assert.NotNull(unit);
        Assert.Equal((ushort)100,unit.UnitNumber);
        Assert.Equal(100u,unit.Supplies);
        Assert.Equal(9f,unit.Cruising);
        var moved=await store.MoveOriginalGridUnitAsync(owner,new(new string('a',64),character,unit.UnitId,39,102,102,101,0x2b),CancellationToken.None);
        Assert.Equal(OriginalMoveGridStoreStatus.Moved,moved.Status);
        Assert.NotNull(moved.Unit);
        Assert.Equal(8f,moved.Unit.Cruising);
        await PostgresMigrationRunner.ApplyAllAsync(data,PostgresMigrationRunner.MigrationDirectory,CancellationToken.None);
        Assert.Equal(moved.Unit,await store.FindOriginalGridUnitAsync(owner,character,unit.UnitId,CancellationToken.None));
    }

    // Detects destination-based refilling, missing persistence, double charging
    // retries, and committing fuel before the move event is durable.
    [Fact(Skip="Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless=nameof(HasTestDatabase))]
    public async Task Round_trips_consume_persisted_cruising_once_and_roll_back_with_event()
    {
        var connection=Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema="cruising_"+Guid.NewGuid().ToString("N");
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
            VALUES($1,'cruising_owner',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64))
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
        var unitId=checked((uint)character);
        // A surviving damaged incarnation must not become a pristine ship
        // in the historical result returned for the same committed command.
        await using(var losses=data.CreateCommand("UPDATE original_grid_unit SET damaged=25,destroyed=10,ship_generation=3"))
            await losses.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var before=await store.FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None);
        Assert.NotNull(before);
        Assert.Equal(10f,before.Cruising);
        OriginalMoveGridWrite Move(int intent,uint source,uint target)=>
            new(intent.ToString("x64"),character,unitId,39,source,source,target,0x2b);
        var first=Move(1,101,102);
        var results=await Task.WhenAll(Enumerable.Range(0,8).Select(_=>store.MoveOriginalGridUnitAsync(owner,first,CancellationToken.None)));
        var moved=Assert.Single(results,r=>r.Status==OriginalMoveGridStoreStatus.Moved);
        Assert.NotNull(moved.Unit);
        Assert.Equal(9f,moved.Unit.Cruising);
        Assert.Equal(((ushort)25,(ushort)10,3L),(moved.Unit.Damaged,moved.Unit.Destroyed,moved.Unit.ShipGeneration));
        Assert.All(results,r=>Assert.Equal(moved.Unit,r.Unit));
        await PostgresMigrationRunner.ApplyAllAsync(data,PostgresMigrationRunner.MigrationDirectory,CancellationToken.None);
        Assert.Equal(moved.Unit,await store.FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None));
        await using(var reopened=NpgsqlDataSource.Create(builder.ConnectionString))
            Assert.Equal(moved.Unit,await new PostgresAccountStore(reopened).FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None));
        await using(var reject=data.CreateCommand("ALTER TABLE domain_event ADD CONSTRAINT reject_move CHECK(event_type <> 'OriginalGridUnitMoved') NOT VALID"))
            await reject.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<PostgresException>(()=>store.MoveOriginalGridUnitAsync(owner,Move(2,102,101),CancellationToken.None));
        Assert.Equal(moved.Unit,await store.FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None));
        await using(var allow=data.CreateCommand("ALTER TABLE domain_event DROP CONSTRAINT reject_move"))
            await allow.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        for(var intent=2;intent<=10;intent++)
        {
            var source=intent%2==0?102u:101u;
            var next=await store.MoveOriginalGridUnitAsync(owner,Move(intent,source,source==101?102u:101u),CancellationToken.None);
            Assert.Equal(OriginalMoveGridStoreStatus.Moved,next.Status);
            Assert.NotNull(next.Unit);
            Assert.Equal(10f-intent,next.Unit.Cruising);
            Assert.Equal(next.Unit,await store.FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None));
        }
        var exhausted=await store.MoveOriginalGridUnitAsync(owner,Move(11,101,102),CancellationToken.None);
        Assert.Equal(OriginalMoveGridStoreStatus.Rejected,exhausted.Status);
        Assert.Equal("MOVE_GRID_CRUISING_EXHAUSTED",exhausted.ErrorCode);
        var replay=await store.MoveOriginalGridUnitAsync(owner,first,CancellationToken.None);
        Assert.Equal(OriginalMoveGridStoreStatus.Replayed,replay.Status);
        Assert.Equal(moved.Unit,replay.Unit);
        var final=await store.FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None);
        Assert.NotNull(final);
        Assert.Equal((101u,0f,11L),(final.CurrentCellId,final.Cruising,final.AuthorityVersion));
        await using var events=data.CreateCommand("""
            SELECT count(*) FROM domain_event WHERE event_type='OriginalGridUnitMoved'
            AND (payload->>'sourceCruising')::real-(payload->>'destinationCruising')::real=1
            AND (payload->>'shipGeneration')::bigint=3
            """);
        Assert.Equal(10L,(long)(await events.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
    }
}
