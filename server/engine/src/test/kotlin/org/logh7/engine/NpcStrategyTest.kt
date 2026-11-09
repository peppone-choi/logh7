package org.logh7.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun restoredNpcJumpArrivesBetweenPeriodicTicksWithoutAClientRequest() = runTest {
        val rules=SessionRules.load()
        val before=stocks()
        var saved=before.copy(outfits=before.outfits.mapValues {(_,fleet)->
            fleet.copy(strategy=NpcStrategicState(21600,2488,15))
        })
        val engine=WorldEngine(this,rules,monotonicMillis={testScheduler.currentTime},galaxy=galaxy,
            navigationStore=object:NavigationStore {
                override fun load()=players()
                override fun save(next:Map<String,Navigator>) {}
            },
            stockStore=object:PlanetStockStore {
                override fun load()=saved
                override fun save(next:PlanetStocks){saved=next}
            },worldStateCommitter=WorldStateCommitter {_,inventory->saved=checkNotNull(inventory)},
            npcPolicy=NpcTacticalPolicy(strategic=true))
        try {
            advanceTimeBy(624);runCurrent()
            assertEquals(OutfitPosition(2487,1000),saved.outfits.getValue(1).position)
            advanceTimeBy(1);runCurrent()
            assertEquals(15L,engine.snapshot().session!!.gameSeconds)
            assertEquals(OutfitPosition(2488),saved.outfits.getValue(1).position)
            assertEquals(97000,saved.bases.getValue(1000).supplies)
        } finally {engine.drainAndClose()}
    }

    @Test fun adjacentDepotPaysOnceToRescueAZeroSupplyFleetOutsideCombat() {
        val initial=stocks()
        val fleet=initial.outfits.getValue(1).copy(position=OutfitPosition(2488))
        val before=initial.copy(outfits=mapOf(1 to fleet))
        val events=mutableListOf<NpcStrategicMovement>()
        val reserved=advance(0,before,mutableMapOf(),events)
        assertEquals(NpcStrategicState(21600,2487,3600),reserved.outfits.getValue(1).strategy)
        assertEquals(before.bases,reserved.bases)
        assertTrue(events.isEmpty())

        val blocked=advance(3600,reserved,players(),events)
        assertEquals(OutfitPosition(2488),blocked.outfits.getValue(1).position)
        assertEquals(before.bases,blocked.bases)
        assertTrue(events.isEmpty())

        val depot=before.bases.getValue(1000)
        for (unavailable in listOf(depot.copy(supplies=1),depot.copy(owner=Allegiance.Country(Power.ALLIANCE.country)))) {
            val changed=reserved.copy(bases=mapOf(1000 to unavailable))
            val cancelled=advance(3600,changed,mutableMapOf(),events)
            assertEquals(OutfitPosition(2488),cancelled.outfits.getValue(1).position)
            assertEquals(changed.bases,cancelled.bases)
            assertTrue(events.isEmpty())
        }

        val arrived=advance(3600,reserved,mutableMapOf(),events)
        val docked=arrived.outfits.getValue(1)
        assertEquals(OutfitPosition(2487,1000),docked.position)
        val jumpCost=fleet.activeShips!!.values.single().boats
        assertEquals(before.bases.getValue(1000).supplies-jumpCost-docked.supplies,arrived.bases.getValue(1000).supplies)
        assertTrue(docked.supplies>0)
        assertEquals(2487,events.single().destination)
        assertEquals(arrived,advance(3600,arrived,mutableMapOf(),events))
        assertEquals(1,events.size)
    }

    @Test fun distantDepotFundsTheSafeReturnRouteOnlyOnce() {
        val initial=stocks()
        val fleet=initial.outfits.getValue(1).copy(position=OutfitPosition(2489))
        val before=initial.copy(outfits=mapOf(1 to fleet))
        val events=mutableListOf<NpcStrategicMovement>()
        val pilots=mutableMapOf<String,Navigator>()
        val reserved=advance(0,before,pilots,events)
        val first=assertNotNull(reserved.outfits.getValue(1).strategy?.arrivesAt)
        assertEquals(before.bases,reserved.bases)
        val mid=advance(first,reserved,pilots,events)
        val jumpCost=fleet.activeShips!!.values.single().boats
        assertEquals(0,mid.outfits.getValue(1).position!!.baseId)
        assertEquals(jumpCost,mid.outfits.getValue(1).supplies)
        assertEquals(before.bases.getValue(1000).supplies-2*jumpCost,mid.bases.getValue(1000).supplies)

        val homeReserved=advance(first+21600,mid,pilots,events)
        val arrival=assertNotNull(homeReserved.outfits.getValue(1).strategy?.arrivesAt)
        val home=advance(arrival,homeReserved,pilots,events)
        assertEquals(OutfitPosition(2487,1000),home.outfits.getValue(1).position)
        assertEquals(mid.bases.getValue(1000).supplies-home.outfits.getValue(1).supplies,home.bases.getValue(1000).supplies)
        assertEquals(2,events.size)
    }

    @Test fun depotFundsOnlyTheMissingCargoOfAPartiallySuppliedFleet() {
        val initial=stocks()
        val fleet=OutfitSupplies.withBalances(initial.outfits.getValue(1).copy(position=OutfitPosition(2488)),mapOf(1 to 125))
        val depot=initial.bases.getValue(1000).copy(supplies=175)
        val before=initial.copy(bases=mapOf(1000 to depot),outfits=mapOf(1 to fleet))
        val events=mutableListOf<NpcStrategicMovement>()
        val pilots=mutableMapOf<String,Navigator>()
        val reserved=advance(0,before,pilots,events)
        assertEquals(NpcStrategicState(21600,2487,3600),reserved.outfits.getValue(1).strategy)
        assertEquals(before.bases,reserved.bases)

        val short=reserved.copy(bases=mapOf(1000 to depot.copy(supplies=174)))
        val blocked=advance(3600,short,pilots,events)
        assertEquals(fleet.position,blocked.outfits.getValue(1).position)
        assertEquals(125,blocked.outfits.getValue(1).supplies)
        assertEquals(short.bases,blocked.bases)
        assertTrue(events.isEmpty())

        val arrived=advance(3600,reserved,pilots,events)
        assertEquals(OutfitPosition(2487,1000),arrived.outfits.getValue(1).position)
        assertEquals(0,arrived.outfits.getValue(1).supplies)
        assertEquals(0,arrived.bases.getValue(1000).supplies)
        assertEquals(2487,events.single().destination)
        assertEquals(arrived,advance(3600,arrived,pilots,events))
        assertEquals(1,events.size)
    }

    @Test fun blockedFirstReliefJumpReservesAnAlternateRouteWithoutChargingTheDepot() {
        val initial=stocks()
        val fleet=initial.outfits.getValue(1).copy(position=OutfitPosition(2489))
        val before=initial.copy(outfits=mapOf(1 to fleet))
        val events=mutableListOf<NpcStrategicMovement>()
        val reserved=advance(0,before,mutableMapOf(),events)
        val first=checkNotNull(reserved.outfits.getValue(1).strategy)
        val pilots=players()
        pilots["pilot"]=pilots.getValue("pilot").copy(grid=checkNotNull(first.destination))

        val rerouted=advance(3600,reserved,pilots,events)
        val retry=checkNotNull(rerouted.outfits.getValue(1).strategy)
        val alternate=assertNotNull(retry.destination)
        assertNotEquals(first.destination,alternate)
        assertEquals(7200L,retry.arrivesAt)
        assertEquals(fleet.position,rerouted.outfits.getValue(1).position)
        assertEquals(before.bases,rerouted.bases)
        assertTrue(events.isEmpty())

        val moved=advance(7200,rerouted,pilots,events)
        assertEquals(alternate,moved.outfits.getValue(1).position?.grid)
        assertEquals(alternate,events.single().destination)
    }

    @Test fun eliminatedCountryCancelsAnUncommandedFleetJumpWithoutSpendingDepotSupplies() {
        val before=stocks()
        val pilots=players()
        val events=mutableListOf<NpcStrategicMovement>()
        val reserved=advance(0,before,pilots,events)
        val arrival=assertNotNull(reserved.outfits.getValue(1).strategy?.arrivesAt)
        val eliminated=simulation.copy(gameMillis=arrival*1000,
            eliminated=mapOf(Power.EMPIRE.country to CountryDefeat(EndReason.SYSTEMS_REDUCED,0)))

        val cancelled=NpcStrategy.advance(eliminated,galaxy,pilots,reserved,events)
        assertEquals(before.outfits,cancelled.outfits)
        assertEquals(before.bases,cancelled.bases)
        assertTrue(events.isEmpty())
        assertEquals(cancelled,NpcStrategy.advance(eliminated,galaxy,pilots,cancelled,events))
    }

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
    @Test fun outmatchedFleetHoldsAtItsDepotAndWithdrawsFromAStrongerEnemyGrid() {
        // One ship against three enemy flagships (more than twice): no advance from the depot.
        val players=players()
        for(i in 1..2)players["enemy$i"]=Navigator(1,10000+i,Power.ALLIANCE,2488,1000,1000,1000,hull=FlagshipHull(89,0))
        val events=mutableListOf<NpcStrategicMovement>()
        val held=advance(0,stocks(),players,events)
        assertEquals(NpcStrategicState(21600),held.outfits.getValue(1).strategy)
        // Caught out on the enemy grid, it reserves the jump back towards its own depot.
        val stranded=stocks().let {s->s.copy(outfits=s.outfits.mapValues {(_,o)->o.copy(position=OutfitPosition(2488))})}
        val out=stranded.copy(outfits=stranded.outfits.mapValues {(_,o)->OutfitSupplies.withBalances(o,mapOf(1 to 300))})
        assertEquals(NpcStrategicState(21600,2487,3600),advance(0,out,players,events).outfits.getValue(1).strategy)
        val waiting=advance(0,stranded,players,events)
        assertEquals(NpcStrategicState(21600),waiting.outfits.getValue(1).strategy)
        assertEquals(waiting.bases,stranded.bases)
        assertEquals(NpcStrategicState(43200),advance(21600,waiting,players,events).outfits.getValue(1).strategy)
        // An even fight on that grid is accepted while the fuel lasts: it stays to fight rather than withdrawing.
        val fuelled=out.copy(outfits=out.outfits.mapValues {(_,o)->OutfitSupplies.withBalances(o,mapOf(1 to 2700))})
        assertEquals(NpcStrategicState(21600),advance(0,fuelled,players(),events).outfits.getValue(1).strategy)
        // Fuel for fewer than two more jumps (the unit's 300 boats need 600) sends it home first.
        val short=out.copy(outfits=out.outfits.mapValues {(_,o)->OutfitSupplies.withBalances(o,mapOf(1 to 599))})
        assertEquals(NpcStrategicState(21600,2487,3600),advance(0,short,players(),events).outfits.getValue(1).strategy)
        assertTrue(events.isEmpty())
    }
    @Test fun lowFuelFleetReturnsToItsDepotReloadsAndCanLaunchAgain() {
        val players=players().also {it["pilot"]=it.getValue("pilot").copy(grid=2588)}
        val events=mutableListOf<NpcStrategicMovement>()
        val before=stocks().let {s->s.copy(outfits=s.outfits.mapValues {(_,o)->
            OutfitSupplies.withBalances(o.copy(position=OutfitPosition(2488)),mapOf(1 to 300))})}
        val reserved=advance(0,before,players,events)
        assertEquals(2487,reserved.outfits.getValue(1).strategy!!.destination)
        val home=advance(3600,reserved,players,events)
        assertEquals(OutfitPosition(2487,1000),home.outfits.getValue(1).position)
        assertEquals(3000,home.outfits.getValue(1).supplies)
        assertEquals(97000,home.bases.getValue(1000).supplies)
        assertEquals(home,advance(3600,home,players,events))
        val outbound=advance(25200,home,players,events)
        assertEquals(2588,outbound.outfits.getValue(1).strategy!!.destination)
        val arrived=advance(28800,outbound,players,events)
        assertEquals(OutfitPosition(2588),arrived.outfits.getValue(1).position)
        assertEquals(2700,arrived.outfits.getValue(1).supplies)
        assertEquals(97000,arrived.bases.getValue(1000).supplies)
        assertEquals(listOf(2487,2588),events.map {it.destination})
    }
    @Test fun advanceLeavesEnoughFuelForTheReturnRouteFromTheNextGrid() {
        val kind=ProductionUnits.definitions.first {it.ship && it.power==2 && it.name=="巡航艦Ⅴ"}.kind
        val unit=ActiveShipUnit(1,UnitStack(kind))
        val original=stocks()
        val source=original.outfits.getValue(1)
        val fleet=source.copy(position=OutfitPosition(2489),
            units=UnitInventory(mapOf(unit.stack to 1),source.units.troops),activeShips=ActiveShipUnits(listOf(unit)))
        fun stock(jumps:Int)=original.copy(outfits=mapOf(1 to OutfitSupplies.withBalances(fleet,mapOf(1 to unit.boats*jumps))))
        val observers=players().also {it["pilot"]=it.getValue("pilot").copy(grid=2491)}
        val events=mutableListOf<NpcStrategicMovement>()
        val affordable=advance(0,stock(4),observers,events)
        assertEquals(90,affordable.outfits.getValue(1).strategy!!.destination!!%100)
        val insufficient=advance(0,stock(3),observers,events)
        assertEquals(88,insufficient.outfits.getValue(1).strategy!!.destination!!%100)
        val depleted=affordable.copy(outfits=affordable.outfits.mapValues {(_,outfit)->
            OutfitSupplies.withBalances(outfit,mapOf(1 to unit.boats*3))})
        val cancelled=advance(3600,depleted,observers,events)
        assertEquals(fleet.position,cancelled.outfits.getValue(1).position)
        assertEquals(unit.boats*3,cancelled.outfits.getValue(1).supplies)
        assertTrue(events.isEmpty())
    }

    @Test fun changedThreatCancelsTheOldAdvanceBeforeItSpendsFuel() {
        val players=players().also {it["pilot"]=it.getValue("pilot").copy(grid=2688)}
        val events=mutableListOf<NpcStrategicMovement>()
        val before=stocks().let {s->s.copy(outfits=s.outfits.mapValues {(_,o)->
            OutfitSupplies.withBalances(o.copy(position=OutfitPosition(2488)),mapOf(1 to 2700))})}
        val reserved=advance(0,before,players,events)
        assertEquals(2587,reserved.outfits.getValue(1).strategy!!.destination)
        for(i in 1..2)players["enemy$i"]=players.getValue("pilot").copy(characterId=10000+i)
        val cancelled=advance(3600,reserved,players,events)
        assertEquals(OutfitPosition(2488),cancelled.outfits.getValue(1).position)
        assertEquals(2700,cancelled.outfits.getValue(1).supplies)
        assertEquals(before.bases,cancelled.bases);assertTrue(events.isEmpty())
        val returning=advance(25200,cancelled,players,events)
        assertEquals(2487,returning.outfits.getValue(1).strategy!!.destination)
        // A different beatable enemy beyond a newly stronger intermediate grid cannot legitimize the old jump.
        for(i in 1..3)players["enemy$i"]=players.getValue("pilot").copy(characterId=10000+i,grid=2587)
        val blockedRoute=advance(3600,reserved,players,events)
        assertEquals(OutfitPosition(2488),blockedRoute.outfits.getValue(1).position)
        assertEquals(2700,blockedRoute.outfits.getValue(1).supplies)
        assertTrue(events.isEmpty())
    }
    @Test fun fleetReservesFuelForEveryJumpAroundAStormAndDocks() {
        val blocked=Galaxy(galaxy.systems,galaxy.bases,galaxy.starts,terrain=List(50) {y->
            CharArray(100) {x->if(x==89 && y in 22..26)'~' else '.'}.concatToString()
        })
        val initial=stocks()
        val fleet=OutfitSupplies.withBalances(initial.outfits.getValue(1).copy(position=OutfitPosition(2490)),mapOf(1 to 1800))
        var world=initial.copy(outfits=mapOf(1 to fleet))
        val observers=mutableMapOf<String,Navigator>()
        val events=mutableListOf<NpcStrategicMovement>()
        val forces=GridForces.collect(blocked,world.generation,observers.values,world)
        assertEquals(6,NpcStrategy.returnDistance(blocked,forces,fleet,world))
        var now=0L
        repeat(6) {
            world=NpcStrategy.advance(simulation.copy(gameMillis=now*1000),blocked,observers,world,events)
            assertNotNull(world.outfits.getValue(1).strategy?.destination)
            now+=3600
            world=NpcStrategy.advance(simulation.copy(gameMillis=now*1000),blocked,observers,world,events)
            now+=21600
        }
        assertEquals(6,events.size)
        assertEquals(2390,events.first().destination)
        assertEquals(OutfitPosition(2487,1000),world.outfits.getValue(1).position)
        assertEquals(3000,world.outfits.getValue(1).supplies)
        assertEquals(97000,world.bases.getValue(1000).supplies)
    }

    @Test fun isolatedNearerDepotDoesNotPreventReturnToReachableDepot() {
        val alternate=galaxy.bases.single().copy(id=1001,grid=2494,name="Alternate")
        val split=Galaxy(galaxy.systems+GalaxySystem(5,2494,4,"Alternate",2),galaxy.bases+alternate,galaxy.starts,
            terrain=List(50) {CharArray(100) {x->if(x==89)'#' else '.'}.concatToString()})
        val initial=stocks()
        val fleet=OutfitSupplies.withBalances(initial.outfits.getValue(1).copy(position=OutfitPosition(2490)),mapOf(1 to 1200))
        val world=initial.copy(bases=initial.bases+(1001 to initial.bases.getValue(1000).copy(baseId=1001)),outfits=mapOf(1 to fleet))
        val forces=GridForces.collect(split,world.generation,emptyList(),world)
        assertEquals(4,NpcStrategy.returnDistance(split,forces,fleet,world))
        val reserved=NpcStrategy.advance(simulation,split,mutableMapOf(),world,mutableListOf())
        assertEquals(2391,reserved.outfits.getValue(1).strategy?.destination)
    }

    @Test fun returningFleetPrefersAStockedDepotOnlyWhenItCanAffordTheRoute() {
        val alternate=galaxy.bases.single().copy(id=1001,grid=2488,name="Alternate")
        val world=Galaxy(galaxy.systems,galaxy.bases+alternate,galaxy.starts)
        val original=stocks()
        val stocked=original.bases.getValue(1000)
        val reserves=mapOf(1000 to stocked.copy(supplies=0),1001 to stocked.copy(baseId=1001))
        val fleet=original.outfits.getValue(1).copy(position=OutfitPosition(2487))
        for(fuel in listOf(0,300)) {
            val ready=OutfitSupplies.withBalances(fleet,mapOf(1 to fuel))
            val before=original.copy(bases=reserves,outfits=mapOf(1 to ready))
            val players=mutableMapOf<String,Navigator>()
            val events=mutableListOf<NpcStrategicMovement>()
            fun advanceWorld(at:Long,stocks:PlanetStocks)=
                NpcStrategy.advance(simulation.copy(gameMillis=at*1000),world,players,stocks,events)
            val decided=advanceWorld(0,before)
            if(fuel==0) {
                assertEquals(OutfitPosition(2487,1000),decided.outfits.getValue(1).position)
                assertEquals(reserves,decided.bases)
                assertTrue(events.isEmpty())
            } else {
                assertEquals(2488,decided.outfits.getValue(1).strategy?.destination)
                assertEquals(reserves,decided.bases)
                val arrived=advanceWorld(3600,decided)
                assertEquals(OutfitPosition(2488,1001),arrived.outfits.getValue(1).position)
                assertEquals(97000,arrived.bases.getValue(1001).supplies)
                assertEquals(2488,events.single().destination)
            }
        }
    }

    @Test fun capturedRebelOrDepletedDepotReroutesTheReturningFleetWithoutChargingTheCancelledJump() {
        val alternate=galaxy.bases.single().copy(id=1001,grid=2588,name="Alternate")
        val world=Galaxy(galaxy.systems+GalaxySystem(5,2588,4,"Alternate",2),galaxy.bases+alternate,galaxy.starts)
        val original=stocks()
        val depot=original.bases.getValue(1000)
        val fleet=OutfitSupplies.withBalances(original.outfits.getValue(1).copy(position=OutfitPosition(2488)),mapOf(1 to 300))
        val initial=original.copy(bases=original.bases+(1001 to depot.copy(baseId=1001)),outfits=mapOf(1 to fleet))
        val observers=mutableMapOf<String,Navigator>()
        val events=mutableListOf<NpcStrategicMovement>()
        fun advanceWorld(at:Long,stocks:PlanetStocks)=
            NpcStrategy.advance(simulation.copy(gameMillis=at*1000),world,observers,stocks,events)
        val reserved=advanceWorld(0,initial)
        assertEquals(2487,reserved.outfits.getValue(1).strategy?.destination)
        for(unavailable in listOf(depot.copy(owner=Allegiance.fromLegacy(3)),depot.copy(camp=1),depot.copy(supplies=0))) {
            events.clear()
            val changed=reserved.copy(bases=reserved.bases+(1000 to unavailable))
            val rerouted=advanceWorld(3600,changed)
            assertEquals(NpcStrategicState(25200,2588,7200),rerouted.outfits.getValue(1).strategy)
            assertEquals(fleet.position,rerouted.outfits.getValue(1).position)
            assertEquals(300,rerouted.outfits.getValue(1).supplies)
            assertEquals(changed.bases,rerouted.bases)
            assertTrue(events.isEmpty())
            assertEquals(rerouted,advanceWorld(7199,rerouted.copy()))
            val arrived=advanceWorld(7200,rerouted)
            assertEquals(OutfitPosition(2588,1001),arrived.outfits.getValue(1).position)
            assertEquals(3000,arrived.outfits.getValue(1).supplies)
            assertEquals(97000,arrived.bases.getValue(1001).supplies)
            assertEquals(changed.bases.getValue(1000),arrived.bases.getValue(1000))
            assertEquals(2588,events.single().destination)
        }
    }

    @Test fun strandedFleetCanDockAndReloadWithoutAnotherJumpAndCannotUseAnOccupiedDepot() {
        val events=mutableListOf<NpcStrategicMovement>()
        val original=stocks()
        val fleet=original.outfits.getValue(1)
        val point=TacticalPoint(2f,20f)
        val motion=TacticalMotion(2487,0,TacticalPose(point,0f),0f,0f,listOf(point))
        val ship=fleet.activeShips!!.values.single().copy(tactical=NpcTacticalState(motion,0,0),
            posture=TacticalPosture(2487,TacticalShipMode.ANCHORED,1000))
        val stranded=original.copy(outfits=mapOf(1 to fleet.copy(position=OutfitPosition(2487),
            activeShips=ActiveShipUnits(listOf(ship)))))
        val docked=advance(0,stranded,mutableMapOf(),events)
        assertEquals(OutfitPosition(2487,1000),docked.outfits.getValue(1).position)
        assertEquals(3000,docked.outfits.getValue(1).supplies)
        assertEquals(97000,docked.bases.getValue(1000).supplies)
        val dockedShip=docked.outfits.getValue(1).activeShips!!.values.single()
        assertNull(dockedShip.posture)
        assertNull(dockedShip.tactical)
        assertEquals(docked,advance(0,docked.copy(),mutableMapOf(),events))
        assertTrue(events.isEmpty())
        val occupied=advance(0,stranded,players().also {it["pilot"]=it.getValue("pilot").copy(grid=2487)},events)
        assertEquals(OutfitPosition(2487),occupied.outfits.getValue(1).position)
        assertEquals(0,occupied.outfits.getValue(1).supplies)
        assertEquals(stranded.bases,occupied.bases)
        assertEquals(ship,occupied.outfits.getValue(1).activeShips!!.values.single())
    }

    @Test fun cancelledJumpCanDockAtTheCurrentGridWithoutSpendingJumpFuel() {
        val alternate=galaxy.bases.single().copy(id=1001,grid=2488,name="Alternate")
        val world=Galaxy(galaxy.systems,galaxy.bases+alternate,galaxy.starts)
        val original=stocks()
        val depot=original.bases.getValue(1000)
        val remote=depot.copy(baseId=1001)
        val fleet=OutfitSupplies.withBalances(original.outfits.getValue(1).copy(position=OutfitPosition(2487)),mapOf(1 to 300))
        val initial=original.copy(bases=mapOf(1000 to depot.copy(supplies=0),1001 to remote),outfits=mapOf(1 to fleet))
        val events=mutableListOf<NpcStrategicMovement>()
        fun advanceWorld(at:Long,stocks:PlanetStocks)=
            NpcStrategy.advance(simulation.copy(gameMillis=at*1000),world,mutableMapOf(),stocks,events)
        val reserved=advanceWorld(0,initial)
        assertEquals(2488,reserved.outfits.getValue(1).strategy?.destination)
        val changed=reserved.copy(bases=mapOf(1000 to depot,1001 to remote.copy(supplies=0)))
        val docked=advanceWorld(3600,changed)
        assertEquals(OutfitPosition(2487,1000),docked.outfits.getValue(1).position)
        assertEquals(3000,docked.outfits.getValue(1).supplies)
        assertEquals(97300,docked.bases.getValue(1000).supplies)
        assertEquals(changed.bases.getValue(1001),docked.bases.getValue(1001))
        assertTrue(events.isEmpty())
    }
    @Test fun appointedCommanderOrCapturedDepotCancelsWithoutChargingOrMoving() {
        for(appointed in listOf(true,false)) {
            val players=players();val events=mutableListOf<NpcStrategicMovement>()
            var reserved=advance(0,stocks(),players,events)
            reserved=if(appointed)reserved.copy(outfits=reserved.outfits.mapValues {(_,o)->o.copy(commanderId=10001)})
                else reserved.copy(bases=reserved.bases.mapValues {(_,b)->b.copy(owner=Allegiance.fromLegacy(3))})
            val result=advance(3600,reserved,players,events)
            assertNull(result.outfits.getValue(1).strategy)
            assertEquals(reserved.bases,result.bases);assertEquals(OutfitPosition(2487,1000),result.outfits.getValue(1).position)
            assertTrue(events.isEmpty())
        }
    }
    @Test fun tacticalCommandTransferCancelsAReservedNpcJumpWithoutSpendingSupplies() {
        val players=players()
        val events=mutableListOf<NpcStrategicMovement>()
        val reserved=advance(0,stocks(),players,events)
        val fleet=reserved.outfits.getValue(1)
        assertNotNull(fleet.strategy?.destination)
        val unit=fleet.activeShips!!.values.single()
        val assigned=reserved.copy(tacticalCommands=reserved.tacticalCommands.copy(authority=mapOf(unit.id to 10001)))

        val settled=advance(3600,assigned,players,events)
        assertEquals(fleet.copy(strategy=null),settled.outfits.getValue(1))
        assertEquals(assigned.bases,settled.bases)
        assertEquals(assigned.tacticalCommands,settled.tacticalCommands)
        assertTrue(events.isEmpty())
        assertEquals(settled,advance(25200,settled,players,events))
        assertTrue(events.isEmpty())
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
    @Test fun dockedFleetNeedsBothOutboundAndReturnFuelAtReservationAndDeparture() {
        val players=players();val events=mutableListOf<NpcStrategicMovement>()
        val initial=stocks()
        val depot=initial.bases.getValue(1000)
        val oneJump=initial.copy(bases=mapOf(1000 to depot.copy(supplies=300)))
        assertNull(advance(0,oneJump,players,events).outfits.getValue(1).strategy?.destination)
        val twoJumps=oneJump.copy(bases=mapOf(1000 to depot.copy(supplies=600)))
        val reserved=advance(0,twoJumps,players,events)
        assertEquals(2488,reserved.outfits.getValue(1).strategy?.destination)
        assertEquals(twoJumps.bases,reserved.bases)
        val cancelled=advance(3600,reserved.copy(bases=oneJump.bases),players,events)
        assertEquals(OutfitPosition(2487,1000),cancelled.outfits.getValue(1).position)
        assertEquals(oneJump.bases,cancelled.bases)
        assertTrue(events.isEmpty())
        val departed=advance(3600,reserved,players,events)
        assertEquals(300,departed.outfits.getValue(1).supplies)
        assertEquals(0,departed.bases.getValue(1000).supplies)
        assertEquals(2488,events.single().destination)
    }

    @Test fun noFuelNoTargetOrNoncombatOutfitDoesNotCreateTravelOrResources() {
        val players=players();val events=mutableListOf<NpcStrategicMovement>()
        var reserved=advance(0,stocks(),players,events)
        reserved=reserved.copy(bases=reserved.bases.mapValues {(_,b)->b.copy(supplies=0)})
        val failed=advance(3600,reserved,players,events)
        assertEquals(OutfitPosition(2487,1000),failed.outfits.getValue(1).position)
        assertEquals(reserved.bases,failed.bases);assertEquals(0,failed.outfits.getValue(1).supplies);assertTrue(events.isEmpty())
        val emptyDepot=advance(0,stocks().copy(bases=reserved.bases),players,events)
        assertNull(emptyDepot.outfits.getValue(1).strategy?.destination)
        assertEquals(reserved.bases,emptyDepot.bases)
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

    @Test fun failedReturnCommitKeepsDepotAndFleetThenRetryRefuelsOnce() {
        var saved=stocks().let {s->s.copy(outfits=s.outfits.mapValues {(_,o)->
            OutfitSupplies.withBalances(o.copy(position=OutfitPosition(2488)),mapOf(1 to 300))})}
        var navigators=players().mapValues {(_,p)->p.copy(grid=2588)};var fail=false
        val stockStore=object:PlanetStockStore {override fun load()=saved;override fun save(stocks:PlanetStocks){saved=stocks}}
        val navStore=object:NavigationStore {override fun load()=navigators;override fun save(players:Map<String,Navigator>){navigators=players}}
        val production=PlanetProduction(galaxy,stockStore,0,PlanetProductionPolicy()).also {it.settle(1,0)}
        val navigation=Navigation(galaxy,navStore,NavigationPolicy())
        val commit=WorldStateCommitter {p,s->if(fail)throw java.io.IOException("disk");navigators=p.toMap();saved=checkNotNull(s)}
        val events=mutableListOf<NpcStrategicMovement>()
        fun settle(at:Long)=TacticalCombat.settle(simulation.copy(gameMillis=at*1000),galaxy,navigation,production,commit,
            NpcTacticalPolicy(strategic=true),onStrategicMovement=events::add)
        settle(0);val reserved=saved
        fail=true;assertFailsWith<java.io.IOException> {settle(3600)}
        assertEquals(reserved,production.snapshot(1));assertEquals(reserved,saved);assertTrue(events.isEmpty())
        fail=false;settle(3600)
        assertEquals(OutfitPosition(2487,1000),saved.outfits.getValue(1).position)
        assertEquals(3000,saved.outfits.getValue(1).supplies);assertEquals(97000,saved.bases.getValue(1000).supplies)
        settle(3600)
        assertEquals(3000,saved.outfits.getValue(1).supplies);assertEquals(97000,saved.bases.getValue(1000).supplies)
        assertEquals(1,events.size)
    }
    @Test fun invalidStrategicStateDoesNotLoadAHalfReservation() {
        assertFailsWith<IllegalArgumentException> {NpcStrategicState(10,2488,null)}
        assertFailsWith<IllegalArgumentException> {NpcStrategicState(10,null,9)}
        assertFailsWith<IllegalArgumentException> {NpcStrategicState(10,5000,9)}
        assertFailsWith<IllegalArgumentException> {NpcStrategicState(10,2488,11)}
    }
}
