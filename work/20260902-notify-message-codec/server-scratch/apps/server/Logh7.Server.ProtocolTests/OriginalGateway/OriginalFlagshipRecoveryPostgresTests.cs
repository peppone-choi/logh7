using Logh7.Server.Storage;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Compatibility;
using System.Buffers.Binary;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalFlagshipRecoveryPostgresTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;

    // Catches clearing the injury flag without atomically archiving the loss,
    // issuing the faction fallback, and advancing the active ship incarnation.
    [Theory(Skip="Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless=nameof(HasTestDatabase))]
    [InlineData(2, 3)]
    [InlineData(3, 93)]
    public async Task Recovery_preserves_loss_and_issues_one_destroyer_across_retries(short faction, ushort kind)
    {
        var connection = Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema = "flagship_recovery_" + Guid.NewGuid().ToString("N");
        await using (var admin = NpgsqlDataSource.Create(connection))
        await using (var create = admin.CreateCommand("CREATE SCHEMA " + schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var builder = new NpgsqlConnectionStringBuilder(connection) { SearchPath=schema };
        await using var data = NpgsqlDataSource.Create(builder.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data,PostgresMigrationRunner.MigrationDirectory,CancellationToken.None);
        var owner = Guid.NewGuid();
        await using (var seed=data.CreateCommand("""
            INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
            VALUES($1,'recovery_owner',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64))
            """))
        {
            seed.Parameters.AddWithValue(owner);
            await seed.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        }
        long character;
        await using (var seed=data.CreateCommand("""
            INSERT INTO character(account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                last_name,first_name,flagship_name,face,ability_values,authority_version,return_base_id,
                flagship_type,flagship_kind)
            VALUES($1,0,repeat('1',64),repeat('2',64),$2,0,0,'Pilot','First','Lost flagship',5,
                ARRAY[1,2,3,4,5,6,7,8]::smallint[],1,2,0,12)
            RETURNING character_id
            """))
        {
            seed.Parameters.AddWithValue(owner); seed.Parameters.AddWithValue(faction);
            character=(long)(await seed.ExecuteScalarAsync(TestContext.Current.CancellationToken))!;
        }
        IAccountStore store=new PostgresAccountStore(data);
        var unitId=checked((uint)character);
        var defeat=new OriginalInjuryReturnWrite(Guid.NewGuid(),character,unitId,1,101,102,2,100,100,100);
        var returned=await store.ReturnInjuredOriginalUnitAsync(owner,defeat,CancellationToken.None);
        // Re-applying migrations must not itself grant or heal a ship.
        await PostgresMigrationRunner.ApplyAllAsync(data,PostgresMigrationRunner.MigrationDirectory,CancellationToken.None);
        Assert.Equal(returned.Unit,await store.FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None));
        var stale=await Assert.ThrowsAsync<InvalidOperationException>(()=>store.RecoverOriginalFlagshipAsync(
            owner,character,unitId,Guid.NewGuid(),returned.Unit.AuthorityVersion,CancellationToken.None));
        Assert.Equal("FLAGSHIP_RECOVERY_SOURCE_STALE",stale.Message);
        var unowned=await Assert.ThrowsAsync<InvalidOperationException>(()=>store.RecoverOriginalFlagshipAsync(
            owner,character+1,unitId,defeat.DefeatId,returned.Unit.AuthorityVersion,CancellationToken.None));
        Assert.Equal("FLAGSHIP_RECOVERY_UNIT_NOT_OWNED",unowned.Message);
        await using (var reject=data.CreateCommand("""
            ALTER TABLE domain_event ADD CONSTRAINT reject_flagship_recovery
            CHECK(event_type <> 'OriginalFlagshipRecovered') NOT VALID
            """))
            await reject.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var failure=await Assert.ThrowsAsync<PostgresException>(()=>store.RecoverOriginalFlagshipAsync(
            owner,character,unitId,defeat.DefeatId,returned.Unit.AuthorityVersion,CancellationToken.None));
        Assert.Equal(PostgresErrorCodes.CheckViolation,failure.SqlState);
        Assert.Equal(returned.Unit,await store.FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None));
        Assert.Equal((ushort)12,Assert.Single(await store.ListCharactersAsync(owner,CancellationToken.None)).FlagshipKind);
        await using (var pending=data.CreateCommand("SELECT count(*) FROM original_flagship_recovery"))
            Assert.Equal(0L,(long)(await pending.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
        await using (var allow=data.CreateCommand("ALTER TABLE domain_event DROP CONSTRAINT reject_flagship_recovery"))
            await allow.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var initialRequests=await Task.WhenAll(Enumerable.Range(0,8).Select(_=>
            store.RecoverOriginalFlagshipAsync(owner,character,unitId,defeat.DefeatId,
                returned.Unit.AuthorityVersion,CancellationToken.None)));
        var recovered=Assert.Single(initialRequests,r=>r.Updated);
        Assert.All(initialRequests,r=>Assert.Equal(recovered.Unit,r.Unit));
        Assert.True(recovered.Updated);
        Assert.Equal((unitId,102u,2u,0,0,1L,3L),
            (recovered.Unit.UnitId,recovered.Unit.CurrentCellId,recovered.Unit.BaseId,
             (int)recovered.Unit.Damaged,(int)recovered.Unit.Destroyed,recovered.Unit.ShipGeneration,recovered.Unit.AuthorityVersion));
        Assert.Null(recovered.Unit.InjuryReturnId);
        Assert.Equal((byte)4,recovered.Unit.Mode);
        var pilot=Assert.Single(await store.ListCharactersAsync(owner,CancellationToken.None));
        Assert.Equal(kind,pilot.FlagshipKind);
        Assert.Equal((byte)0,pilot.FlagshipType);
        Assert.Equal("Lost flagship",pilot.FlagshipName); // Personal name is not a ship-class identity.
        var repeats=await Task.WhenAll(Enumerable.Range(0,8).Select(_=>
            store.RecoverOriginalFlagshipAsync(owner,character,unitId,defeat.DefeatId,
                returned.Unit.AuthorityVersion,CancellationToken.None)));
        Assert.All(repeats,r=>{Assert.False(r.Updated);Assert.Equal(recovered.Unit,r.Unit);});
        await using (var reopened=NpgsqlDataSource.Create(builder.ConnectionString))
            Assert.Equal(recovered.Unit,await new PostgresAccountStore(reopened).FindOriginalGridUnitAsync(
                owner,character,unitId,CancellationToken.None));
        await using (var history=data.CreateCommand("""
            SELECT count(*) FROM domain_event WHERE event_type='OriginalUnitInjuryReturned'
            AND (payload->>'damaged')::int=100 AND (payload->>'destroyed')::int=100
            """))
            Assert.Equal(1L,(long)(await history.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
        await using (var issuance=data.CreateCommand("SELECT count(*) FROM domain_event WHERE event_type='OriginalFlagshipRecovered'"))
            Assert.Equal(1L,(long)(await issuance.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
        // The old return may be retried after recovery. It must not re-kill the
        // replacement, and conflicting payloads must still be rejected.
        var oldReturn=await store.ReturnInjuredOriginalUnitAsync(owner,defeat,CancellationToken.None);
        Assert.False(oldReturn.Updated);
        Assert.Equal(recovered.Unit,oldReturn.Unit);
        var conflict=await Assert.ThrowsAsync<InvalidOperationException>(()=>store.ReturnInjuredOriginalUnitAsync(
            owner,defeat with { DestinationBase=1 },CancellationToken.None));
        Assert.Equal("INJURY_RETURN_REPLAY_CONFLICT",conflict.Message);
        var recoveryConflict=await Assert.ThrowsAsync<InvalidOperationException>(()=>store.RecoverOriginalFlagshipAsync(
            owner,character,unitId,defeat.DefeatId,returned.Unit.AuthorityVersion+1,CancellationToken.None));
        Assert.Equal("FLAGSHIP_RECOVERY_REPLAY_CONFLICT",recoveryConflict.Message);
        // Storage-only second loss. Destination safety and encounter/life ID
        // derivation are session responsibilities, not claimed by this fixture.
        var nextDefeat=defeat with { DefeatId=Guid.NewGuid(),SourceGrid=102,ExpectedUnitVersion=3 };
        var nextReturn=await store.ReturnInjuredOriginalUnitAsync(owner,nextDefeat,CancellationToken.None);
        Assert.Equal((1L,(ushort)100), (nextReturn.Unit.ShipGeneration,nextReturn.Unit.Destroyed));
        var delayedRecovery=await store.RecoverOriginalFlagshipAsync(owner,character,unitId,defeat.DefeatId,
            returned.Unit.AuthorityVersion,CancellationToken.None);
        Assert.False(delayedRecovery.Updated);
        Assert.Equal(nextReturn.Unit,delayedRecovery.Unit);
        var delayedReturn=await store.ReturnInjuredOriginalUnitAsync(owner,defeat,CancellationToken.None);
        Assert.False(delayedReturn.Updated);
        Assert.Equal(nextReturn.Unit,delayedReturn.Unit);
        var nextRecovery=await store.RecoverOriginalFlagshipAsync(owner,character,unitId,nextDefeat.DefeatId,
            nextReturn.Unit.AuthorityVersion,CancellationToken.None);
        Assert.Equal((2L,5L,(ushort)0),
            (nextRecovery.Unit.ShipGeneration,nextRecovery.Unit.AuthorityVersion,nextRecovery.Unit.Destroyed));
        await using (var archived=data.CreateCommand("""
            SELECT count(*) FROM original_flagship_recovery
            WHERE destroyed=100 AND damaged=100 AND ship_generation IN (1,2)
            """))
            Assert.Equal(2L,(long)(await archived.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
        if(faction==2)
        {
            // Actual encrypted 0B01 requests, including a fresh identical
            // destination after a round trip. No SQL teleport stages departure.
            var battles=new OriginalTacticalBattleRegistry();
            var key=new byte[16];
            var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,store:store,battles:battles);
            OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
            OriginalWarpSessionClockTests.SetField(session,"_worldCharacterId",unitId);
            OriginalWarpSessionClockTests.SetField(session,"_worldGridUnitId",unitId);
            async Task<IReadOnlyList<byte[]>> Query(string hex,uint seq)
            {
                var result=await session.ProcessAsync(0x30,
                    OriginalClientInnerFrameCodec.Encode(Convert.FromHexString(hex),key,seq),CancellationToken.None);
                Assert.Equal(NaturalAuthoritySessionStatus.Success,result.Status);
                return new[]{result.ResponsePayload!}.Concat(result.AdditionalResponses?.Select(p=>p.Payload)??[])
                    .Select(f=>OriginalClientInnerFrameCodec.Decode(f,key,0).Payload!).ToArray();
            }
            await Query("0205",1);
            var bootstrap=await Query("0F02",2);
            var dockedPilot=Assert.Single(bootstrap,f=>BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))==0x0323
                && BinaryPrimitives.ReadUInt32BigEndian(f.AsSpan(6))==unitId);
            Assert.Equal(2001u,BinaryPrimitives.ReadUInt32BigEndian(dockedPilot.AsSpan(30)));
            Assert.Equal(0u,BinaryPrimitives.ReadUInt32BigEndian(dockedPilot.AsSpan(34)));
            uint sequence=3;
            foreach(var destination in new ushort[]{101,102,101})
            {
                var request=new byte[33];
                BinaryPrimitives.WriteUInt16BigEndian(request,0x0B01);
                BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(10),unitId);
                BinaryPrimitives.WriteUInt16BigEndian(request.AsSpan(14),39);
                BinaryPrimitives.WriteUInt16BigEndian(request.AsSpan(24),destination);
                var move=await Query(Convert.ToHexString(request),sequence++);
                var notification=Assert.Single(move,f=>BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))==0x0B07);
                var information=Assert.Single(move,f=>BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))==0x325);
                var expectedCruising=13f-sequence;
                Assert.Equal(expectedCruising,BitConverter.UInt32BitsToSingle(BinaryPrimitives.ReadUInt32BigEndian(notification.AsSpan(notification.Length-4))));
                Assert.Equal(unitId,BinaryPrimitives.ReadUInt32BigEndian(information.AsSpan(8)));
                Assert.Equal(expectedCruising,BitConverter.UInt32BitsToSingle(BinaryPrimitives.ReadUInt32BigEndian(information.AsSpan(46))));
                var moved=await store.FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None);
                Assert.NotNull(moved);
                // The arrival joins the destination grid's own base when the
                // grid holds one on this character's side; a foreign faction
                // still arrives in open space.
                var arrivalBase=faction==2 ? (uint)destination-100 : 0u;
                Assert.Equal(((uint)destination,arrivalBase,expectedCruising),(moved.CurrentCellId,moved.BaseId,moved.Cruising));
                var staleReturn=await store.ReturnInjuredOriginalUnitAsync(owner,defeat,CancellationToken.None);
                Assert.False(staleReturn.Updated);
                Assert.Equal(moved,staleReturn.Unit); // Old casualty must not re-dock a travelling replacement.
                Assert.Equal((byte)6,staleReturn.Unit.Mode);
            }
            // A fresh connection must project the stored 7, not refill based
            // on being back at grid 101. Separate registry prevents the test
            // reconnect from modifying this session's encounter fixture.
            var reconnectKey=new byte[16];
            await using(var reopened=NpgsqlDataSource.Create(builder.ConnectionString))
            {
                var reconnect=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,reconnectKey,
                    store:new PostgresAccountStore(reopened),battles:new OriginalTacticalBattleRegistry());
                OriginalWarpSessionClockTests.SetField(reconnect,"_accountId",owner);
                OriginalWarpSessionClockTests.SetField(reconnect,"_worldCharacterId",unitId);
                OriginalWarpSessionClockTests.SetField(reconnect,"_worldGridUnitId",unitId);
                await reconnect.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),reconnectKey,1),CancellationToken.None);
                var refresh=await reconnect.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"),reconnectKey,2),CancellationToken.None);
                Assert.Equal(NaturalAuthoritySessionStatus.Success,refresh.Status);
                var frames=new[]{refresh.ResponsePayload!}.Concat(refresh.AdditionalResponses?.Select(p=>p.Payload)??[])
                    .Select(f=>OriginalClientInnerFrameCodec.Decode(f,reconnectKey,0).Payload!).ToArray();
                var information=Assert.Single(frames,f=>BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))==0x325);
                Assert.Equal(unitId,BinaryPrimitives.ReadUInt32BigEndian(information.AsSpan(8)));
                Assert.Equal(7f,BitConverter.UInt32BitsToSingle(BinaryPrimitives.ReadUInt32BigEndian(information.AsSpan(46))));
            }
            battles.GetEncounter(101,100).RecordUnitDamage(unitId,new(100,100));
            var scene=await Query("0F02",sequence++);
            var projected=Assert.Single(scene,f=>BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))==0x325);
            Assert.Equal((ushort)0,BinaryPrimitives.ReadUInt16BigEndian(projected.AsSpan(36)));
            Assert.Equal((ushort)3,BinaryPrimitives.ReadUInt16BigEndian(projected.AsSpan(12)));
            var persisted=await store.FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None);
            Assert.NotNull(persisted);
            Assert.Equal((3L,102u,(ushort)0),(persisted.ShipGeneration,persisted.CurrentCellId,persisted.Destroyed));
            Assert.Equal(10f,persisted.Cruising); // NEW_DESIGN fresh fallback starts with full cruising.
            await Query("0F02",sequence++);
            Assert.Equal(persisted,await store.FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None));
            await using var events=data.CreateCommand("SELECT count(*) FROM domain_event WHERE event_type='OriginalFlagshipRecovered'");
            Assert.Equal(3L,(long)(await events.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
        }
    }
}
