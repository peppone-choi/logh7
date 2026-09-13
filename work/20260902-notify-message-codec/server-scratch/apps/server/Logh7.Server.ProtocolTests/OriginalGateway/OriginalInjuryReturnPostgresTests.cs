using Logh7.Server.Storage;
using Logh7.Server.OriginalGateway;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalInjuryReturnPostgresTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;

    [Fact(Skip="Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless=nameof(HasTestDatabase))]
    public async Task Return_commits_location_and_loss_once_and_survives_a_new_store()
    {
        var connection = Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema = "injury_return_test_" + Guid.NewGuid().ToString("N");
        await using (var admin = NpgsqlDataSource.Create(connection))
        await using (var create = admin.CreateCommand("CREATE SCHEMA " + schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var builder = new NpgsqlConnectionStringBuilder(connection) { SearchPath=schema };
        await using var data = NpgsqlDataSource.Create(builder.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data,PostgresMigrationRunner.MigrationDirectory,CancellationToken.None);
        await PostgresMigrationRunner.ApplyAllAsync(data,PostgresMigrationRunner.MigrationDirectory,CancellationToken.None);
        var owner = Guid.NewGuid();
        var outsider = Guid.NewGuid();
        await using (var seed = data.CreateCommand("""
            INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
            VALUES($1,'injury_owner',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64)),
                  ($2,'injury_other',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',0,repeat('0',64))
            """))
        {
            seed.Parameters.AddWithValue(owner); seed.Parameters.AddWithValue(outsider);
            await seed.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        }
        long character;
        await using (var seed = data.CreateCommand("""
            INSERT INTO character(account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                last_name,first_name,flagship_name,face,ability_values,authority_version,return_base_id)
            VALUES($1,0,repeat('1',64),repeat('2',64),2,0,0,'Pilot','First','Flag',5,ARRAY[1,2,3,4,5,6,7,8]::smallint[],1,2)
            RETURNING character_id
            """))
        {
            seed.Parameters.AddWithValue(owner);
            character=(long)(await seed.ExecuteScalarAsync(TestContext.Current.CancellationToken))!;
        }
        IAccountStore store = new PostgresAccountStore(data);
        // Reproduce a ship lost while navigating, rather than the legacy default mode0.
        await using(var seed=data.CreateCommand("UPDATE original_grid_unit SET mode=6"))
            await seed.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var unitId = checked((uint)character);
        var request = new OriginalInjuryReturnWrite(Guid.NewGuid(),character,unitId,1,101,102,2,100,100,100);
        var badComplement = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            store.ReturnInjuredOriginalUnitAsync(owner, request with { Number=1, Damaged=1, Destroyed=1 },
                TestContext.Current.CancellationToken));
        Assert.Equal("INJURY_RETURN_COMPLEMENT_MISMATCH",badComplement.Message);
        var untouched = await store.FindOriginalGridUnitAsync(owner,character,unitId,TestContext.Current.CancellationToken);
        Assert.Equal((ushort)100,untouched!.UnitNumber);
        Assert.Equal((ushort)0,untouched.Destroyed);
        Assert.Equal(101u,untouched.CurrentCellId);
        await using(var fail=data.CreateCommand("""
            ALTER TABLE domain_event ADD CONSTRAINT reject_injury_return
            CHECK(event_type <> 'OriginalUnitInjuryReturned') NOT VALID
            """))
            await fail.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var failure=await Assert.ThrowsAsync<PostgresException>(()=>store.ReturnInjuredOriginalUnitAsync(owner,request,CancellationToken.None));
        Assert.Equal(PostgresErrorCodes.CheckViolation,failure.SqlState);
        var before=await store.FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None);
        Assert.NotNull(before);
        Assert.Equal((101u,(ushort)0,(ushort)0,1L),(before.CurrentCellId,before.Damaged,before.Destroyed,before.AuthorityVersion));
        Assert.Null(before.InjuryReturnId);
        Assert.Equal((byte)6,before.Mode); // Failed event insert must roll back the stance too.
        await using(var allow=data.CreateCommand("ALTER TABLE domain_event DROP CONSTRAINT reject_injury_return"))
            await allow.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var returned=await store.ReturnInjuredOriginalUnitAsync(owner,request,CancellationToken.None);
        Assert.True(returned.Updated);
        Assert.Equal((byte)4,returned.Unit.Mode); // Return to the base, not open-space navigation.
        Assert.Equal((102u,2u,(ushort)100,(ushort)100,2L),
            (returned.Unit.CurrentCellId,returned.Unit.BaseId,returned.Unit.Damaged,returned.Unit.Destroyed,returned.Unit.AuthorityVersion));
        await using (var reopened=NpgsqlDataSource.Create(builder.ConnectionString))
        {
            var restored=await new PostgresAccountStore(reopened).FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None);
            Assert.Equal(returned.Unit,restored);
        }
        var repeats=await Task.WhenAll(Enumerable.Range(0,8).Select(_=>store.ReturnInjuredOriginalUnitAsync(owner,request,CancellationToken.None)));
        Assert.All(repeats,r=>{Assert.False(r.Updated);Assert.Equal(returned.Unit,r.Unit);});
        var conflict=await Assert.ThrowsAsync<InvalidOperationException>(()=>store.ReturnInjuredOriginalUnitAsync(
            owner,request with { DestinationBase=1 },CancellationToken.None));
        Assert.Equal("INJURY_RETURN_REPLAY_CONFLICT",conflict.Message);
        var denied=await Assert.ThrowsAsync<InvalidOperationException>(()=>store.ReturnInjuredOriginalUnitAsync(outsider,request,CancellationToken.None));
        Assert.Equal("INJURY_RETURN_UNIT_NOT_OWNED",denied.Message);
        var warp=await store.MoveOriginalGridUnitAsync(owner,
            new(new string('a',64),character,unitId,39,102,102,101,OriginalMoveGridAuthority.MinimalWorldWarpAction),CancellationToken.None);
        Assert.Equal(OriginalMoveGridStoreStatus.Rejected,warp.Status);
        Assert.Equal("MOVE_GRID_UNIT_RECOVERING",warp.ErrorCode);
        await using(var modes=data.CreateCommand("SELECT (payload->>'sourceMode')::int,(payload->>'mode')::int FROM domain_event WHERE event_type='OriginalUnitInjuryReturned'"))
        await using(var reader=await modes.ExecuteReaderAsync(TestContext.Current.CancellationToken))
        {
            Assert.True(await reader.ReadAsync(TestContext.Current.CancellationToken));
            Assert.Equal(6,reader.GetInt32(0));
            Assert.Equal(4,reader.GetInt32(1));
        }
        await using var counts=data.CreateCommand("SELECT count(*) FROM domain_event WHERE event_type='OriginalUnitInjuryReturned'");
        Assert.Equal(1L,(long)(await counts.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
    }
}
