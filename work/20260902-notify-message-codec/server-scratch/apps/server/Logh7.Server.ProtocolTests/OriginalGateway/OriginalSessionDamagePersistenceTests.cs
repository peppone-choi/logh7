using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalSessionDamagePersistenceTests
{
    public static bool HasTestDatabase=>OriginalReturnBasePostgresTests.HasTestDatabase;

    [Theory(Skip="Requires isolated PostgreSQL",SkipUnless=nameof(HasTestDatabase))]
    [InlineData(100, 25)]
    [InlineData(1, 1)]
    public async Task Registered_session_persists_damage_without_a_victim_refresh(ushort number, ushort damaged)
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
        await using(var check=data.CreateCommand("""
            SELECT EXISTS(SELECT 1 FROM information_schema.columns
              WHERE table_schema=current_schema() AND table_name='original_grid_unit' AND column_name='unit_number')
            """))
            Assert.True((bool)(await check.ExecuteScalarAsync(TestContext.Current.CancellationToken))!,
                "Persisted unit complement column is missing");
        await using(var setup=data.CreateCommand("UPDATE original_grid_unit SET unit_number=$1"))
        {
            setup.Parameters.AddWithValue((int)number);
            await setup.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        }
        var before=Assert.IsType<OriginalGridUnitRecord>(await store.FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));
        var battles=new OriginalTacticalBattleRegistry();
        var key=new byte[16];
        var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,store:store,battles:battles);
        OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(session,"_worldCharacterId",id);
        OriginalWarpSessionClockTests.SetField(session,"_worldGridUnitId",id);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),CancellationToken.None);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"),key,2),CancellationToken.None);
        Assert.Contains(battles.OtherParticipants(before.CurrentCellId,0),p=>p.Unit.Id==id);
        using(await battles.LockAsync(before.CurrentCellId,100,CancellationToken.None))
            await battles.CommitUnitDamageAsync(before.CurrentCellId,100,id,new(damaged,0),CancellationToken.None);
        await using var reopened=NpgsqlDataSource.Create(builder.ConnectionString);
        var after=Assert.IsType<OriginalGridUnitRecord>(await new PostgresAccountStore(reopened)
            .FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));
        Assert.Equal(before with { Damaged=damaged,AuthorityVersion=2 },after);
        Assert.Equal(new OriginalTacticalDamageState(damaged,0),battles.GetEncounter(before.CurrentCellId,100).GetUnitDamage(id));
        var freshBattles = new OriginalTacticalBattleRegistry();
        var fresh = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key,
            store: new PostgresAccountStore(reopened), battles: freshBattles);
        OriginalWarpSessionClockTests.SetField(fresh, "_accountId", owner);
        OriginalWarpSessionClockTests.SetField(fresh, "_worldCharacterId", id);
        OriginalWarpSessionClockTests.SetField(fresh, "_worldGridUnitId", id);
        await fresh.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),
            TestContext.Current.CancellationToken);
        Assert.Equal(number, freshBattles.GetEncounter(before.CurrentCellId,100).UnitNumber(id));
    }
}
