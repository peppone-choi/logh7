using Logh7.Server.Storage;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Compatibility;
using System.Buffers.Binary;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalFleetControlTransactionTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;
    [Theory(Skip="Requires isolated PostgreSQL",SkipUnless=nameof(HasTestDatabase))]
    [InlineData(false,false)]
    [InlineData(true,false)]
    [InlineData(true,true)]
    public async Task Assignment_batch_commits_all_and_rolls_back_on_later_revision_or_foreign_key_failure(bool liveRefresh,bool tickOnly)
    {
        var connection=Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema="fleet_control_"+Guid.NewGuid().ToString("N");
        await using(var admin=NpgsqlDataSource.Create(connection))
        await using(var create=admin.CreateCommand("CREATE SCHEMA "+schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var config=new NpgsqlConnectionStringBuilder(connection){SearchPath=schema};
        await using var data=NpgsqlDataSource.Create(config.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data,PostgresMigrationRunner.MigrationDirectory,
            TestContext.Current.CancellationToken);
        await using var seed=data.CreateCommand("""
            WITH owner AS (
                INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                    argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
                VALUES($1,'commander',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64))
                RETURNING account_id)
            INSERT INTO character(account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                last_name,first_name,flagship_name,face,ability_values,authority_version)
            SELECT account_id,0,repeat('1',64),repeat('2',64),2,0,0,'Pilot','First','Ship',5,
                ARRAY[1,2,3,4,5,6,7,8]::smallint[],1 FROM owner RETURNING character_id
            """);
        var account=Guid.NewGuid();
        seed.Parameters.AddWithValue(account);
        var character=(long)(await seed.ExecuteScalarAsync(TestContext.Current.CancellationToken))!;
        var store=new PostgresFleetUnitStore(data);
        var initial=new OriginalFleetUnitRecord(2113929217,2113929312,56,2,0,101,300,25,10,3,4,0,0,10);
        await store.EnsureCreatedAsync(initial,TestContext.Current.CancellationToken);
        await store.EnsureCreatedAsync(initial with { UnitId=2113929218 },TestContext.Current.CancellationToken);
        var first=new OriginalFleetControlWrite(initial.UnitId,101,initial.OutfitId,0,1,character,false);
        var second=first with { UnitId=2113929218 };
        Assert.True(await store.SaveControlAssignmentsAsync([first,second],TestContext.Current.CancellationToken));
        await using var reopened=NpgsqlDataSource.Create(config.ConnectionString);
        var reader=new PostgresFleetUnitStore(reopened);
        var saved=await reader.ReadGridAsync(101,TestContext.Current.CancellationToken);
        Assert.All(saved,row=>
        {
            Assert.Equal(character,row.ControllerCharacterId);
            Assert.False(row.Autonomous);
            Assert.Equal(2,row.Revision);
            Assert.Equal(25,row.Damaged);
            Assert.Equal(3f,row.X);
        });
        var releaseFirst=first with { ExpectedRevision=2,ControllerCharacterId=null,Autonomous=true };
        var releaseSecond=second with { ExpectedRevision=2,ControllerCharacterId=null,Autonomous=true };
        Assert.False(await store.SaveControlAssignmentsAsync([releaseFirst,releaseSecond with { ExpectedRevision=1 }],
            TestContext.Current.CancellationToken));
        Assert.Equal(saved,await reader.ReadGridAsync(101,TestContext.Current.CancellationToken));
        var error=await Assert.ThrowsAsync<PostgresException>(()=>store.SaveControlAssignmentsAsync(
            [releaseFirst,releaseSecond with { ControllerCharacterId=999999 }],TestContext.Current.CancellationToken));
        Assert.Equal("23503",error.SqlState);
        Assert.Equal(saved,await reader.ReadGridAsync(101,TestContext.Current.CancellationToken));
        Assert.True(await store.SaveControlAssignmentsAsync([releaseFirst,releaseSecond],TestContext.Current.CancellationToken));
        var released=await reader.ReadGridAsync(101,TestContext.Current.CancellationToken);
        var registry=new OriginalTacticalBattleRegistry();
        var fleet=new OriginalBattlefieldFleet(initial.OutfitId,2,0,0,0,
            [new(initial.UnitId,56,new(3,4,0,0)),new(2113929218,56,new(3,4,0,0))]);
        using(await registry.LockAsync(101,100,TestContext.Current.CancellationToken))
        {
            foreach(var snapshot in fleet.Project(101))
            {
                registry.RegisterNpc(snapshot,OriginalSubordinateShipCatalog.Capabilities,OriginalAuthoredPlayableCatalog.TacticalArms);
                var row=Assert.Single(released,x=>x.UnitId==snapshot.Unit.Id);
                registry.GetEncounter(101,100).RecordUnitDamage(row.UnitId,new(row.Damaged,row.Destroyed));
                registry.BindFleetUnitPersistence(row,store);
            }
            var corps=OriginalSystemSceneCodec.CreatePlayableTacticalCorps(checked((uint)character))
                with { PowerBeam=7,PowerGun=9,CommandRange=1234 };
            OriginalNpcControlAssignment[] assignments=[new(initial.UnitId,checked((uint)character),corps,false,0,checked((uint)character),0),
                new(2113929218,checked((uint)character),corps,false,0,checked((uint)character),0)];
            Assert.True(await registry.CommitFleetControlAssignmentsAsync(101,assignments,TestContext.Current.CancellationToken));
            Assert.All(registry.OtherParticipants(101,0),p=>Assert.Equal((uint)character,p.Ship.Character));
            // The assignment must advance the registry's durable revision too.
            await registry.CommitUnitDamageAsync(101,100,initial.UnitId,new(40,15),TestContext.Current.CancellationToken);
            var current=await reader.ReadGridAsync(101,TestContext.Current.CancellationToken);
            Assert.Equal(5,Assert.Single(current,x=>x.UnitId==initial.UnitId).Revision);
            Assert.Equal((ushort)40,Assert.Single(current,x=>x.UnitId==initial.UnitId).Damaged);
            var secondCurrent=Assert.Single(current,x=>x.UnitId==2113929218);
            Assert.True(await store.SaveAsync(secondCurrent with { Cruising=8 },TestContext.Current.CancellationToken));
            var before=registry.NpcSnapshot(101,initial.UnitId);
            Assert.False(await registry.CommitFleetControlAssignmentsAsync(101,
                assignments.Select(a=>a with { Autonomous=true,Corps=a.Corps with { PowerBeam=33 } }).ToArray(),
                TestContext.Current.CancellationToken));
            Assert.Same(before,registry.NpcSnapshot(101,initial.UnitId));
            Assert.False(Assert.Single(await reader.ReadGridAsync(101,TestContext.Current.CancellationToken),
                x=>x.UnitId==initial.UnitId).Autonomous);
        }
        // A fresh registry/connection must restore the real controller, not the
        // authored fleet placeholder, and must not restart autonomous movement.
        var freshRegistry=new OriginalTacticalBattleRegistry();
        var session=OriginalPlayerCombatTests.Session(freshRegistry,checked((uint)character),2,
            OriginalBattlefieldCatalog.Parse(OriginalFleetContentTests.Document().ToJsonString()));
        var roster=(IAccountStore)typeof(NaturalAuthoritySession).GetField("_store",
            System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic)!.GetValue(session)!;
        OriginalWarpSessionClockTests.SetField(session,"_store",new OriginalFleetSceneRestoreTests.Store(reopened,roster));
        var scene=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"),new byte[16],1),TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,scene.Status);
        var restored=Assert.IsType<OriginalTacticalParticipantSnapshot>(freshRegistry.NpcSnapshot(101,initial.UnitId));
        Assert.Equal((uint)character,restored.Ship.Character);
        Assert.Equal((uint)character,restored.Corps.Id);
        Assert.Equal((byte)7,restored.Corps.PowerBeam);
        Assert.Equal((byte)9,restored.Corps.PowerGun);
        Assert.Equal(1234f,restored.Corps.CommandRange);
        var frames=new[]{scene.ResponsePayload!}.Concat(scene.AdditionalResponses!.Select(p=>p.Payload))
            .Select(frame=>OriginalClientInnerFrameCodec.Decode(frame,new byte[16],0).Payload!).ToArray();
        var corpsFrame=Assert.Single(frames,frame=>BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4))==0x033f);
        Assert.Equal((uint)character,BinaryPrimitives.ReadUInt32BigEndian(corpsFrame.AsSpan(8)));
        Assert.Equal((byte)7,corpsFrame[27]);
        var distribution=Convert.FromHexString("040C0000000000000000000000020000000200320A14040403030303140A14");
        BinaryPrimitives.WriteUInt32BigEndian(distribution.AsSpan(10),(uint)character);
        BinaryPrimitives.WriteUInt32BigEndian(distribution.AsSpan(14),(uint)character);
        OriginalWarpSessionClockTests.SetField(session,"_accountId",Guid.NewGuid());
        var unauthorized=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(
            distribution,new byte[16],2),TestContext.Current.CancellationToken);
        Assert.StartsWith("command-reject=TACTICAL_CONTROL_SAVE_CONFLICT",unauthorized.ResponseMetadata);
        Assert.Equal((byte)7,freshRegistry.NpcSnapshot(101,initial.UnitId)!.Corps.PowerBeam);
        OriginalWarpSessionClockTests.SetField(session,"_accountId",account);
        var changed=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(
            distribution,new byte[16],3),TestContext.Current.CancellationToken);
        Assert.StartsWith("tactical-control-accepted",changed.ResponseMetadata);
        Assert.Equal((byte)10,freshRegistry.NpcSnapshot(101,initial.UnitId)!.Corps.PowerBeam);
        var afterControl=await reader.ReadControllerCorpsAsync((uint)character,TestContext.Current.CancellationToken);
        Assert.True(afterControl.HasValue);
        Assert.Equal((byte)10,afterControl.Value.Corps.PowerBeam);
        Assert.False(await store.SavePlayerCorpsAsync(account,(uint)character,0,
            restored.Corps,restored.Corps with { PowerBeam=12 },TestContext.Current.CancellationToken));
        Assert.Equal((byte)10,(await reader.ReadControllerCorpsAsync((uint)character,
            TestContext.Current.CancellationToken))!.Value.Corps.PowerBeam);
        var restartRegistry=new OriginalTacticalBattleRegistry();
        var restart=OriginalPlayerCombatTests.Session(restartRegistry,(uint)character,2,
            OriginalBattlefieldCatalog.Parse(OriginalFleetContentTests.Document().ToJsonString()));
        OriginalWarpSessionClockTests.SetField(restart,"_store",new OriginalFleetSceneRestoreTests.Store(reopened,roster));
        await restart.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"),new byte[16],1),TestContext.Current.CancellationToken);
        Assert.Equal((byte)10,restartRegistry.NpcSnapshot(101,initial.UnitId)!.Corps.PowerBeam);
        // No owner session exists in this fresh registry. A different viewer
        // must still receive the persisted controller's public character record.
        await using(var merit=data.CreateCommand("UPDATE character SET rank=7,achievement=1234,pcp=987,mcp=654 WHERE character_id=$1"))
        {
            merit.Parameters.AddWithValue(character);
            await merit.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        }
        var observerRegistry=new OriginalTacticalBattleRegistry();
        if(liveRefresh)
        {
            var existing=restartRegistry.NpcSnapshot(101,initial.UnitId)!;
            observerRegistry.RegisterNpc(new(existing.Unit with { Supplies=47 },
                existing.Ship with { X=15 },existing.Corps,existing.CharacterFrame.ToArray(),
                existing.Power,existing.ShipGeneration,existing.Outfit,existing.CommanderMerit),
                OriginalSubordinateShipCatalog.CapabilitiesFor(initial.Kind),OriginalAuthoredPlayableCatalog.TacticalArms);
        }
        var observer=OriginalPlayerCombatTests.Session(observerRegistry,60000,2,
            OriginalBattlefieldCatalog.Parse(OriginalFleetContentTests.Document().ToJsonString()));
        var observerRoster=(IAccountStore)typeof(NaturalAuthoritySession).GetField("_store",
            System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic)!.GetValue(observer)!;
        OriginalWarpSessionClockTests.SetField(observer,"_store",new OriginalFleetSceneRestoreTests.Store(reopened,observerRoster));
        var observerScene=await observer.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"),new byte[16],1),TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,observerScene.Status);
        var query=new byte[6];
        BinaryPrimitives.WriteUInt16BigEndian(query,0x0322);
        BinaryPrimitives.WriteUInt32BigEndian(query.AsSpan(2),(uint)character);
        var publicReply=await observer.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(query,new byte[16],2),
            TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,publicReply.Status);
        var publicCharacter=OriginalClientInnerFrameCodec.Decode(publicReply.ResponsePayload!,new byte[16],0).Payload!;
        Assert.Equal((uint)character,BinaryPrimitives.ReadUInt32BigEndian(publicCharacter.AsSpan(6)));
        Assert.Equal((ushort)7,BinaryPrimitives.ReadUInt16BigEndian(publicCharacter.AsSpan(publicCharacter.Length-62)));
        Assert.Equal(1234u,BinaryPrimitives.ReadUInt32BigEndian(publicCharacter.AsSpan(publicCharacter.Length-47)));
        // Stored flagship name "Ship" is four UTF16 elements, so PCP starts63.
        Assert.Equal(0u,BinaryPrimitives.ReadUInt32BigEndian(publicCharacter.AsSpan(63)));
        Assert.Equal(0u,BinaryPrimitives.ReadUInt32BigEndian(publicCharacter.AsSpan(67)));
        Assert.Equal(new OriginalTacticalCommanderMerit((uint)character,7,1234),
            observerRegistry.NpcSnapshot(101,initial.UnitId)!.CommanderMerit);
        if(liveRefresh)
        {
            Assert.Equal(15f,observerRegistry.NpcSnapshot(101,initial.UnitId)!.Ship.X);
            Assert.Equal(47u,observerRegistry.NpcSnapshot(101,initial.UnitId)!.Unit.Supplies);
        }
        var scopedRow=Assert.Single(await reader.ReadGridAsync(101,TestContext.Current.CancellationToken),
            row=>row.UnitId==initial.UnitId);
        Assert.NotNull(await reader.ReadControllerCharacterAsync(scopedRow,TestContext.Current.CancellationToken));
        foreach(var staleRead in new[] {
            scopedRow with { GridId=102 }, scopedRow with { Generation=scopedRow.Generation+1 },
            scopedRow with { Revision=scopedRow.Revision+1 }, scopedRow with { ControllerCharacterId=999999 } })
            Assert.Null(await reader.ReadControllerCharacterAsync(staleRead,TestContext.Current.CancellationToken));
        var events=new List<OriginalNpcEvent>();
        for(uint tick=100;tick<125;tick+=6)
            events.AddRange(await freshRegistry.AdvanceNpcsAsync(tick,TestContext.Current.CancellationToken));
        Assert.DoesNotContain(events,e=>e.Actor==initial.UnitId && e.Action is "move" or "fire");
        Assert.Equal(3f,freshRegistry.NpcSnapshot(101,initial.UnitId)!.Ship.X);
        var healthy=Assert.Single(await reader.ReadGridAsync(101,TestContext.Current.CancellationToken),
            row=>row.UnitId==initial.UnitId);
        Assert.Equal(healthy,await store.ReleaseUnavailableControllerAsync(healthy,TestContext.Current.CancellationToken));
        await Assert.ThrowsAsync<InvalidDataException>(()=>store.ReleaseUnavailableControllerAsync(
            healthy with { ControllerUnitId=null,ControllerGeneration=null },TestContext.Current.CancellationToken));
        var orphanRegistry=new OriginalTacticalBattleRegistry();
        var orphanScene=OriginalPlayerCombatTests.Session(orphanRegistry,(uint)character,2,
            OriginalBattlefieldCatalog.Parse(OriginalFleetContentTests.Document().ToJsonString()));
        OriginalWarpSessionClockTests.SetField(orphanScene,"_store",new OriginalFleetSceneRestoreTests.Store(reopened,roster));
        if(liveRefresh)
            await orphanScene.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(
                Convert.FromHexString("0F02"),new byte[16],1),TestContext.Current.CancellationToken);
        await using(var replace=data.CreateCommand(
            "UPDATE original_grid_unit SET ship_generation=ship_generation+1 WHERE character_id=$1"))
        {
            replace.Parameters.AddWithValue(character);
            Assert.Equal(1,await replace.ExecuteNonQueryAsync(TestContext.Current.CancellationToken));
        }
        Assert.Null(await reader.ReadControllerCorpsAsync((uint)character,TestContext.Current.CancellationToken));
        // NEW DESIGN: proven loss of the controlling incarnation releases only
        // control to the authored fleet AI; it must not erase surviving hulls.
        if(tickOnly)
            await orphanRegistry.AdvanceNpcsAsync(990,TestContext.Current.CancellationToken);
        else
        {
            var orphanResult=await orphanScene.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(
                Convert.FromHexString("0F02"),new byte[16],liveRefresh ? 2u : 1u),TestContext.Current.CancellationToken);
            Assert.Equal(NaturalAuthoritySessionStatus.Success,orphanResult.Status);
        }
        var releasedOrphan=Assert.Single(await reader.ReadGridAsync(101,TestContext.Current.CancellationToken),
            row=>row.UnitId==initial.UnitId);
        Assert.Null(releasedOrphan.ControllerCharacterId);
        Assert.True(releasedOrphan.Autonomous);
        Assert.Equal((ushort)300,releasedOrphan.Number);
        Assert.Equal((ushort)40,releasedOrphan.Damaged);
        Assert.Equal((ushort)15,releasedOrphan.Destroyed);
        if(!tickOnly) Assert.Equal(3f,releasedOrphan.X);
        var orphanEvents=new List<OriginalNpcEvent>();
        for(uint tick=1000;tick<1100;tick+=6)
            orphanEvents.AddRange(await orphanRegistry.AdvanceNpcsAsync(tick,TestContext.Current.CancellationToken));
        Assert.Contains(orphanEvents,e=>e.Actor==initial.UnitId && e.Action is "move" or "fire");
        var beforeStale=Assert.Single(await reader.ReadGridAsync(101,TestContext.Current.CancellationToken),
            row=>row.UnitId==initial.UnitId);
        var stale=new OriginalFleetControlWrite(initial.UnitId,101,initial.OutfitId,
            beforeStale.Generation,beforeStale.Revision,character,false,restored.Corps,(uint)character,0);
        Assert.False(await store.SaveControlAssignmentsAsync([stale],TestContext.Current.CancellationToken));
        Assert.Equal(beforeStale,Assert.Single(await reader.ReadGridAsync(101,TestContext.Current.CancellationToken),
            row=>row.UnitId==initial.UnitId));
        Assert.Null(await reader.ReadControllerCorpsAsync((uint)character,TestContext.Current.CancellationToken));
        Assert.True(await store.SaveControlAssignmentsAsync(
            [stale with { ControllerGeneration=1,Corps=restored.Corps with { PowerBeam=11 } }],
            TestContext.Current.CancellationToken));
        var latest=await reader.ReadControllerCorpsAsync((uint)character,TestContext.Current.CancellationToken);
        Assert.True(latest.HasValue);
        Assert.Equal((byte)11,latest.Value.Corps.PowerBeam);
        await using(var injury=data.CreateCommand(
            "UPDATE original_grid_unit SET injury_return_id=$1,injury_return_request_hash=repeat('a',64) WHERE character_id=$2"))
        {
            injury.Parameters.AddWithValue(Guid.NewGuid());
            injury.Parameters.AddWithValue(character);
            Assert.Equal(1,await injury.ExecuteNonQueryAsync(TestContext.Current.CancellationToken));
        }
        var injuredRow=Assert.Single(await reader.ReadGridAsync(101,TestContext.Current.CancellationToken),
            row=>row.UnitId==initial.UnitId);
        Assert.False(await store.SavePlayerCorpsAsync(account,(uint)character,1,latest.Value.Corps,
            latest.Value.Corps with { PowerBeam=12 },TestContext.Current.CancellationToken));
        Assert.False(await store.SaveControlAssignmentsAsync(
            [stale with { ExpectedRevision=injuredRow.Revision,ControllerGeneration=1,Corps=latest.Value.Corps }],
            TestContext.Current.CancellationToken));
        Assert.Equal(injuredRow,Assert.Single(await reader.ReadGridAsync(101,TestContext.Current.CancellationToken),
            row=>row.UnitId==initial.UnitId));
        Assert.Equal((byte)11,(await reader.ReadControllerCorpsAsync((uint)character,
            TestContext.Current.CancellationToken))!.Value.Corps.PowerBeam);
        var injuryReleased=await store.ReleaseUnavailableControllerAsync(injuredRow,TestContext.Current.CancellationToken);
        Assert.Equal(injuredRow with { ControllerCharacterId=null,ControllerUnitId=null,ControllerGeneration=null,
            Autonomous=true,Revision=injuredRow.Revision+1 },injuryReleased);
    }
}
