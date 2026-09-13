using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalFleetSceneRestoreTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;
    internal sealed class Store(NpgsqlDataSource data, IAccountStore roster) : OriginalWarpSessionClockTests.UnusedStore, IOriginalFleetUnitStoreProvider
    {
        public PostgresFleetUnitStore FleetUnits => new(data);
        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid account, CancellationToken ct) =>
            roster.ListCharactersAsync(account,ct);
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Native_scene_request_reads_saved_ordinary_units_before_bootstrap_and_refresh()
    {
        var connection = Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema = "fleet_scene_" + Guid.NewGuid().ToString("N");
        await using (var admin = NpgsqlDataSource.Create(connection))
        await using (var create = admin.CreateCommand("CREATE SCHEMA " + schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var config = new NpgsqlConnectionStringBuilder(connection) { SearchPath = schema };
        await using var data = NpgsqlDataSource.Create(config.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data, PostgresMigrationRunner.MigrationDirectory,
            TestContext.Current.CancellationToken);
        var stored = new OriginalFleetUnitRecord(2113929217,2113929312,56,2,0,101,300,35,10,12,13,0,1.25f,7, Supplies: 23);
        await new PostgresFleetUnitStore(data).EnsureCreatedAsync(stored, TestContext.Current.CancellationToken);
        var catalog = OriginalBattlefieldCatalog.Parse(OriginalFleetContentTests.Document().ToJsonString());
        var key = new byte[16];
        var battles = new OriginalTacticalBattleRegistry();
        var session = OriginalPlayerCombatTests.Session(battles,2,2,catalog);
        var roster=(IAccountStore)typeof(NaturalAuthoritySession).GetField("_store",
            System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic)!.GetValue(session)!;
        OriginalWarpSessionClockTests.SetField(session,"_store",new Store(data,roster));
        for (uint sequence = 1; sequence <= 2; sequence++)
        {
            var result = await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(
                Convert.FromHexString("0F02"),key,sequence),TestContext.Current.CancellationToken);
            Assert.Equal(NaturalAuthoritySessionStatus.Success,result.Status);
            Assert.Equal(new OriginalTacticalDamageState(sequence == 1 ? (ushort)35 : (ushort)50,10),
                battles.GetEncounter(101,100).GetUnitDamage(stored.UnitId));
            var participant = battles.OtherParticipants(101,2).Single(p=>p.Unit.Id==stored.UnitId);
            Assert.Equal(12f,participant.Ship.X);
            Assert.Equal(7f,participant.Unit.Cruising);
            Assert.Equal(23u,participant.Unit.Supplies);
            var frames = new[] {result.ResponsePayload!}.Concat(result.AdditionalResponses!.Select(x=>x.Payload))
                .Select(x=>OriginalClientInnerFrameCodec.Decode(x,key,0).Payload!).ToArray();
            var shipFrame=frames.Single(x=>BinaryPrimitives.ReadUInt16BigEndian(x.AsSpan(4))==0x33b);
            Assert.True(OriginalSystemSceneCodec.TryDecodeTacticalUnitShips(shipFrame.AsSpan(4),out var ships));
            Assert.Equal(12f,Assert.Single(ships.Records,x=>x.Id==stored.UnitId).X);
            if (sequence == 1)
                using (await battles.LockAsync(101,100,TestContext.Current.CancellationToken))
                    battles.GetEncounter(101,100).RecordUnitDamage(stored.UnitId,new(50,10));
        }
        Assert.Equal(stored,Assert.Single(await new PostgresFleetUnitStore(data).ReadGridAsync(101,
            TestContext.Current.CancellationToken),x=>x.UnitId==stored.UnitId));
        using (await battles.LockAsync(101,100,TestContext.Current.CancellationToken))
            await battles.CommitUnitDamageAsync(101,100,stored.UnitId,new(60,20),TestContext.Current.CancellationToken);
        await using var reopened=NpgsqlDataSource.Create(config.ConnectionString);
        var persisted=Assert.Single(await new PostgresFleetUnitStore(reopened).ReadGridAsync(101,
            TestContext.Current.CancellationToken),x=>x.UnitId==stored.UnitId);
        Assert.Equal(stored with { Damaged=60,Destroyed=20,Revision=2 },persisted);
        var freshBattles=new OriginalTacticalBattleRegistry();
        var fresh=OriginalPlayerCombatTests.Session(freshBattles,2,2,catalog);
        OriginalWarpSessionClockTests.SetField(fresh,"_store",new Store(reopened,roster));
        await fresh.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"),key,1),
            TestContext.Current.CancellationToken);
        Assert.Equal(new OriginalTacticalDamageState(60,20),freshBattles.GetEncounter(101,100).GetUnitDamage(stored.UnitId));
        Assert.True(await new PostgresFleetUnitStore(reopened).SaveAsync(persisted with { Cruising=3 },
            TestContext.Current.CancellationToken));
        using (await battles.LockAsync(101,100,TestContext.Current.CancellationToken))
        {
            var error=await Assert.ThrowsAsync<InvalidOperationException>(()=>battles.CommitUnitDamageAsync(
                101,100,stored.UnitId,new(80,30),TestContext.Current.CancellationToken));
            Assert.Equal("FLEET_UNIT_SAVE_CONFLICT",error.Message);
        }
        Assert.Equal(new OriginalTacticalDamageState(60,20),battles.GetEncounter(101,100).GetUnitDamage(stored.UnitId));
    }
}
