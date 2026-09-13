using System.Threading.Channels;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalFleetMotionPersistenceTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;
    [Theory(Skip="Requires isolated PostgreSQL",SkipUnless=nameof(HasTestDatabase))]
    [InlineData(false, 20f)]
    [InlineData(true, 20f)]
    [InlineData(false, 0f)]
    [InlineData(true, 0f)]
    public async Task Movement_is_saved_before_pose_and_notification_commit(bool conflict, float targetDistance)
    {
        var connection=Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema="fleet_motion_"+Guid.NewGuid().ToString("N");
        await using(var admin=NpgsqlDataSource.Create(connection))
        await using(var create=admin.CreateCommand("CREATE SCHEMA "+schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var config=new NpgsqlConnectionStringBuilder(connection){SearchPath=schema};
        await using var data=NpgsqlDataSource.Create(config.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data,PostgresMigrationRunner.MigrationDirectory,
            TestContext.Current.CancellationToken);
        var store=new PostgresFleetUnitStore(data);
        var row=new OriginalFleetUnitRecord(2113929217,2113929312,56,3,0,101,300,0,0,0,0,0,MathF.PI/2,10);
        await store.EnsureCreatedAsync(row,TestContext.Current.CancellationToken);
        var fleet=new OriginalBattlefieldFleet(row.OutfitId,3,0,0,0,
            [new(row.UnitId,56,new(0,0,0,MathF.PI/2))]);
        var initial=Assert.Single(fleet.Project(101));
        var registry=new OriginalTacticalBattleRegistry();
        var queue=Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var observer=registry.Subscribe(101,100,queue.Writer,2,primaryNpcKnown:false);
        using(await registry.LockAsync(101,100,TestContext.Current.CancellationToken))
        {
            registry.RegisterNpc(initial,OriginalSubordinateShipCatalog.Capabilities,OriginalAuthoredPlayableCatalog.TacticalArms);
            registry.BindFleetUnitPersistence(row,store);
            registry.UpdateParticipant(queue.Writer,OriginalNpcAiTests.Actor(2,2,targetDistance));
        }
        await registry.AdvanceNpcsAsync(100,TestContext.Current.CancellationToken);
        while(queue.Reader.TryRead(out _)) { }
        if(conflict)
        {
            Assert.True(await store.SaveAsync(row with { Cruising=9 },TestContext.Current.CancellationToken));
            var error=await Assert.ThrowsAsync<InvalidOperationException>(()=>registry.AdvanceNpcsAsync(106,
                TestContext.Current.CancellationToken));
            Assert.Equal("FLEET_UNIT_SAVE_CONFLICT",error.Message);
            Assert.Equal(initial.Ship,registry.NpcSnapshot(101,row.UnitId)!.Ship);
            Assert.False(queue.Reader.TryRead(out _));
        }
        else
        {
            var events=await registry.AdvanceNpcsAsync(106,TestContext.Current.CancellationToken);
            Assert.Contains(events,e=>e.Actor==row.UnitId && e.Action=="move");
            Assert.DoesNotContain(events,e=>e.Action=="fire");
            var moved=registry.NpcSnapshot(101,row.UnitId)!.Ship;
            if (targetDistance > 0) Assert.True(moved.X>0);
            else Assert.True(float.IsFinite(moved.X) && float.IsFinite(moved.Y) &&
                moved.X*moved.X+moved.Y*moved.Y > 0, "Overlapping NPC must actually separate");
            await using var reopened=NpgsqlDataSource.Create(config.ConnectionString);
            var saved=Assert.Single(await new PostgresFleetUnitStore(reopened).ReadGridAsync(101,
                TestContext.Current.CancellationToken));
            Assert.Equal(moved.X,saved.X);
            Assert.Equal(moved.Y,saved.Y);
            Assert.Equal(moved.Direction,saved.Direction);
            Assert.Equal(2,saved.Revision);
            Assert.Equal(0,saved.Damaged);
        }
    }
}
