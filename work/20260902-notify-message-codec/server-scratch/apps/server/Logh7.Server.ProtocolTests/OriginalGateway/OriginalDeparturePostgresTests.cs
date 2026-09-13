using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalDeparturePostgresTests
{
    public static bool HasTestDatabase=>OriginalReturnBasePostgresTests.HasTestDatabase;

    [Theory(Skip="Requires isolated PostgreSQL",SkipUnless=nameof(HasTestDatabase))]
    [InlineData(0, 0)]
    [InlineData(25, 25)]
    [InlineData(100, 99)]
    [InlineData(100, 100)]
    public async Task Departure_is_owned_atomic_once_only_and_old_retries_do_not_move_a_later_ship(int damaged, int destroyed)
    {
        var connection=Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema="departure_"+Guid.NewGuid().ToString("N");
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
        await using(var casualties=data.CreateCommand(
            "UPDATE original_grid_unit SET unit_number=100,damaged=$1,destroyed=$2"))
        {
            casualties.Parameters.AddWithValue(damaged);
            casualties.Parameters.AddWithValue(destroyed);
            await casualties.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        }
        var before=Assert.IsType<OriginalGridUnitRecord>(await store.FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));
        var write=new OriginalDepartureWrite(new string('a',64),character,id,before.AuthorityVersion,before.ShipGeneration,before.BaseId);
        if (destroyed == 100)
        {
            var error = await Assert.ThrowsAsync<InvalidOperationException>(() =>
                store.DepartOwnOriginalUnitAsync(owner, write, TestContext.Current.CancellationToken));
            Assert.Equal("DEPARTURE_UNIT_UNAVAILABLE", error.Message);
            Assert.Equal(before, await store.FindOriginalGridUnitAsync(owner, character, id, TestContext.Current.CancellationToken));
            await using var noHistory = data.CreateCommand("SELECT count(*) FROM original_departure_request");
            Assert.Equal(0L, (long)(await noHistory.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
            return;
        }
        var results=await Task.WhenAll(Enumerable.Range(0,8).Select(_=>store.DepartOwnOriginalUnitAsync(owner,write,CancellationToken.None)));
        var moved=Assert.Single(results,r=>r.Updated);
        Assert.Equal(before with { Mode=4,AuthorityVersion=2 },moved.Unit);
        Assert.All(results,r=>Assert.Equal(moved.Unit,r.Unit));
        await using(var events=data.CreateCommand("""
            SELECT count(*) FROM domain_event WHERE event_type='OriginalUnitStanceChanged'
                AND (payload->>'destinationBase')::bigint=$1 AND (payload->>'mode')::int=4
            """))
        {
            events.Parameters.AddWithValue((long)before.BaseId);
            Assert.Equal(1L,(long)(await events.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
        }
        await using(var reopened=NpgsqlDataSource.Create(builder.ConnectionString))
            Assert.Equal(moved.Unit,await new PostgresAccountStore(reopened).FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));
        await Assert.ThrowsAsync<InvalidOperationException>(()=>store.DepartOwnOriginalUnitAsync(Guid.NewGuid(),write,CancellationToken.None));
        await Assert.ThrowsAsync<InvalidOperationException>(()=>store.DepartOwnOriginalUnitAsync(owner,
            write with { RequestFingerprint=new string('c',64),ExpectedUnitVersion=999 },CancellationToken.None));
        // Test-only docking fixture, not a production arrival command.
        await using(var stage=data.CreateCommand("UPDATE original_grid_unit SET base_id=1,mode=0"))
            await stage.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var staged=Assert.IsType<OriginalGridUnitRecord>(await store.FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));
        var second=write with { RequestFingerprint=new string('b',64),ExpectedUnitVersion=staged.AuthorityVersion,ExpectedBaseId=1 };
        await using(var block=data.CreateCommand(
            "ALTER TABLE domain_event ADD CONSTRAINT reject_departure CHECK(event_type<>'OriginalUnitStanceChanged') NOT VALID"))
            await block.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<PostgresException>(()=>store.DepartOwnOriginalUnitAsync(owner,second,CancellationToken.None));
        Assert.Equal(staged,await store.FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));
        await using(var counts=data.CreateCommand("SELECT count(*) FROM original_departure_request"))
            Assert.Equal(1L,(long)(await counts.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
        await using(var allow=data.CreateCommand("ALTER TABLE domain_event DROP CONSTRAINT reject_departure"))
            await allow.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        Assert.True((await store.DepartOwnOriginalUnitAsync(owner,second,CancellationToken.None)).Updated);
        await using(var later=data.CreateCommand("UPDATE original_grid_unit SET current_cell_id=102,base_id=2,mode=7,ship_generation=1"))
            await later.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var current=Assert.IsType<OriginalGridUnitRecord>(await store.FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));
        var replay=await store.DepartOwnOriginalUnitAsync(owner,write,CancellationToken.None);
        Assert.False(replay.Updated);
        Assert.Equal(current,replay.Unit);
        Assert.Equal(current,await store.FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));
        var key=new byte[16];
        var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,
            store:store,battles:new OriginalTacticalBattleRegistry());
        OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(session,"_worldCharacterId",id);
        OriginalWarpSessionClockTests.SetField(session,"_worldGridUnitId",id);
        async Task<NaturalAuthoritySessionResult> Send(byte[] payload,uint sequence)=>
            await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(payload,key,sequence),CancellationToken.None);
        await Send(Convert.FromHexString("0205"),1);
        var dockedRefresh=await Send(Convert.FromHexString("0F02"),2);
        var dockedFrames=new[]{dockedRefresh.ResponsePayload!}.Concat(dockedRefresh.AdditionalResponses?.Select(p=>p.Payload)??[])
            .Select(f=>OriginalClientInnerFrameCodec.Decode(f,key,0).Payload!).ToArray();
        var docked=Assert.Single(dockedFrames,f=>BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))==0x0325);
        Assert.Equal(2u,BinaryPrimitives.ReadUInt32BigEndian(docked.AsSpan(28)));
        var request=new byte[32];
        BinaryPrimitives.WriteUInt16BigEndian(request,0x0b06);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(6),id);
        BinaryPrimitives.WriteUInt16BigEndian(request.AsSpan(20),4);
        var result=await Send(request,3);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,result.Status);
        var frames=new[]{result.ResponsePayload!}.Concat(result.AdditionalResponses?.Select(p=>p.Payload)??[])
            .Select(f=>OriginalClientInnerFrameCodec.Decode(f,key,0).Payload!).ToArray();
        var notification=Assert.Single(frames,f=>BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))==0x0b0b);
        Assert.Equal(2u,BinaryPrimitives.ReadUInt32BigEndian(notification.AsSpan(14)));
        Assert.Equal((ushort)4,BinaryPrimitives.ReadUInt16BigEndian(notification.AsSpan(18)));
        Assert.Equal(2001u,BinaryPrimitives.ReadUInt32BigEndian(notification.AsSpan(20)));
        Assert.Equal(0u,BinaryPrimitives.ReadUInt32BigEndian(notification.AsSpan(24)));
        Assert.Equal((byte)1,notification[28]);
        Assert.Equal(id,BinaryPrimitives.ReadUInt32BigEndian(notification.AsSpan(29)));
        var departed=Assert.IsType<OriginalGridUnitRecord>(await store.FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));
        Assert.Equal(current with { Mode=4,AuthorityVersion=current.AuthorityVersion+1 },departed);
        // Transport replay is rejected before dispatch; do not weaken the
        // existing strictly increasing wire sequence for command retries.
        Assert.StartsWith("command-reject=",(await Send(request,4)).ResponseMetadata);
        Assert.Equal(departed,await store.FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));
        var wrongActor=(byte[])request.Clone();
        BinaryPrimitives.WriteUInt32BigEndian(wrongActor.AsSpan(6),id+99);
        Assert.StartsWith("command-reject=",(await Send(wrongActor,5)).ResponseMetadata);
        var otherMode=(byte[])request.Clone();
        BinaryPrimitives.WriteUInt16BigEndian(otherMode.AsSpan(20),6);
        Assert.StartsWith("command-reject=",(await Send(otherMode,6)).ResponseMetadata);
        // Invalid transport frames terminate the session; check last.
        Assert.Equal(NaturalAuthoritySessionStatus.Invalid,(await Send(request,3)).Status);
        Assert.Equal(departed,await store.FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));
        var information=Assert.Single(frames,f=>BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))==0x0325);
        Assert.Equal(id,BinaryPrimitives.ReadUInt32BigEndian(information.AsSpan(8)));
        Assert.Equal((byte)4,information[14]);
        Assert.Equal(2u,BinaryPrimitives.ReadUInt32BigEndian(information.AsSpan(28)));
        // New DB connection and session must project the command's persisted
        // result, not the old docking fixture or a transient session field.
        await using var reopenedAfterDeparture=NpgsqlDataSource.Create(builder.ConnectionString);
        var reconnect=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,
            store:new PostgresAccountStore(reopenedAfterDeparture),battles:new OriginalTacticalBattleRegistry());
        OriginalWarpSessionClockTests.SetField(reconnect,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(reconnect,"_worldCharacterId",id);
        OriginalWarpSessionClockTests.SetField(reconnect,"_worldGridUnitId",id);
        await reconnect.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),CancellationToken.None);
        var refresh=await reconnect.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"),key,2),CancellationToken.None);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,refresh.Status);
        var reconnectedFrames=new[]{refresh.ResponsePayload!}.Concat(refresh.AdditionalResponses?.Select(p=>p.Payload)??[])
            .Select(f=>OriginalClientInnerFrameCodec.Decode(f,key,0).Payload!).ToArray();
        var persisted=Assert.Single(reconnectedFrames,f=>BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))==0x0325);
        Assert.Equal(id,BinaryPrimitives.ReadUInt32BigEndian(persisted.AsSpan(8)));
        Assert.Equal((byte)4,persisted[14]);
        Assert.Equal(2u,BinaryPrimitives.ReadUInt32BigEndian(persisted.AsSpan(28)));
        var ownCharacter=Assert.Single(reconnectedFrames,f=>BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))==0x0323
            && BinaryPrimitives.ReadUInt32BigEndian(f.AsSpan(6))==id);
        Assert.Equal(2001u,BinaryPrimitives.ReadUInt32BigEndian(ownCharacter.AsSpan(30)));
        // The spot-owner word is NOT a base id: serving one there cost the
        // client the spot's name (宇宙港/スポット不明) and unlocked nothing.
        Assert.Equal(0u,BinaryPrimitives.ReadUInt32BigEndian(ownCharacter.AsSpan(34)));
        // The dock/undock toggle sends5 from inside the port. The result is
        // orbital berth, not base0. Exercise the real storage and wire path.
        var undock=(byte[])request.Clone();
        BinaryPrimitives.WriteUInt16BigEndian(undock.AsSpan(20),5);
        var orbital=await reconnect.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(undock,key,3),CancellationToken.None);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,orbital.Status);
        Assert.DoesNotContain("command-reject=",orbital.ResponseMetadata??"");
        var orbitalFrames=new[]{orbital.ResponsePayload!}.Concat(orbital.AdditionalResponses?.Select(p=>p.Payload)??[])
            .Select(f=>OriginalClientInnerFrameCodec.Decode(f,key,0).Payload!).ToArray();
        var orbitalUnit=Assert.Single(orbitalFrames,f=>BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))==0x0325);
        Assert.Equal((byte)5,orbitalUnit[14]);
        Assert.Equal(2u,BinaryPrimitives.ReadUInt32BigEndian(orbitalUnit.AsSpan(28)));
        var orbitalNotice=Assert.Single(orbitalFrames,f=>BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))==0x0b0b);
        Assert.Equal(0u,BinaryPrimitives.ReadUInt32BigEndian(orbitalNotice.AsSpan(20)));
        var characterRequest=new byte[6];
        BinaryPrimitives.WriteUInt16BigEndian(characterRequest,0x0322);
        BinaryPrimitives.WriteUInt32BigEndian(characterRequest.AsSpan(2),id);
        var orbitalCharacterReply=await reconnect.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(characterRequest,key,4),CancellationToken.None);
        var orbitalCharacter=OriginalClientInnerFrameCodec.Decode(orbitalCharacterReply.ResponsePayload!,key,0).Payload!;
        Assert.Equal((ushort)0x0323,BinaryPrimitives.ReadUInt16BigEndian(orbitalCharacter.AsSpan(4)));
        Assert.Equal(0u,BinaryPrimitives.ReadUInt32BigEndian(orbitalCharacter.AsSpan(30)));
        var storedOrbital=await store.FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None);
        Assert.Equal(departed with { Mode=5,AuthorityVersion=departed.AuthorityVersion+1 },storedOrbital);
        var oldReplay=await store.DepartOwnOriginalUnitAsync(owner,write,CancellationToken.None);
        Assert.False(oldReplay.Updated);
        Assert.Equal(storedOrbital,oldReplay.Unit);
    }

    /// <summary>
    /// Cruising is an authored counter that only a warp spends. Garrisoning at
    /// the unit's own base refills it, so the world is not a one-way trip;
    /// leaving port does not, and a refused stance change refuels nothing.
    /// </summary>
    [Fact(Skip="Requires isolated PostgreSQL",SkipUnless=nameof(HasTestDatabase))]
    public async Task Garrisoning_at_the_units_base_refuels_it_and_leaving_port_does_not()
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateSchemaAsync("refuel");
        IAccountStore store=new PostgresAccountStore(data);
        var (owner,character)=await SeedOwnerAsync(data,"refuel");
        var id=checked((uint)character);
        await using(var spend=data.CreateCommand("UPDATE original_grid_unit SET cruising=4,mode=6"))
            await spend.ExecuteNonQueryAsync(ct);
        var spent=Assert.IsType<OriginalGridUnitRecord>(
            await store.FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));
        Assert.Equal(4f,spent.Cruising);

        var docked=await store.DepartOwnOriginalUnitAsync(owner,
            new(new string('a',64),character,id,spent.AuthorityVersion,spent.ShipGeneration,spent.BaseId,4),ct);

        Assert.True(docked.Updated);
        Assert.Equal((byte)4,docked.Unit.Mode);
        Assert.Equal(OriginalMoveGridAuthority.MinimalWorldStartingCruising,docked.Unit.Cruising);
        Assert.Equal(docked.Unit,await store.FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));

        await using(var spendAgain=data.CreateCommand("UPDATE original_grid_unit SET cruising=6"))
            await spendAgain.ExecuteNonQueryAsync(ct);
        var refuelled=Assert.IsType<OriginalGridUnitRecord>(
            await store.FindOriginalGridUnitAsync(owner,character,id,CancellationToken.None));

        var undocked=await store.DepartOwnOriginalUnitAsync(owner,
            new(new string('b',64),character,id,refuelled.AuthorityVersion,refuelled.ShipGeneration,
                refuelled.BaseId,5),ct);

        Assert.True(undocked.Updated);
        Assert.Equal((byte)5,undocked.Unit.Mode);
        Assert.Equal(6f,undocked.Unit.Cruising);
    }

    private static async Task<NpgsqlDataSource> CreateSchemaAsync(string tag)
    {
        var connection=Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema=tag+"_"+Guid.NewGuid().ToString("N");
        await using(var admin=NpgsqlDataSource.Create(connection))
        await using(var create=admin.CreateCommand("CREATE SCHEMA "+schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var data=NpgsqlDataSource.Create(
            new NpgsqlConnectionStringBuilder(connection){SearchPath=schema}.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data,PostgresMigrationRunner.MigrationDirectory,
            CancellationToken.None);
        return data;
    }

    private static async Task<(Guid Owner,long Character)> SeedOwnerAsync(NpgsqlDataSource data,string login)
    {
        var ct=TestContext.Current.CancellationToken;
        var owner=Guid.NewGuid();
        await using(var seed=data.CreateCommand("""
            INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
            VALUES($1,$2,decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64))
            """))
        {
            seed.Parameters.AddWithValue(owner);
            seed.Parameters.AddWithValue(login);
            await seed.ExecuteNonQueryAsync(ct);
        }
        await using var character=data.CreateCommand("""
            INSERT INTO character(account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                last_name,first_name,flagship_name,face,ability_values,authority_version)
            VALUES($1,0,repeat('1',64),repeat('2',64),2,0,0,'Pilot','First','Ship',5,
                ARRAY[1,2,3,4,5,6,7,8]::smallint[],1) RETURNING character_id
            """);
        character.Parameters.AddWithValue(owner);
        return (owner,(long)(await character.ExecuteScalarAsync(ct))!);
    }
}
