using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalUnitModePostgresTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;

    [Fact(Skip="Requires isolated PostgreSQL", SkipUnless=nameof(HasTestDatabase))]
    public async Task Upgrade_reopen_movement_and_historical_replay_preserve_mode_without_changing_legacy_rows()
    {
        var connection=Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema="unit_mode_"+Guid.NewGuid().ToString("N");
        await using(var admin=NpgsqlDataSource.Create(connection))
        await using(var create=admin.CreateCommand("CREATE SCHEMA "+schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var builder=new NpgsqlConnectionStringBuilder(connection){SearchPath=schema};
        await using var data=NpgsqlDataSource.Create(builder.ConnectionString);
        foreach(var migration in Directory.GetFiles(PostgresMigrationRunner.MigrationDirectory,"*.sql").Order(StringComparer.Ordinal))
            if(string.CompareOrdinal(Path.GetFileName(migration),"0024_original_unit_mode.sql")<0)
                await PostgresMigrationRunner.ApplyAsync(data,migration,CancellationToken.None);
        var owner=Guid.NewGuid();
        await using(var seed=data.CreateCommand("""
            INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
            VALUES($1,'unit_mode',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64))
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
        object? before;
        await using(var read=data.CreateCommand("SELECT to_jsonb(u) FROM original_grid_unit u"))
            before=await read.ExecuteScalarAsync(TestContext.Current.CancellationToken);
        await PostgresMigrationRunner.ApplyAllAsync(data,PostgresMigrationRunner.MigrationDirectory,CancellationToken.None);
        await using(var column=data.CreateCommand("""
            SELECT count(*) FROM information_schema.columns
            WHERE table_schema=current_schema() AND table_name='original_grid_unit' AND column_name='mode'
            """))
            Assert.Equal(1L,(long)(await column.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
        await using(var read=data.CreateCommand("SELECT to_jsonb(u)-'mode'-'unit_number'-'supplies'-'morale' FROM original_grid_unit u"))
            Assert.Equal(before,await read.ExecuteScalarAsync(TestContext.Current.CancellationToken));
        await using(var read=data.CreateCommand("SELECT unit_number FROM original_grid_unit"))
            Assert.Equal(100,(int)(await read.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
        IAccountStore store=new PostgresAccountStore(data);
        var unitId=checked((uint)character);
        Assert.Equal((byte)0,Assert.IsType<OriginalGridUnitRecord>(
            await store.FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None)).Mode);
        // Test-only fixture assignment. This is not a production departure.
        await using(var seed=data.CreateCommand("UPDATE original_grid_unit SET mode=5,damaged=25,destroyed=10,ship_generation=3"))
            await seed.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var unit=Assert.IsType<OriginalGridUnitRecord>(
            await store.FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None));
        Assert.Equal((byte)5,unit.Mode);
        var first=new OriginalMoveGridWrite(new string('a',64),character,unitId,39,101,101,102,0x2b);
        var moved=await store.MoveOriginalGridUnitAsync(owner,first,CancellationToken.None);
        Assert.Equal(OriginalMoveGridStoreStatus.Moved,moved.Status);
        Assert.Equal((byte)6,Assert.IsType<OriginalGridUnitRecord>(moved.Unit).Mode);
        await using(var eventMode=data.CreateCommand(
            "SELECT (payload->>'mode')::integer FROM domain_event WHERE event_type='OriginalGridUnitMoved'"))
            Assert.Equal(6,(int)(await eventMode.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
        // Independent expected canonical input; changing/removing mode in the
        // state hash must fail even when the persisted row remains correct.
        var canonical=FormattableString.Invariant(
            $"{{\"accountId\":\"{owner:D}\",\"authorityVersion\":2,\"characterId\":{character},\"unitId\":{unitId},\"sourceCellId\":101,\"destinationCellId\":102,\"cruisingBits\":1091567616,\"shipGeneration\":3,\"requestFingerprint\":\"{new string('a',64)}\",\"mode\":6}}");
        var expectedHash=Convert.ToHexStringLower(SHA256.HashData(
            Encoding.UTF8.GetBytes("logh7-authority-state/move-grid-v3\n"+canonical)));
        await using(var hash=data.CreateCommand("SELECT authority_state_hash FROM account WHERE account_id=$1"))
        {
            hash.Parameters.AddWithValue(owner);
            Assert.Equal(expectedHash,(string)(await hash.ExecuteScalarAsync(TestContext.Current.CancellationToken))!);
        }
        await PostgresMigrationRunner.ApplyAllAsync(data,PostgresMigrationRunner.MigrationDirectory,CancellationToken.None);
        await using(var reopened=NpgsqlDataSource.Create(builder.ConnectionString))
            Assert.Equal(moved.Unit,await new PostgresAccountStore(reopened)
                .FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None));
        // A real encrypted session uses the persisted arrival mode for0B07;
        // neither the requested route mode nor the old orbital stance wins.
        var wireKey=new byte[16];
        var wireSession=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,wireKey,
            store:store,battles:new OriginalTacticalBattleRegistry());
        OriginalWarpSessionClockTests.SetField(wireSession,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(wireSession,"_worldCharacterId",unitId);
        OriginalWarpSessionClockTests.SetField(wireSession,"_worldGridUnitId",unitId);
        await wireSession.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),wireKey,1),CancellationToken.None);
        await wireSession.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"),wireKey,2),CancellationToken.None);
        var warp=new byte[33];
        BinaryPrimitives.WriteUInt16BigEndian(warp,0x0B01);
        BinaryPrimitives.WriteUInt32BigEndian(warp.AsSpan(10),unitId);
        BinaryPrimitives.WriteUInt16BigEndian(warp.AsSpan(14),39);
        BinaryPrimitives.WriteUInt16BigEndian(warp.AsSpan(24),101);
        BinaryPrimitives.WriteUInt16BigEndian(warp.AsSpan(31),4);
        var answer=await wireSession.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(warp,wireKey,3),CancellationToken.None);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,answer.Status);
        var completion=OriginalClientInnerFrameCodec.Decode(answer.ResponsePayload!,wireKey,0).Payload!;
        Assert.Equal((ushort)0x0B07,BinaryPrimitives.ReadUInt16BigEndian(completion.AsSpan(4)));
        Assert.Equal((ushort)6,BinaryPrimitives.ReadUInt16BigEndian(completion.AsSpan(22)));
        // The arrival joins the destination grid's own base. Arriving with no
        // base at all left the client refusing 態勢変更 forever.
        Assert.Equal(1u,BinaryPrimitives.ReadUInt32BigEndian(completion.AsSpan(18)));
        Assert.Equal(8f,BinaryPrimitives.ReadSingleBigEndian(completion.AsSpan(29)));
        await using(var later=data.CreateCommand("UPDATE original_grid_unit SET mode=7"))
            await later.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var replay=await store.MoveOriginalGridUnitAsync(owner,first,CancellationToken.None);
        Assert.Equal(OriginalMoveGridStoreStatus.Replayed,replay.Status);
        Assert.Equal(moved.Unit,replay.Unit);
        Assert.Equal((byte)7,Assert.IsType<OriginalGridUnitRecord>(
            await store.FindOriginalGridUnitAsync(owner,character,unitId,CancellationToken.None)).Mode);
        // New data source and session, actual encrypted requests and response
        // decoding. Authentication setup is a fixture, not a native login claim.
        for(var attempt=0;attempt<2;attempt++)
        {
            await using var reopened=NpgsqlDataSource.Create(builder.ConnectionString);
            var key=new byte[16];
            var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,
                store:new PostgresAccountStore(reopened),battles:new OriginalTacticalBattleRegistry());
            OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
            OriginalWarpSessionClockTests.SetField(session,"_worldCharacterId",unitId);
            OriginalWarpSessionClockTests.SetField(session,"_worldGridUnitId",unitId);
            await session.ProcessAsync(0x30,
                OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),CancellationToken.None);
            var refresh=await session.ProcessAsync(0x30,
                OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"),key,2),CancellationToken.None);
            Assert.Equal(NaturalAuthoritySessionStatus.Success,refresh.Status);
            var frames=new[]{refresh.ResponsePayload!}.Concat(refresh.AdditionalResponses?.Select(p=>p.Payload)??[])
                .Select(f=>OriginalClientInnerFrameCodec.Decode(f,key,0).Payload!).ToArray();
            var information=Assert.Single(frames,f=>BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))==0x325);
            Assert.Equal(unitId,BinaryPrimitives.ReadUInt32BigEndian(information.AsSpan(8)));
            Assert.Equal((byte)7,information[14]);
        }
        foreach(var invalid in new[]{-1,256})
        {
            await using var bad=data.CreateCommand("UPDATE original_grid_unit SET mode=$1");
            bad.Parameters.AddWithValue(invalid);
            var error=await Assert.ThrowsAsync<PostgresException>(()=>bad.ExecuteNonQueryAsync(TestContext.Current.CancellationToken));
            Assert.Equal(PostgresErrorCodes.CheckViolation,error.SqlState);
        }
    }
}
