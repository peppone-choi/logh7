package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** U-32a 우주해적: 변경 그리드 출현·주기·상한, 격침과 토벌 치안 보상. 작성: Peppone Choi. */
class PirateActionsTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2490,4,"Frontier",0)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),GalaxyBase(1050,2490,80,0,"Wild",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,1,1,1),1050 to PlanetStock(1050,0,0,1,1,1,PlanetEconomy(1000,500,50,40))))

    @Test fun additionalCountriesAreNotMistakenForPirateFrontierOrMovementDestinations() {
        val s=stocks.copy(bases=stocks.bases+(1050 to stocks.bases.getValue(1050).copy(owner=Allegiance.Country(CountryId(42)))))
        assertTrue(PirateActions.frontier(galaxy,s).isEmpty())
        val ready=PirateActions.settle(galaxy,s,100)
        assertTrue(PirateActions.settle(galaxy,ready,100+7*86400).social.pirates.fleets.isEmpty())
        val roaming=s.copy(social=s.social.copy(pirates=PirateState(mapOf(1 to
            PirateFleet(1,2489,0,listOf(PirateUnit(900_001,300)))),2)))
        val moved=PirateActions.move(galaxy,roaming,14*86400L,emptySet()).social.pirates.fleets.values.single()
        assertTrue(moved.grid !in setOf(2487,2490))
    }

    @Test fun fleetsAppearOnFrontierGridsEverySevenDaysUpToOnePerGrid() {
        assertEquals(listOf(2490),PirateActions.frontier(galaxy,stocks))
        val armed=PirateActions.settle(galaxy,stocks,100)
        assertTrue(armed.social.pirates.fleets.isEmpty());assertEquals(100+7*86400,armed.social.pirates.nextSpawnAt)
        val spawned=PirateActions.settle(galaxy,armed,100+7*86400)
        val fleet=spawned.social.pirates.fleets.values.single()
        assertEquals(2490,fleet.grid);assertTrue(fleet.units.size in 1..3 && fleet.units.all {it.boats==300 && it.id>=900_000})
        assertEquals(1,PirateActions.settle(galaxy,spawned,100+14*86400).social.pirates.fleets.size,"one fleet per frontier grid")
    }
    @Test fun sinkingEveryUnitSuppressesTheFleetAndRestoresOrder() {
        val spawned=PirateActions.settle(galaxy,PirateActions.settle(galaxy,stocks,100),100+7*86400)
        var s=spawned
        val units=spawned.social.pirates.fleets.values.single().units
        for(u in units) {
            val (next,lost)=PirateActions.damage(s,u.id,1000);assertEquals(300,lost);s=PirateActions.reward(next,10000)
        }
        assertEquals(PirateActions.MERIT*units.size,s.strategic.people.getValue(10000).merit)
        val cleared=PirateActions.settle(galaxy,s,100+8*86400)
        assertTrue(cleared.social.pirates.fleets.isEmpty());assertEquals(45,cleared.bases.getValue(1050).economy?.peace)
    }
    @Test fun aLivingFleetWearsDownOrderAndHalvesRoutesThroughItsGrid() {
        val spawned=PirateActions.settle(galaxy,PirateActions.settle(galaxy,stocks,100),100+7*86400)
        val later=PirateActions.settle(galaxy,spawned,100+10*86400)
        assertEquals(37,later.bases.getValue(1050).economy?.peace,"three days of harm")
        val route=TradeRoute(1,1049,1050,10_000_000,0)
        val withAdults=later.copy(bases=later.bases+(1049 to later.bases.getValue(1049).copy(economy=PlanetEconomy(10000,8000,50,50)))+
            (1050 to later.bases.getValue(1050).copy(economy=PlanetEconomy(10000,4000,50,37))))
        assertEquals(2L,TradeActions.routeProfit(withAdults,route,galaxy),"halved")
        assertEquals(4L,TradeActions.routeProfit(withAdults,route))
    }
    @Test fun aFleetOutOfBattleDriftsToANearbyFrontierGridEverySevenDays() {
        val g=Galaxy(galaxy.systems+GalaxySystem(5,2492,4,"Next",0),galaxy.bases,galaxy.starts)
        val spawned=PirateActions.settle(g,PirateActions.settle(g,stocks,100),100+7*86400)
        val from=spawned.social.pirates.fleets.values.single().grid
        assertSame(spawned,PirateActions.move(g,spawned,100+8*86400,emptySet()),"not yet")
        assertSame(spawned,PirateActions.move(g,spawned,100+14*86400,setOf(from)),"in battle")
        val moved=PirateActions.move(g,spawned,100+14*86400,emptySet()).social.pirates.fleets.values.single()
        assertNotEquals(from,moved.grid);assertEquals(100+14*86400L,moved.since)
    }
    @Test fun aFleetNextToARouteEndAlsoHalvesItsProfit() {
        val route=TradeRoute(1,1049,1050,10_000_000,0)
        val rich=stocks.copy(bases=stocks.bases+(1049 to stocks.bases.getValue(1049).copy(economy=PlanetEconomy(10000,8000,50,50)))+
            (1050 to stocks.bases.getValue(1050).copy(economy=PlanetEconomy(10000,4000,50,50))))
        fun at(grid:Int)=rich.copy(social=rich.social.copy(pirates=PirateState(mapOf(1 to PirateFleet(1,grid,0,listOf(PirateUnit(900_001,300)))),2)))
        assertEquals(2L,TradeActions.routeProfit(at(2488),route,galaxy),"one cell from the route end at 2487")
        assertEquals(4L,TradeActions.routeProfit(at(2687),route,galaxy),"two cells away is out of reach")
    }
    @Test fun piratesStayAndCounterattackWhenOnlyRegularShipsAreDeployed() {
        val session=SessionSimulation(SessionRules.load()).exportState()
        val now=7*Justice.DAY
        val fleet=PirateFleet(1,2490,0,listOf(PirateUnit(900_001,300)))
        val point=TacticalPoint(0f,-9f)
        val motion=TacticalMotion(2490,now,TacticalPose(point,0f),0f,0f,listOf(point))
        val stack=UnitStack(32)
        val ship=ActiveShipUnit(1,stack,tactical=NpcTacticalState(motion,now,now))
        val outfit=OutfitStock(1,2,OutfitKind.FLEET,1049,10,units=UnitInventory(mapOf(stack to 1)),
            activeShips=ActiveShipUnits(listOf(ship)),position=OutfitPosition(2490))
        val start=stocks.copy(outfits=mapOf(1 to outfit),nextShipId=2,
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1049,1)),
            social=stocks.social.copy(pirates=PirateState(mapOf(1 to fleet),2)))
        assertTrue(PirateActions.engaged(galaxy,start,emptyList(),2490))
        assertSame(start,PirateActions.move(galaxy,start,now,setOf(2490)))
        val impacts=mutableListOf<TacticalImpact>()
        PirateActions.counter(session,galaxy,mutableMapOf(),start,now,impacts)
        assertEquals(900_001,impacts.single().source)
        val docked=start.copy(outfits=mapOf(1 to outfit.copy(position=OutfitPosition(2490,1050))))
        assertFalse(PirateActions.engaged(galaxy,docked,emptyList(),2490))
    }
    @Test fun counterattackCannotFireThroughAStar() {
        val simulation=SessionSimulation(SessionRules.load())
        val session=simulation.exportState()
        val now=session.gameMillis/1000+PirateActions.FIRE_SECONDS
        val battlefield=Galaxy(galaxy.systems.map {if(it.grid==2490)it.copy(star=0) else it},galaxy.bases,galaxy.starts)
        val fleet=PirateFleet(1,2490,now,listOf(PirateUnit(900_001,300)))
        val start=stocks.copy(social=stocks.social.copy(pirates=PirateState(mapOf(1 to fleet),2)))
        fun fireAt(point:TacticalPoint):List<TacticalImpact> {
            val motion=TacticalMotion(2490,now,TacticalPose(point,0f),0f,0f,listOf(point))
            val pilot=Navigator(1,10000,Power.EMPIRE,2490,1000,1000,1000,
                cpPaused=true,hull=FlagshipHull(0,now),motion=motion)
            val impacts=mutableListOf<TacticalImpact>()
            PirateActions.counter(session,battlefield,mutableMapOf("pilot" to pilot),start,now,impacts)
            return impacts
        }
        assertTrue(fireAt(TacticalPoint(5f,-8f)).isEmpty())
        assertEquals(1,fireAt(TacticalPoint(5f,-12f)).size)
    }
    @Test fun theSharedFleetPoseResolverPlacesAPirateTarget() {
        val s=stocks.copy(social=stocks.social.copy(pirates=PirateState(mapOf(1 to PirateFleet(1,2490,0,listOf(PirateUnit(900_001,300),PirateUnit(900_002,300)))),2)))
        val target=TacticalCombat.Target(pirate=900_002,spec=PirateActions.spec())
        assertEquals(PirateActions.pose(s,900_002),TacticalFleetAttack.pose(target,0,emptyMap(),s,galaxy))
    }
}
