package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class NpcStrategyTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2488,4,"Front",3)),
        listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val simulation=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.ALLIANCE,false,"current")}.exportState()
    private val stack=UnitStack(32)
    private fun stocks()=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100000,100000,100000)),
        outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,units=UnitInventory(mapOf(stack to 1),mapOf(UnitStack(0) to 1)),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack))),position=OutfitPosition(2487,1000),activeGround=ActiveGroundUnits(listOf(ActiveGroundUnit(2,UnitStack(0),base=1000))))),
        warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),deploymentInitialized=true,nextShipId=3)
    private fun players()=mutableMapOf("pilot" to Navigator(1,10000,Power.ALLIANCE,2488,1000,1000,1000,hull=FlagshipHull(89,0)))
    private fun advance(at:Long,stocks:PlanetStocks,players:MutableMap<String,Navigator>,events:MutableList<NpcStrategicMovement>)=
        NpcStrategy.advance(simulation.copy(gameMillis=at*1000),galaxy,players,stocks,events)

    @Test fun reservedAdjacentJumpRestoresThenConsumesRealDepotSuppliesOnceAndStartsContact() {
        val before=stocks();val players=players();val events=mutableListOf<NpcStrategicMovement>()
        val reserved=advance(0,before,players,events)
        assertEquals(NpcStrategicState(21600,2488,3600),reserved.outfits.getValue(1).strategy)
        assertEquals(before.bases,reserved.bases);assertTrue(events.isEmpty());assertFalse(players.getValue("pilot").cpPaused)
        assertEquals(reserved,advance(3599,reserved,players,events))
        players["elsewhereEmpire"]=Navigator(1,12000,Power.EMPIRE,2388,1000,1000,1000)
        players["elsewhereAlliance"]=Navigator(1,12001,Power.ALLIANCE,2388,1000,1000,1000)
        val arrived=advance(3600,reserved.copy(),players,events)
        val fleet=arrived.outfits.getValue(1)
        assertEquals(1000,fleet.activeGround!!.values.single().base)
        assertEquals(OutfitPosition(2488),fleet.position);assertEquals(97000,arrived.bases.getValue(1000).supplies)
        assertEquals(2700,fleet.supplies);assertEquals(mapOf(1 to 2700),OutfitSupplies.distribution(fleet))
        assertEquals(NpcStrategicState(25200),fleet.strategy)
        assertFalse(players.getValue("elsewhereEmpire").cpPaused)
        assertFalse(players.getValue("elsewhereAlliance").cpPaused)
        assertTrue(players.getValue("pilot").cpPaused);assertEquals(3600,players.getValue("pilot").cpRecoveryAt)
        assertEquals(listOf(1),events.single().units);assertEquals(mapOf(10000 to "current"),events.single().recipients)
        assertEquals(arrived,advance(3600,arrived,players,events));assertEquals(1,events.size)
        val waiting=advance(25200,arrived,players,events)
        assertEquals(fleet.position,waiting.outfits.getValue(1).position);assertEquals(fleet.supplies,waiting.outfits.getValue(1).supplies)
        assertNull(waiting.outfits.getValue(1).strategy!!.destination);assertEquals(1,events.size)
    }
    @Test fun appointedCommanderOrCapturedDepotCancelsWithoutChargingOrMoving() {
        for(appointed in listOf(true,false)) {
            val players=players();val events=mutableListOf<NpcStrategicMovement>()
            var reserved=advance(0,stocks(),players,events)
            reserved=if(appointed)reserved.copy(outfits=reserved.outfits.mapValues {(_,o)->o.copy(commanderId=10001)})
                else reserved.copy(bases=reserved.bases.mapValues {(_,b)->b.copy(power=3)})
            val result=advance(3600,reserved,players,events)
            assertNull(result.outfits.getValue(1).strategy)
            assertEquals(reserved.bases,result.bases);assertEquals(OutfitPosition(2487,1000),result.outfits.getValue(1).position)
            assertTrue(events.isEmpty())
        }
    }
    @Test fun capacityIsRecheckedAtArrivalAndCannotOverfillAnotherEmpireForce() {
        val players=players();val events=mutableListOf<NpcStrategicMovement>()
        val reserved=advance(0,stocks(),players,events)
        repeat(300) {i->players["friendly$i"]=Navigator(1,11000+i,Power.EMPIRE,2488,1000,1000,1000)}
        val result=advance(3600,reserved,players,events)
        assertEquals(OutfitPosition(2487,1000),result.outfits.getValue(1).position)
        assertEquals(reserved.bases,result.bases);assertEquals(0,result.outfits.getValue(1).supplies)
        assertNull(result.outfits.getValue(1).strategy!!.destination);assertTrue(events.isEmpty())
    }
    @Test fun simultaneousArrivalsRebuildForcesBeforeTheSecondFleetChecksCapacity() {
        val initial=stocks()
        val second=initial.outfits.getValue(1).copy(id=2,warehouseId=11,
            units=UnitInventory(mapOf(stack to 1)),activeGround=null,
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(3,stack))))
        val before=initial.copy(outfits=initial.outfits+(2 to second),
            warehouses=initial.warehouses+(11 to OutfitWarehouse(11,2,OutfitKind.FLEET,1000,2)),nextShipId=4)
        val players=players();val events=mutableListOf<NpcStrategicMovement>()
        val reserved=advance(0,before,players,events)
        assertEquals(2488,reserved.outfits.getValue(2).strategy!!.destination)
        repeat(299) {i->players["friendly$i"]=Navigator(1,11000+i,Power.EMPIRE,2488,1000,1000,1000)}
        val result=advance(3600,reserved,players,events)
        assertEquals(OutfitPosition(2488),result.outfits.getValue(1).position)
        assertEquals(OutfitPosition(2487,1000),result.outfits.getValue(2).position)
        assertEquals(0,result.outfits.getValue(2).supplies)
        assertEquals(97000,result.bases.getValue(1000).supplies)
        assertEquals(listOf(1),events.map {it.outfit})
    }
    @Test fun noFuelNoTargetOrNoncombatOutfitDoesNotCreateTravelOrResources() {
        val players=players();val events=mutableListOf<NpcStrategicMovement>()
        var reserved=advance(0,stocks(),players,events)
        reserved=reserved.copy(bases=reserved.bases.mapValues {(_,b)->b.copy(supplies=0)})
        val failed=advance(3600,reserved,players,events)
        assertEquals(OutfitPosition(2487,1000),failed.outfits.getValue(1).position)
        assertEquals(reserved.bases,failed.bases);assertEquals(0,failed.outfits.getValue(1).supplies);assertTrue(events.isEmpty())
        val idle=advance(0,stocks(),mutableMapOf(),events)
        assertNull(idle.outfits.getValue(1).strategy!!.destination)
        for(kind in listOf(OutfitKind.TRANSPORT,OutfitKind.GROUND)) {
            val before=stocks();val outfit=before.outfits.getValue(1)
            val warehouse=before.warehouses.getValue(10)
            val modified=before.copy(outfits=mapOf(1 to outfit.copy(kind=kind)),
                warehouses=mapOf(10 to warehouse.copy(kind=kind.warehouseKind,outfitId=if(kind==OutfitKind.GROUND)0 else 1)))
            assertEquals(modified,advance(0,modified,players,events))
        }
    }
    @Test fun failedWorldCommitPublishesNeitherTravelFuelLossNorContactThenRetryCommitsOnce() {
        var saved=stocks();var navigators=players().toMap();var fail=false
        val stockStore=object:PlanetStockStore {override fun load()=saved;override fun save(stocks:PlanetStocks){saved=stocks}}
        val navStore=object:NavigationStore {override fun load()=navigators;override fun save(players:Map<String,Navigator>){navigators=players}}
        val production=PlanetProduction(galaxy,stockStore,0,PlanetProductionPolicy()).also {it.settle(1,0)}
        val navigation=Navigation(galaxy,navStore,NavigationPolicy())
        val commit=WorldStateCommitter {p,stocks->if(fail)throw java.io.IOException("disk");navigators=p.toMap();saved=checkNotNull(stocks)}
        val events=mutableListOf<NpcStrategicMovement>()
        fun settle(at:Long)=TacticalCombat.settle(simulation.copy(gameMillis=at*1000),galaxy,navigation,production,commit,
            NpcTacticalPolicy(strategic=true),onStrategicMovement=events::add)
        fail=true;assertFailsWith<java.io.IOException> {settle(0)}
        assertNull(production.snapshot(1)!!.outfits.getValue(1).strategy);assertTrue(events.isEmpty())
        fail=false;settle(0);val reserved=production.snapshot(1)!!
        fail=true;assertFailsWith<java.io.IOException> {settle(3600)}
        assertEquals(reserved,production.snapshot(1));assertFalse(navigation.snapshot(1).getValue("pilot").cpPaused)
        assertTrue(events.isEmpty())
        fail=false;settle(3600);assertEquals(1,events.size)
        assertEquals(OutfitPosition(2488),saved.outfits.getValue(1).position)
        assertTrue(navigation.snapshot(1).getValue("pilot").cpPaused)
        val fuel=saved.outfits.getValue(1).supplies;settle(3600)
        assertEquals(fuel,saved.outfits.getValue(1).supplies);assertEquals(1,events.size)
    }

    @Test fun invalidStrategicStateDoesNotLoadAHalfReservation() {
        assertFailsWith<IllegalArgumentException> {NpcStrategicState(10,2488,null)}
        assertFailsWith<IllegalArgumentException> {NpcStrategicState(10,null,9)}
        assertFailsWith<IllegalArgumentException> {NpcStrategicState(10,5000,9)}
        assertFailsWith<IllegalArgumentException> {NpcStrategicState(10,2488,11)}
    }
}
