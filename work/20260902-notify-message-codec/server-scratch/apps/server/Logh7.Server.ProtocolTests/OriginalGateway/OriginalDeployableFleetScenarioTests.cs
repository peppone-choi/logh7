using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalDeployableFleetScenarioTests
{
    [Fact]
    public void Configured_catalog_selects_the_packaged_scenario_and_never_hides_bad_paths()
    {
        var scenario=Path.Combine(AppContext.BaseDirectory,"battlefields","fleet-skirmish.json");
        var fleet=OriginalBattlefieldCatalog.LoadConfigured(scenario).Resolve(101).Fleets;
        Assert.NotNull(fleet);
        // 5 combat/allied fleets plus the 工作艦 and 補給艦 that 修理 and 補給 name.
        Assert.Equal(7,fleet.Count);
        Assert.Empty(OriginalBattlefieldCatalog.LoadConfigured(null).Resolve(101).Fleets ?? []);
        Assert.Throws<ArgumentException>(()=>OriginalBattlefieldCatalog.LoadConfigured("fleet-skirmish.json"));
        Assert.Throws<FileNotFoundException>(()=>OriginalBattlefieldCatalog.LoadConfigured(
            Path.Combine(AppContext.BaseDirectory,"missing-fleet-scenario.json")));
    }

    [Fact]
    public async Task Shipped_fleet_scenario_imports_seven_units_and_runs_opposing_fleets_without_populating_rear_base()
    {
        var catalog=OriginalBattlefieldCatalog.Load(Path.Combine(AppContext.BaseDirectory,
            "battlefields","fleet-skirmish.json"));
        // Four opposing/allied fleets as shipped, one allied picket added so a
        // tactical order has a living friendly fleet to address, and the 工作艦 and
        // 補給艦 that 修理 and 補給 name in their own descriptions.
        Assert.Equal(7,catalog.Resolve(101).Fleets!.Count);
        // The battle grid holds one authored single-hull defensive picket that a
        // player can actually finish. The rear dock grid stays empty: a hostile
        // there leaves a docked player in the tactical view with no strategy UI.
        var picket=Assert.Single(catalog.Resolve(101).Fleets!,f=>f.Id==2113929571);
        Assert.Equal((ushort)1,Assert.Single(picket.Ships).Complement);
        Assert.True(picket.Defensive);
        // The rear grid carries a second unarmed single-hull picket so a battle
        // there can be finished. It is only ever reached by warping in undocked:
        // logging in docked with a hostile present locks the strategy UI away.
        var rearFleets=catalog.Resolve(102).Fleets!;
        Assert.Equal(4,rearFleets.Count);
        var rear=Assert.Single(rearFleets,f=>f.Power==3);
        Assert.Equal((ushort)1,Assert.Single(rear.Ships).Complement);
        Assert.True(rear.Defensive);
        // ...and one allied picket of the player's own power, so 具申 (0x0421)
        // has a friendly fleet to address without a second human player. Only a
        // hostile makes a field unquiet, so an ally does not lock the strategy UI.
        var ally=Assert.Single(rearFleets,f=>f.Power==2 && f.Role is null);
        Assert.True(ally.Defensive);
        Assert.Equal(2113929481u,Assert.Single(ally.Ships).Id);
        Assert.False(catalog.Resolve(102).SpawnEnemy);
        var battles=new OriginalTacticalBattleRegistry();
        var player=OriginalPlayerCombatTests.Session(battles,2,2,catalog);
        var scene=await player.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"),new byte[16],1),TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,scene.Status);
        var fleetUnits=battles.OtherParticipants(101,2).OrderBy(p=>p.Unit.Id).ToArray();
        // ...2113929483 and 2113929484 are the 工作艦 and 補給艦 修理 and 補給 name.
        Assert.Equal(new uint[]{2113929473,2113929474,2113929475,2113929476,2113929477,2113929478,2113929479,
            2113929482,2113929483,2113929484},
            fleetUnits.Select(p=>p.Unit.Id));
        // 2113929479 is the single-hull picket; 483/484 are the single-hull
        // 工作艦 and 補給艦 (kinds 57/58), so the 300-hull rule covers the rest.
        Assert.All(fleetUnits.Where(p=>p.Unit.Id is not (2113929479 or 2113929483 or 2113929484)),
            p=>Assert.Equal((ushort)300,battles.GetEncounter(101,100).UnitNumber(p.Unit.Id)));
        // The added ally is the player's own power, so 具申 can address it.
        Assert.False(Assert.Single(fleetUnits,p=>p.Unit.Id==2113929482).IsHostileTo(2,0));
        Assert.Equal((ushort)1,battles.GetEncounter(101,100).UnitNumber(2113929479));
        var events=new List<OriginalNpcEvent>();
        for(uint tick=100;tick<300;tick+=6)
            events.AddRange(await battles.AdvanceNpcsAsync(tick,TestContext.Current.CancellationToken));
        // Both camps engage. Targets are chosen by the AI, so the added hostile
        // outfit may draw fire that previously went to the older opposing fleet.
        Assert.Contains(events,e=>e.Action=="fire" && e.Actor is 2113929473 or 2113929474 &&
            e.Target is 2113929475 or 2113929476 or 2113929477 or 2113929478);
        // Since the support vessels were brought alongside the flagship (v399) they
        // are the nearest player-side hulls to the hostile camp, so the AI picks
        // them; the assertion is that the hostile camp engages, not which hull.
        Assert.Contains(events,e=>e.Action=="fire" &&
            e.Actor is 2113929475 or 2113929476 or 2113929477 or 2113929478 &&
            e.Target is 2113929473 or 2113929474 or 2113929482 or 2113929483 or 2113929484);
        // The newly authored hostile outfit is a real combatant, not scenery.
        Assert.Contains(events,e=>e.Actor is 2113929477 or 2113929478);
    }
}
