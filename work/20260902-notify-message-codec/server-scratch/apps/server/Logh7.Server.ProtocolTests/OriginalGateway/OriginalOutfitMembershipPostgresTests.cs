using Logh7.Server.Storage;
using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalOutfitMembershipPostgresTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;
    [Fact(Skip="Requires isolated PostgreSQL",SkipUnless=nameof(HasTestDatabase))]
    public async Task Real_character_membership_requires_existing_same_power_outfit_and_is_not_a_unit_controller()
    {
        var connection=Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema="outfit_member_"+Guid.NewGuid().ToString("N");
        await using(var admin=NpgsqlDataSource.Create(connection))
        await using(var create=admin.CreateCommand("CREATE SCHEMA "+schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var config=new NpgsqlConnectionStringBuilder(connection){SearchPath=schema};
        await using var data=NpgsqlDataSource.Create(config.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data,PostgresMigrationRunner.MigrationDirectory,
            TestContext.Current.CancellationToken);
        await using(var exists=data.CreateCommand("SELECT to_regclass('original_outfit_member') IS NOT NULL"))
            Assert.True((bool)(await exists.ExecuteScalarAsync(TestContext.Current.CancellationToken))!,"Outfit membership storage is missing");
        await using var seed=data.CreateCommand("""
            WITH owner AS (
                INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                    argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
                VALUES($1,'member',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64))
                RETURNING account_id)
            INSERT INTO character(account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                last_name,first_name,flagship_name,face,ability_values,authority_version)
            SELECT account_id,0,repeat('1',64),repeat('2',64),2,0,0,'Pilot','First','Ship',5,
                ARRAY[1,2,3,4,5,6,7,8]::smallint[],1 FROM owner RETURNING character_id
            """);
        var account=Guid.NewGuid();
        seed.Parameters.AddWithValue(account);
        var character=(long)(await seed.ExecuteScalarAsync(TestContext.Current.CancellationToken))!;
        await using(var outfits=data.CreateCommand("""
            INSERT INTO original_outfit(outfit_id,power,camp,kind,outfit_index)
            VALUES(500,2,0,0,1),(501,3,0,0,1)
            """))
            await outfits.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        await using(var join=data.CreateCommand("INSERT INTO original_outfit_member(character_id,outfit_id,power,camp) VALUES($1,500,2,0)"))
        {
            join.Parameters.AddWithValue(character);
            await join.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        }
        foreach(var invalid in new[] { "outfit_id=999", "outfit_id=501", "outfit_id=501,power=3", "camp=1" })
        {
            await using var update=data.CreateCommand("UPDATE original_outfit_member SET "+invalid+" WHERE character_id=$1");
            update.Parameters.AddWithValue(character);
            var error=await Assert.ThrowsAsync<PostgresException>(()=>update.ExecuteNonQueryAsync(TestContext.Current.CancellationToken));
            Assert.Equal("23503",error.SqlState);
        }
        await using var reopened=NpgsqlDataSource.Create(config.ConnectionString);
        await using var read=reopened.CreateCommand("SELECT outfit_id FROM original_outfit_member WHERE character_id=$1");
        read.Parameters.AddWithValue(character);
        Assert.Equal(500L,(long)(await read.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
        await using var units=data.CreateCommand("SELECT count(*) FROM original_fleet_unit");
        Assert.Equal(0L,(long)(await units.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
        var store=new PostgresAccountStore(reopened);
        Assert.Null(await store.FindOriginalOutfitMembershipAsync(Guid.NewGuid(),character,TestContext.Current.CancellationToken));
        var membership=Assert.IsType<OriginalOutfitMembership>(await store.FindOriginalOutfitMembershipAsync(
            account,character,TestContext.Current.CancellationToken));
        Assert.Equal(500u,membership.Outfit.Id);
        Assert.Equal((byte)2,membership.Outfit.Power);
        Assert.Equal(1,membership.MembershipRevision);
        var battles=new OriginalTacticalBattleRegistry();
        var session=OriginalPlayerCombatTests.Session(battles,checked((uint)character),2);
        OriginalWarpSessionClockTests.SetField(session,"_store",store);
        OriginalWarpSessionClockTests.SetField(session,"_accountId",account);
        var key=new byte[16];
        var scene=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"),key,1),
            TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,scene.Status);
        Assert.Equal(500u,Assert.Single(battles.OtherParticipants(101,0),p=>p.Unit.Id==(uint)character).Unit.Outfit);
        var frames=new[]{scene.ResponsePayload!}.Concat(scene.AdditionalResponses!.Select(p=>p.Payload))
            .Select(f=>OriginalClientInnerFrameCodec.Decode(f,key,0).Payload!).ToArray();
        var outfitFrame=Assert.Single(frames,f=>BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))==0x032b);
        Assert.Equal(500u,BinaryPrimitives.ReadUInt32BigEndian(outfitFrame.AsSpan(7)));
        var unitFrame=Assert.Single(frames,f=>BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))==0x0325);
        Assert.Equal(500u,BinaryPrimitives.ReadUInt32BigEndian(unitFrame.AsSpan(19)));
    }
}
