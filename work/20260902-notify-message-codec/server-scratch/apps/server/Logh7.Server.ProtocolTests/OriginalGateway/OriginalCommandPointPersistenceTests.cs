using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalCommandPointPersistenceTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Reopened_database_and_new_sessions_project_owned_persisted_balances()
    {
        var ct = TestContext.Current.CancellationToken;
        var connection = Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema = "command_points_" + Guid.NewGuid().ToString("N");
        await using (var admin = NpgsqlDataSource.Create(connection))
        await using (var create = admin.CreateCommand("CREATE SCHEMA " + schema))
            await create.ExecuteNonQueryAsync(ct);
        var config = new NpgsqlConnectionStringBuilder(connection) { SearchPath = schema };
        await using var data = NpgsqlDataSource.Create(config.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data, PostgresMigrationRunner.MigrationDirectory, ct);
        var owner = Guid.NewGuid();
        await using (var seed = data.CreateCommand("""
            INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
            VALUES($1,'points_owner',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',0,repeat('0',64))
            """))
        {
            seed.Parameters.AddWithValue(owner);
            await seed.ExecuteNonQueryAsync(ct);
        }
        var store = new PostgresAccountStore(data);
        var created = await store.CreateCharacterAsync(owner, new(new string('1',64),new string('2',64),
            2,0,0,"Last","First","保存艦",5,[1,2,3,4,5,6,7,8]),ct);
        var initial = Assert.Single(await store.ListCharactersAsync(owner,ct));
        // Migration0036 applies the user-approved authored initial grant. This is
        // an explicit replacement policy, not a recovered original starting balance.
        var policy = OriginalCommandPointPolicy.LoadDefault();
        Assert.Equal(policy.InitialPolitical,initial.Pcp);
        Assert.Equal(policy.InitialMilitary,initial.Mcp);
        // Test-owned fixture stock, not a production credit API or original starting balance.
        await using (var seed = data.CreateCommand("UPDATE character SET pcp=321,mcp=4294967295 WHERE character_id=$1"))
        {
            seed.Parameters.AddWithValue(created.CharacterId);
            Assert.Equal(1,await seed.ExecuteNonQueryAsync(ct));
        }
        await PostgresMigrationRunner.ApplyAllAsync(data, PostgresMigrationRunner.MigrationDirectory, ct);
        for (var reconnect = 0; reconnect < 2; reconnect++)
        {
            var expectedPcp = reconnect == 0 ? 321u : 161u;
            var expectedMcp = reconnect == 0 ? uint.MaxValue : 23u;
            await using var reopened = NpgsqlDataSource.Create(config.ConnectionString);
            var restored = new PostgresAccountStore(reopened);
            var row = Assert.Single(await restored.ListCharactersAsync(owner,ct));
            Assert.Equal(initial.AbilityValues,row.AbilityValues);
            Assert.Equal(initial with { Pcp=expectedPcp,Mcp=expectedMcp },row with { AbilityValues=initial.AbilityValues });
            Assert.Empty(await restored.ListCharactersAsync(Guid.NewGuid(),ct));
            var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
            var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,store:restored);
            OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
            byte[] query = [3,0x22,0,0,0,0];
            BinaryPrimitives.WriteUInt32BigEndian(query.AsSpan(2),checked((uint)created.CharacterId));
            var response = await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(query,key,1),ct);
            Assert.Equal(NaturalAuthoritySessionStatus.Success,response.Status);
            var decoded = OriginalClientInnerFrameCodec.Decode(response.ResponsePayload!,key,0);
            Assert.Equal(OriginalClientInnerFrameStatus.Success,decoded.Status);
            Assert.Equal(expectedPcp,BinaryPrimitives.ReadUInt32BigEndian(decoded.Payload!.AsSpan(61)));
            Assert.Equal(expectedMcp,BinaryPrimitives.ReadUInt32BigEndian(decoded.Payload.AsSpan(65)));
            if (reconnect == 0)
            {
                await using var change = data.CreateCommand("UPDATE character SET pcp=161,mcp=23 WHERE character_id=$1");
                change.Parameters.AddWithValue(created.CharacterId);
                Assert.Equal(1,await change.ExecuteNonQueryAsync(ct));
                var refresh = await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(query,key,2),ct);
                Assert.Equal(NaturalAuthoritySessionStatus.Success,refresh.Status);
                var refreshed = OriginalClientInnerFrameCodec.Decode(refresh.ResponsePayload!,key,0);
                Assert.Equal(OriginalClientInnerFrameStatus.Success,refreshed.Status);
                Assert.Equal(161u,BinaryPrimitives.ReadUInt32BigEndian(refreshed.Payload!.AsSpan(61)));
                Assert.Equal(23u,BinaryPrimitives.ReadUInt32BigEndian(refreshed.Payload.AsSpan(65)));
            }
        }
    }
}
