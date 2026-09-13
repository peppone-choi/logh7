using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalFleetRepairProjectionTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Repair_projection_updates_all_rows_or_none_on_stale_last_row(bool stale)
    {
        await using var data=NpgsqlDataSource.Create("Host=127.0.0.1;Database=unused");
        var store=new PostgresFleetUnitStore(data);
        var registry=new OriginalTacticalBattleRegistry();
        var first=new OriginalFleetUnitRecord(2113929217,2113929312,56,3,0,101,300,40,10,0,0,0,0,10);
        var last=first with { UnitId=2113929218 };
        using var lease=await registry.LockAsync(101,100,TestContext.Current.CancellationToken);
        foreach(var row in new[]{first,last})
        {
            var fleet=new OriginalBattlefieldFleet(row.OutfitId,3,0,0,0,[new(row.UnitId,56,new(0,0,0,0))]);
            var initial=Assert.Single(fleet.Project(101));
            registry.RegisterNpc(initial,OriginalSubordinateShipCatalog.Capabilities,OriginalAuthoredPlayableCatalog.TacticalArms);
            registry.BindFleetUnitPersistence(row,store);
        }
        var after=new[]{first,last}.Select(row=>row with { Damaged=10,Supplies=0,Revision=2 }).ToArray();
        if(stale)
        {
            Assert.Throws<InvalidOperationException>(()=>registry.PrepareFleetRepairs(
                [first,last with { Revision=9 }],after));
            Assert.Equal(100u,registry.NpcSnapshot(101,first.UnitId)!.Unit.Supplies);
        }
        else
        {
            var commit=registry.PrepareFleetRepairs([first,last],after);
            Assert.Equal(100u,registry.NpcSnapshot(101,first.UnitId)!.Unit.Supplies);
            Assert.Equal(100u,registry.NpcSnapshot(101,last.UnitId)!.Unit.Supplies);
            commit();
            foreach(var row in after)
            {
                var projected=registry.NpcSnapshot(101,row.UnitId)!.Unit;
                Assert.Equal((ushort)10,projected.Damaged);
                Assert.Equal((ushort)10,projected.Destroyed);
                Assert.Equal(0u,projected.Supplies);
            }
            Assert.Throws<InvalidOperationException>(()=>registry.ApplyCommittedFleetRepairs([first,last],after));
        }
    }
}
