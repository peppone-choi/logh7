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

    @Test fun dockedNpcSortiesAgainstDeployedEnemyInItsOwnSystemWithoutWarping() {
        val before = stocks()
        val observers = players()
        observers["pilot"] = observers.getValue("pilot").copy(grid = 2487, mode = 5)
        val movements = mutableListOf<NpcStrategicMovement>()
        val after = advance(0, before, observers, movements)
        val fleet = after.outfits.getValue(1)
        assertEquals(OutfitPosition(2487), fleet.position)
        assertEquals(3000, fleet.supplies)
        assertEquals(before.bases.getValue(1000).supplies - fleet.supplies, after.bases.getValue(1000).supplies)
        assertTrue(observers.getValue("pilot").cpPaused)
        assertEquals(NpcStrategicMovement(1, 1, 2487, 2487, 0, listOf(1), mapOf(10000 to "current")),
            movements.single())
        assertEquals(after, advance(0, after, observers, movements))
        assertEquals(1, movements.size)
    }

    @Test fun localDefenseDoesNotLaunchAgainstDockedOrOverwhelmingEnemies() {
        val before = stocks()
        val enemy = players().getValue("pilot").copy(grid = 2487, mode = 5)
        val docked = mutableMapOf("pilot" to enemy.copy(baseId = 1000, mode = 1))
        val overwhelming = (0..2).associate { index ->
            "enemy$index" to enemy.copy(characterId = enemy.characterId + index)
        }.toMutableMap()
        for (observers in listOf(docked, overwhelming)) {
            val events = mutableListOf<NpcStrategicMovement>()
            val after = advance(0, before, observers, events)
            assertEquals(before.outfits.getValue(1).position, after.outfits.getValue(1).position)
            assertEquals(before.bases, after.bases)
            assertTrue(events.isEmpty())
        }
    }

    @Test fun awardedOriginalOnlyHoldsItsVacantFleetAtTheStartingBerth() {
        val base=galaxy.bases.single().copy(id=1049)
        val world=Galaxy(galaxy.systems,listOf(base),galaxy.starts)
        val initial=stocks()
        val fleet=initial.outfits.getValue(1).copy(id=2,number=2,baseId=base.id,
            position=OutfitPosition(base.grid,base.id),strategy=NpcStrategicState(21600,2488,3600))
        val ready=initial.copy(bases=mapOf(base.id to initial.bases.getValue(1000).copy(baseId=base.id)),
            outfits=mapOf(2 to fleet.copy(activeGround=null)),warehouses=mapOf(10 to initial.warehouses.getValue(10).copy(baseId=base.id,outfitId=2)))
        val claim=OriginalBerthClaim("winner",1001,CountryId(2),10,null)
        val due=simulation.copy(gameMillis=3600_000)
        val events=mutableListOf<NpcStrategicMovement>()
        val targets=players()
        assertEquals(ready,NpcStrategy.advance(due,world,targets,ready,events,startingClaims=listOf(claim)))
        assertTrue(events.isEmpty())
        val departed=NpcStrategy.advance(due,world,targets,ready,events)
        assertEquals(OutfitPosition(2488),departed.outfits.getValue(2).position)
        assertEquals(1,events.size)
        assertEquals(departed,NpcStrategy.advance(due,world,targets,departed,events,startingClaims=listOf(claim)))
        val entered=players().apply {put("winner",Navigator(1,1001,Power.EMPIRE,base.grid,100,100,100,baseId=base.id,mode=1))}
        val afterEntry=NpcStrategy.advance(due,world,entered,ready,mutableListOf(),startingClaims=listOf(claim))
        assertEquals(OutfitPosition(2488),afterEntry.outfits.getValue(2).position)
    }

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

    @Test fun aSavedEnemyForceDoesNotSustainAnOffensiveJumpAfterItsCountryIsEliminated() {
        val empire=Power.EMPIRE.country
        val alliance=Power.ALLIANCE.country
        val continuing=simulation.copy(countries=setOf(empire,alliance,CountryId(42)))
        val before=stocks()
        val pilots=players()
        val events=mutableListOf<NpcStrategicMovement>()
        val reserved=NpcStrategy.advance(continuing.copy(gameMillis=0),galaxy,pilots,before,events)
        val fleet=reserved.outfits.getValue(1)
        assertEquals(NpcStrategicState(21600,2488,3600),fleet.strategy)
        val due=continuing.copy(gameMillis=3600_000,eliminated=mapOf(alliance to CountryDefeat(EndReason.SYSTEMS_REDUCED,1)))
        val settled=NpcStrategy.advance(due,galaxy,pilots,reserved,events)
        assertEquals(fleet.copy(strategy=NpcStrategicState(25200)),settled.outfits.getValue(1))
        assertEquals(reserved.bases,settled.bases)
        assertTrue(events.isEmpty())
        assertFalse(pilots.getValue("pilot").cpPaused)
        assertTrue(GridForces.collect(galaxy,settled.generation,pilots.values,settled).getValue(2488).any {
            it.camp.country==alliance && it.units>0
        })
    }

    @Test fun defeatedFlagshipsAtDepartureDoNotBlockAnAdvanceAgainstAnActiveCountry() {
        val empire = Power.EMPIRE.country
        val alliance = Power.ALLIANCE.country
        val defeatedCountry = CountryId(42)
        val continuing = simulation.copy(countries = setOf(empire, alliance, defeatedCountry),
            eliminated = mapOf(defeatedCountry to CountryDefeat(EndReason.SYSTEMS_REDUCED, 0)))
        val pilots = players()
        for (id in 12000..12002) {
            pilots[id.toString()] = Navigator(1, id, Power.ALLIANCE, 2487, 1000, 1000, 1000)
                .copy(country = defeatedCountry)
        }
        val events = mutableListOf<NpcStrategicMovement>()
        val before = stocks()
        val reserved = NpcStrategy.advance(continuing.copy(gameMillis = 0), galaxy, pilots, before, events)
        assertEquals(NpcStrategicState(21600, 2488, 3600), reserved.outfits.getValue(1).strategy)
        val arrived = NpcStrategy.advance(continuing.copy(gameMillis = 3600_000), galaxy, pilots, reserved, events)
        assertEquals(OutfitPosition(2488), arrived.outfits.getValue(1).position)
        assertEquals(2488, events.single().destination)
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
    @Test fun npcArrivalEntersLiveTargetIntoTacticsWithoutChangingDefeatedOrDeceasedPilots() {
        val defeatedCountry=CountryId(42)
        val pilots=players()
        val live=pilots.getValue("pilot")
        val defeated=live.copy(characterId=10001)
        val deceased=live.copy(characterId=10002)
        pilots["defeated"]=defeated
        pilots["deceased"]=deceased
        val events=mutableListOf<NpcStrategicMovement>()
        val before=BattleDeath.apply(stocks(),listOf(deceased.characterId),1) {_,_->true}
        val continuing=simulation.copy(countries=setOf(Power.EMPIRE.country,Power.ALLIANCE.country,defeatedCountry),
            participants=simulation.participants+("defeated" to Participant(defeatedCountry,false,false)))
        val reserved=NpcStrategy.advance(continuing.copy(gameMillis=0),galaxy,pilots,before,events)
        assertEquals(NpcStrategicState(21600,2488,3600),reserved.outfits.getValue(1).strategy)
        val due=continuing.copy(gameMillis=3_600_000,eliminated=mapOf(defeatedCountry to CountryDefeat(EndReason.SYSTEMS_REDUCED,1)))
        val arrived=NpcStrategy.advance(due,galaxy,pilots,reserved,events)
        assertEquals(OutfitPosition(2488),arrived.outfits.getValue(1).position)
        assertTrue(pilots.getValue("pilot").cpPaused)
        assertEquals(defeated,pilots.getValue("defeated"))
        assertEquals(deceased,pilots.getValue("deceased"))
        assertEquals(2488,events.single().destination)
        assertTrue(GridForces.collect(galaxy,arrived.generation,pilots.values,arrived).getValue(2488).any {
            it.camp.country==Power.ALLIANCE.country && it.units==2L
        })
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
        val defeatedCountry = CountryId(42)
        val afterDefeat = simulation.copy(gameMillis = 0,
            countries = setOf(Power.EMPIRE.country, Power.ALLIANCE.country, defeatedCountry),
            eliminated = mapOf(defeatedCountry to CountryDefeat(EndReason.SYSTEMS_REDUCED, 0)))
        val ghost = observers.getValue("pilot").copy(characterId = 12000, country = defeatedCountry,
            grid = 2487, baseId = 0, mode = 5)
        val withGhost = observers.toMutableMap().also { it["defeated"] = ghost }
        val withSafeReturn = NpcStrategy.advance(afterDefeat, galaxy, withGhost, stock(4), events)
        assertEquals(affordable, withSafeReturn)
        val insufficient=advance(0,stock(3),observers,events)
        assertEquals(88,insufficient.outfits.getValue(1).strategy!!.destination!!%100)
        val depleted=affordable.copy(outfits=affordable.outfits.mapValues {(_,outfit)->
            OutfitSupplies.withBalances(outfit,mapOf(1 to unit.boats*3))})
        val cancelled=advance(3600,depleted,observers,events)
        assertEquals(fleet.position,cancelled.outfits.getValue(1).position)
        assertEquals(unit.boats*3,cancelled.outfits.getValue(1).supplies)
        assertTrue(events.isEmpty())
    }

    @Test fun losingTheLastReachableDepotStopsAnOffensiveJumpBeforeItSpendsFuel() {
        val original=stocks()
        val fleet=OutfitSupplies.withBalances(original.outfits.getValue(1).copy(position=OutfitPosition(2489)),mapOf(1 to 600))
        val captured=original.copy(
            bases=original.bases.mapValues {(_,base)->base.copy(owner=Allegiance.fromLegacy(3))},
            outfits=mapOf(1 to fleet))
        val observers=players().also {it["pilot"]=it.getValue("pilot").copy(grid=2490)}
        val events=mutableListOf<NpcStrategicMovement>()
        val holding=advance(0,captured,observers,events)
        assertEquals(fleet.copy(strategy=NpcStrategicState(21600)),holding.outfits.getValue(1))

        val pending=fleet.copy(strategy=NpcStrategicState(21600,2490,3600))
        val cancelled=advance(3600,captured.copy(outfits=mapOf(1 to pending)),observers,events)
        assertEquals(fleet.copy(strategy=NpcStrategicState(25200)),cancelled.outfits.getValue(1))
        assertEquals(captured.bases,cancelled.bases)
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
    @Test fun blockedOffensiveRouteFallsBackToFriendlyDepotButAnEnemyHereKeepsTheFleetEngaged() {
        val storm=Galaxy(galaxy.systems,galaxy.bases,galaxy.starts,terrain=List(50) {y->
            CharArray(100) {x->if(x==89 && y in 23..25)'~' else '.'}.concatToString()
        })
        val initial=stocks()
        val fleet=OutfitSupplies.withBalances(initial.outfits.getValue(1).copy(position=OutfitPosition(2488)),mapOf(1 to 2700))
        val stock=initial.copy(outfits=mapOf(1 to fleet))
        val pilots=players()
        val events=mutableListOf<NpcStrategicMovement>()
        val engaged=NpcStrategy.advance(simulation.copy(gameMillis=0),storm,pilots,stock,events)
        assertEquals(NpcStrategicState(21600),engaged.outfits.getValue(1).strategy)
        assertEquals(fleet.position,engaged.outfits.getValue(1).position)
        assertTrue(events.isEmpty())

        pilots["pilot"]=pilots.getValue("pilot").copy(grid=2490)
        val returning=NpcStrategy.advance(simulation.copy(gameMillis=0),storm,pilots,stock,events)
        assertEquals(NpcStrategicState(21600,2487,3600),returning.outfits.getValue(1).strategy)
        assertEquals(stock.bases,returning.bases)
        assertTrue(events.isEmpty())
        val docked=NpcStrategy.advance(simulation.copy(gameMillis=3600000),storm,pilots,returning,events)
        assertEquals(OutfitPosition(2487,1000),docked.outfits.getValue(1).position)
        assertEquals(3000,docked.outfits.getValue(1).supplies)
        assertEquals(99400,docked.bases.getValue(1000).supplies)
        assertEquals(2487,events.single().destination)
    }

    @Test fun blockedNearestEnemyDoesNotHideAReachableOffensiveTarget() {
        val initial=stocks()
        val fleet=OutfitSupplies.withBalances(initial.outfits.getValue(1).copy(position=OutfitPosition(2488)),mapOf(1 to 2700))
        val stock=initial.copy(outfits=mapOf(1 to fleet))
        val pilot=players().getValue("pilot")
        for(blocked in listOf(false,true)) {
            val world=Galaxy(galaxy.systems,galaxy.bases,galaxy.starts,terrain=List(50) {y->
                CharArray(100) {x->
                    if((x==87 || x==89) && y in 23..25 && (y!=24 || blocked && x==89))'~' else '.'
                }.concatToString()
            })
            val pilots=mutableMapOf("pilot" to pilot.copy(grid=2490),"far" to pilot.copy(characterId=10001,grid=2485))
            val events=mutableListOf<NpcStrategicMovement>()
            val reserved=NpcStrategy.advance(simulation.copy(gameMillis=0),world,pilots,stock,events)
            val destination=if(blocked)2487 else 2489
            assertEquals(destination,reserved.outfits.getValue(1).strategy?.destination)
            assertEquals(stock.bases,reserved.bases)
            assertTrue(events.isEmpty())
            val arrived=NpcStrategy.advance(simulation.copy(gameMillis=3600000),world,pilots,reserved,events)
            assertEquals(OutfitPosition(destination),arrived.outfits.getValue(1).position)
            assertEquals(2400,arrived.outfits.getValue(1).supplies)
            assertEquals(stock.bases,arrived.bases)
            assertEquals(destination,events.single().destination)
        }
    }

    @Test fun anAffordableFirstHopToTheSameEnemyKeepsEnoughFuelToReturn() {
        val depot=galaxy.bases.single().copy(grid=2588)
        val world=Galaxy(galaxy.systems+GalaxySystem(5,2588,4,"Depot",2),listOf(depot),galaxy.starts)
        val initial=stocks()
        val outfit=initial.outfits.getValue(1)
        val ship=outfit.activeShips!!.values.single().copy(damaged=299)
        val fleet=OutfitSupplies.withBalances(outfit.copy(position=OutfitPosition(2488),
            activeShips=ActiveShipUnits(listOf(ship))),mapOf(ship.id to 600))
        val inventory=initial.copy(outfits=mapOf(fleet.id to fleet))
        val pilots=players().apply {this["pilot"]=getValue("pilot").copy(grid=2490)}
        val events=mutableListOf<NpcStrategicMovement>()
        val reserved=NpcStrategy.advance(simulation.copy(gameMillis=0),world,pilots,inventory,events)
        // 2389 is first by ID but needs two jumps back; 2489 needs only one.
        assertEquals(2489,reserved.outfits.getValue(fleet.id).strategy?.destination)
        assertEquals(600,reserved.outfits.getValue(fleet.id).supplies)
        assertTrue(events.isEmpty())
        val arrived=NpcStrategy.advance(simulation.copy(gameMillis=3600000),world,pilots,reserved,events)
        assertEquals(OutfitPosition(2489),arrived.outfits.getValue(fleet.id).position)
        assertEquals(300,arrived.outfits.getValue(fleet.id).supplies)
        assertEquals(inventory.bases,arrived.bases)
        assertEquals(2489,events.single().destination)
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

    @Test fun dryLocalDepotDoesNotBlockAnAffordableReturnOrFundedRescue() {
        val alternate = galaxy.bases.single().copy(id = 1001, grid = 2488, name = "Alternate")
        val world = Galaxy(galaxy.systems, galaxy.bases + alternate, galaxy.starts)
        val original = stocks()
        val depot = original.bases.getValue(1000)
        val remote = depot.copy(baseId = 1001)
        val fleet = original.outfits.getValue(1).copy(position = OutfitPosition(2487))
        for (fuel in listOf(0, 300)) {
            val initial = original.copy(
                bases = mapOf(1000 to depot.copy(supplies = 0), 1001 to remote),
                outfits = mapOf(1 to OutfitSupplies.withBalances(fleet, mapOf(1 to fuel))),
            )
            val events = mutableListOf<NpcStrategicMovement>()
            fun advanceWorld(at: Long, stocks: PlanetStocks, pilots: MutableMap<String,Navigator> = mutableMapOf()) =
                NpcStrategy.advance(simulation.copy(gameMillis = at * 1000), world, pilots, stocks, events)

            val reserved = advanceWorld(0, initial)
            assertEquals(2488, reserved.outfits.getValue(1).strategy?.destination)
            assertEquals(OutfitPosition(2487), reserved.outfits.getValue(1).position)
            assertEquals(fuel, reserved.outfits.getValue(1).supplies)
            assertEquals(initial.bases, reserved.bases)
            assertTrue(events.isEmpty())

            val docked = advanceWorld(3600, reserved)
            assertEquals(OutfitPosition(2488, 1001), docked.outfits.getValue(1).position)
            assertEquals(3000, docked.outfits.getValue(1).supplies)
            assertEquals(0, docked.bases.getValue(1000).supplies)
            assertEquals(remote.supplies - 3300 + fuel, docked.bases.getValue(1001).supplies)
            assertEquals(2488, events.single().destination)

            if (fuel == 0) {
                events.clear()
                val depleted = reserved.copy(bases = reserved.bases + (1001 to remote.copy(supplies = 0)))
                val fallback = advanceWorld(3600, depleted)
                assertEquals(OutfitPosition(2487, 1000), fallback.outfits.getValue(1).position)
                assertEquals(depleted.bases, fallback.bases)
                assertTrue(events.isEmpty())

                val parked=fallback.outfits.getValue(1)
                val nextCheck=assertNotNull(parked.strategy).nextDecisionAt
                val restored=fallback.copy(bases=fallback.bases+(1001 to remote))
                val enemy=players().getValue("pilot").copy(grid=2487)
                val contested=advanceWorld(nextCheck,restored,mutableMapOf("pilot" to enemy))
                assertNull(contested.outfits.getValue(1).strategy?.destination)
                assertEquals(restored.bases,contested.bases)

                val locallyRestored=restored.copy(bases=restored.bases+(1000 to depot))
                val normal=advanceWorld(nextCheck,locallyRestored,mutableMapOf("pilot" to enemy.copy(grid=2486)))
                assertEquals(2486,normal.outfits.getValue(1).strategy?.destination)
                assertEquals(locallyRestored.bases,normal.bases)

                val retry=advanceWorld(nextCheck,restored)
                val rescue=assertNotNull(retry.outfits.getValue(1).strategy)
                assertEquals(2488,rescue.destination)
                assertEquals(parked,retry.outfits.getValue(1).copy(strategy=parked.strategy))
                assertEquals(restored.bases,retry.bases)
                assertTrue(events.isEmpty())

                val arrival=assertNotNull(rescue.arrivesAt)
                val emptyAgain=retry.copy(bases=depleted.bases)
                val cancelled=advanceWorld(arrival,emptyAgain)
                assertEquals(parked,cancelled.outfits.getValue(1).copy(strategy=parked.strategy))
                assertEquals(emptyAgain.bases,cancelled.bases)
                assertTrue(events.isEmpty())

                val recovered=advanceWorld(arrival,retry)
                assertEquals(OutfitPosition(2488,1001),recovered.outfits.getValue(1).position)
                assertEquals(3000,recovered.outfits.getValue(1).supplies)
                assertEquals(0,recovered.bases.getValue(1000).supplies)
                assertEquals(remote.supplies-3300,recovered.bases.getValue(1001).supplies)
                assertEquals(2488,events.single().destination)
                assertEquals(recovered,advanceWorld(arrival,recovered))
                assertEquals(1,events.size)
            }
        }
    }

    @Test fun dockedFleetWithInsufficientLocalStockCanUseRemoteRelief() {
        val initial = stocks()
        val local = initial.bases.getValue(1000).copy(supplies = 599)
        val remote = initial.bases.getValue(1000).copy(baseId = 1001)
        val world = Galaxy(galaxy.systems, galaxy.bases + galaxy.bases.single().copy(id = 1001, grid = 2488), galaxy.starts)
        val before = initial.copy(bases = mapOf(1000 to local, 1001 to remote))
        val events = mutableListOf<NpcStrategicMovement>()
        fun advanceWorld(at: Long, inventory: PlanetStocks) = NpcStrategy.advance(
            simulation.copy(gameMillis = at * 1000), world, mutableMapOf(), inventory, events)

        val reserved = advanceWorld(0, before)
        assertEquals(NpcStrategicState(21600, 2488, 3600), reserved.outfits.getValue(1).strategy)
        assertEquals(before.bases, reserved.bases)
        assertTrue(events.isEmpty())
        val short = reserved.copy(bases = reserved.bases + (1001 to remote.copy(supplies = 299)))
        val cancelled = advanceWorld(3600, short)
        assertNull(cancelled.outfits.getValue(1).strategy?.destination)
        assertEquals(OutfitPosition(2487, 1000), cancelled.outfits.getValue(1).position)
        assertEquals(short.bases, cancelled.bases)
        assertTrue(events.isEmpty())

        val arrived = advanceWorld(3600, reserved)
        assertEquals(OutfitPosition(2488, 1001), arrived.outfits.getValue(1).position)
        assertEquals(3000, arrived.outfits.getValue(1).supplies)
        assertEquals(local, arrived.bases.getValue(1000))
        assertEquals(remote.supplies - 3300, arrived.bases.getValue(1001).supplies)
        assertEquals(2488, events.single().destination)
    }

    @Test fun unavailableDepotReroutesTheReturningFleetWithoutChargingTheCancelledJump() {
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
        val unavailableDepots=listOf(depot.copy(owner=Allegiance.fromLegacy(3)),depot.copy(camp=1),depot.copy(supplies=0))
        for(unavailable in unavailableDepots+depot) {
            events.clear()
            observers.clear()
            if(unavailable==depot)observers["pilot"]=players().getValue("pilot").copy(grid=2487)
            val changed=reserved.copy(bases=reserved.bases+(1000 to unavailable))
            val rerouted=advanceWorld(3600,changed)
            assertEquals(NpcStrategicState(25200,2588,7200),rerouted.outfits.getValue(1).strategy)
            assertEquals(fleet.position,rerouted.outfits.getValue(1).position)
            assertEquals(300,rerouted.outfits.getValue(1).supplies)
            assertEquals(changed.bases,rerouted.bases)
            if(unavailable==depot)assertEquals(players().getValue("pilot").copy(grid=2487),observers["pilot"])
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

    @Test fun localResupplyUsesTheBetterStockedDepotAndBreaksTiesByBaseId() {
        val alternate=galaxy.bases.single().copy(id=1001,name="Alternate")
        val world=Galaxy(galaxy.systems,galaxy.bases+alternate,galaxy.starts)
        val original=stocks()
        val depot=original.bases.getValue(1000)
        val fleet=original.outfits.getValue(1).copy(position=OutfitPosition(2487))
        for(localStock in listOf(1,depot.supplies)) {
            val initial=original.copy(bases=mapOf(1000 to depot.copy(supplies=localStock),1001 to depot.copy(baseId=1001)),
                outfits=mapOf(1 to fleet))
            val movements=mutableListOf<NpcStrategicMovement>()
            val docked=NpcStrategy.advance(simulation,world,mutableMapOf(),initial,movements)
            val chosen=if(localStock==1)1001 else 1000
            val other=if(chosen==1000)1001 else 1000
            assertEquals(OutfitPosition(2487,chosen),docked.outfits.getValue(1).position)
            assertEquals(3000,docked.outfits.getValue(1).supplies)
            assertEquals(depot.supplies-3000,docked.bases.getValue(chosen).supplies)
            assertEquals(initial.bases.getValue(other),docked.bases.getValue(other))
            assertTrue(movements.isEmpty())
            assertEquals(docked,NpcStrategy.advance(simulation,world,mutableMapOf(),docked,movements))
        }
    }

    @Test fun idleNpcRetriesPartialDockResupplyUntilItsCargoIsFull() {
        val initial=stocks()
        val depot=initial.bases.getValue(1000)
        val fleet=initial.outfits.getValue(1).copy(position=OutfitPosition(2487))
        val events=mutableListOf<NpcStrategicMovement>()
        for(initialSupply in listOf(0,100)) {
            val docked=advance(0,initial.copy(bases=mapOf(1000 to depot.copy(supplies=initialSupply)),
                outfits=mapOf(1 to fleet)),mutableMapOf(),events)
            assertEquals(OutfitPosition(2487,1000),docked.outfits.getValue(1).position)
            assertEquals(initialSupply,docked.outfits.getValue(1).supplies)
            assertTrue(docked.outfits.getValue(1).strategy!!.awaitingDockSupply)

            val partial=advance(21600,docked.copy(bases=mapOf(1000 to depot.copy(supplies=200))),mutableMapOf(),events)
            assertEquals(initialSupply+200,partial.outfits.getValue(1).supplies)
            assertEquals(0,partial.bases.getValue(1000).supplies)
            assertTrue(partial.outfits.getValue(1).strategy!!.awaitingDockSupply)

            val stocked=advance(43200,partial.copy(bases=mapOf(1000 to depot.copy(supplies=4000))),mutableMapOf(),events)
            assertEquals(3000,stocked.outfits.getValue(1).supplies)
            assertEquals(1200+initialSupply,stocked.bases.getValue(1000).supplies)
            assertFalse(stocked.outfits.getValue(1).strategy!!.awaitingDockSupply)
            val repeated=advance(64800,stocked,mutableMapOf(),events)
            assertEquals(3000,repeated.outfits.getValue(1).supplies)
            assertEquals(stocked.bases,repeated.bases)
            assertTrue(events.isEmpty())
        }
    }

    @Test fun idleNpcRetriesResupplyAfterDockingAtAnEmptyDepot() {
        val initial = stocks()
        val depot = initial.bases.getValue(1000)
        val fleet = initial.outfits.getValue(1).copy(position = OutfitPosition(2487))
        val commanded = fleet.copy(id = 2, warehouseId = 11, commanderId = 10001,
            position = OutfitPosition(2487, 1000), activeShips = ActiveShipUnits(listOf(ActiveShipUnit(3, stack))),
            activeGround = null)
        val movements = mutableListOf<NpcStrategicMovement>()
        val docked = advance(0, initial.copy(bases = mapOf(1000 to depot.copy(supplies = 0)),
            outfits = mapOf(1 to fleet, 2 to commanded), warehouses = initial.warehouses +
                (11 to initial.warehouses.getValue(10).copy(id = 11, outfitId = 2)), nextShipId = 4), mutableMapOf(), movements)
        assertEquals(OutfitPosition(2487, 1000), docked.outfits.getValue(1).position)
        assertEquals(0, docked.outfits.getValue(1).supplies)
        assertTrue(docked.outfits.getValue(1).strategy!!.awaitingDockSupply)

        val production = PlanetProduction(galaxy, null, 0, PlanetProductionPolicy(suppliesPerDay = 4000,
            fleetUpkeepPerShip = 0), restored = docked)
        val produced = production.settle(1, 86400)
        assertEquals(4000, produced.bases.getValue(1000).supplies)
        val locked = NpcStrategy.advance(simulation.copy(gameMillis = 86400_000), galaxy, mutableMapOf(), produced,
            movements, warehouseBlocked = { _, id -> id == fleet.id })
        assertEquals(0, locked.outfits.getValue(1).supplies)
        assertEquals(4000, locked.bases.getValue(1000).supplies)
        assertTrue(locked.outfits.getValue(1).strategy!!.awaitingDockSupply)

        val stocked = advance(108000, locked, mutableMapOf(), movements)
        assertEquals(OutfitPosition(2487, 1000), stocked.outfits.getValue(1).position)
        assertNull(stocked.outfits.getValue(1).strategy?.destination)
        assertFalse(stocked.outfits.getValue(1).strategy!!.awaitingDockSupply)
        assertEquals(3000, stocked.outfits.getValue(1).supplies)
        assertEquals(1000, stocked.bases.getValue(1000).supplies)
        assertEquals(produced.outfits.getValue(2), stocked.outfits.getValue(2))
        val repeated = advance(129600, stocked, mutableMapOf(), movements)
        assertEquals(stocked.outfits.getValue(1).supplies, repeated.outfits.getValue(1).supplies)
        assertEquals(stocked.bases, repeated.bases)
        assertTrue(movements.isEmpty())

        val captured = produced.copy(bases = produced.bases.mapValues { (_, stock) ->
            stock.copy(owner = Allegiance.Country(CountryId(3)))
        })
        val stranded = advance(86400, captured, mutableMapOf(), movements)
        assertNull(stranded.outfits.getValue(1).strategy)
        assertEquals(captured.bases, stranded.bases)
        assertTrue(movements.isEmpty())

        val pilots = players()
        val reserved = advance(86400, produced, pilots, movements)
        assertEquals(2488, reserved.outfits.getValue(1).strategy?.destination)
        assertEquals(produced.bases, reserved.bases)
        val departed = advance(90000, reserved, pilots, movements)
        assertEquals(OutfitPosition(2488), departed.outfits.getValue(1).position)
        assertFalse(departed.outfits.getValue(1).strategy!!.awaitingDockSupply)
        assertEquals(2700, departed.outfits.getValue(1).supplies)
        assertEquals(1000, departed.bases.getValue(1000).supplies)
    }

    @Test fun localResupplyCannotMoveStationedShipsToAnotherBaseInTheSameGrid() {
        val alternate=galaxy.bases.single().copy(id=1001,name="Alternate")
        val world=Galaxy(galaxy.systems,galaxy.bases+alternate,galaxy.starts)
        val original=stocks()
        val depot=original.bases.getValue(1000)
        val fleet=original.outfits.getValue(1)
        val ship=fleet.activeShips!!.values.single().copy(posture=TacticalPosture(2487,TacticalShipMode.STATIONED,1000))
        val stationed=fleet.copy(position=OutfitPosition(2487),activeShips=ActiveShipUnits(listOf(ship)))
        val events=mutableListOf<NpcStrategicMovement>()
        for(strategy in listOf(null,NpcStrategicState(21600,2488,3600))) {
            val initial=original.copy(bases=mapOf(1000 to depot.copy(supplies=1),1001 to depot.copy(baseId=1001)),
                outfits=mapOf(1 to stationed.copy(strategy=strategy)))
            val settled=NpcStrategy.advance(simulation.copy(gameMillis=3600000),world,mutableMapOf(),initial,events)
            assertEquals(OutfitPosition(2487,1000),settled.outfits.getValue(1).position)
            assertEquals(1,settled.outfits.getValue(1).supplies)
            assertEquals(0,settled.bases.getValue(1000).supplies)
            assertEquals(initial.bases.getValue(1001),settled.bases.getValue(1001))
        }
        assertTrue(events.isEmpty())
    }

    @Test fun currentTacticalAnchorageBlocksNewAndPendingStrategicJumps() {
        val original=stocks()
        val fleet=OutfitSupplies.withBalances(original.outfits.getValue(1).copy(position=OutfitPosition(2487)),mapOf(1 to 2700))
        val events=mutableListOf<NpcStrategicMovement>()
        val ready=original.copy(outfits=mapOf(1 to fleet))
        val reserved=advance(0,ready,players(),events)
        val strategy=assertNotNull(reserved.outfits.getValue(1).strategy)
        assertEquals(2488,strategy.destination)
        assertEquals(OutfitPosition(2488),advance(3600,reserved,players(),mutableListOf()).outfits.getValue(1).position)

        for(mode in listOf(TacticalShipMode.ANCHORED,TacticalShipMode.STATIONED)) {
            val ship=fleet.activeShips!!.values.single().copy(posture=TacticalPosture(2487,mode,1000))
            val anchored=fleet.copy(activeShips=ActiveShipUnits(listOf(ship)))
            val initial=ready.copy(outfits=mapOf(1 to anchored))
            val considered=advance(0,initial,players(),events)
            assertNull(considered.outfits.getValue(1).strategy?.destination)
            assertEquals(anchored,considered.outfits.getValue(1).copy(strategy=null))

            val pending=initial.copy(outfits=mapOf(1 to anchored.copy(strategy=strategy)))
            val cancelled=advance(3600,pending,players(),events)
            assertNull(cancelled.outfits.getValue(1).strategy?.destination)
            assertEquals(anchored,cancelled.outfits.getValue(1).copy(strategy=null))
            assertEquals(initial.bases,cancelled.bases)
            assertTrue(events.isEmpty())
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

    @Test fun returningNpcRepairsSurvivorsAndPaysForRepairAndRefuelOnlyOnce() {
        val alternate = galaxy.bases.single().copy(id = 1001, grid = 2488, name = "Alternate")
        val world = Galaxy(galaxy.systems, galaxy.bases + alternate, galaxy.starts)
        val initial = stocks()
        val depot = initial.bases.getValue(1000)
        val damaged = TacticalLosses.apply(initial.outfits.getValue(1), 1, 40)
        val ship = damaged.activeShips!!.values.single()
        val cost = TacticalSupportOperations.repairCost(ship.stack.kind).coerceAtLeast(1)
        for (remote in listOf(false, true)) {
            val fleet = damaged.copy(position = OutfitPosition(2487))
            val baseId = if (remote) 1001 else 1000
            val bases = if (remote) mapOf(1000 to depot.copy(supplies = 0), 1001 to depot.copy(baseId = 1001)) else initial.bases
            val before = initial.copy(bases = bases, outfits = mapOf(1 to fleet))
            val events = mutableListOf<NpcStrategicMovement>()
            fun advanceWorld(at: Long, stocks: PlanetStocks) =
                NpcStrategy.advance(simulation.copy(gameMillis = at * 1000), world, mutableMapOf(), stocks, events)

            val decided = advanceWorld(0, before)
            if (remote) assertEquals(bases, decided.bases)
            val docked = if (remote) advanceWorld(3600, decided) else decided
            val arrived = docked.outfits.getValue(1)
            val repaired = arrived.activeShips!!.values.single()
            val cargo = minOf(OutfitSupplies.unitCapacity(ship), ship.boats * 10)
            val jump = if (remote) ship.boats else 0
            assertEquals(OutfitPosition(if (remote) 2488 else 2487, baseId), arrived.position)
            assertEquals(ship.id, repaired.id)
            assertEquals(ship.boats, repaired.boats)
            assertEquals(0, repaired.damaged)
            assertEquals(cargo, arrived.supplies)
            assertEquals(depot.supplies - cargo - ship.damaged * cost - jump, docked.bases.getValue(baseId).supplies)
            assertEquals(docked, advanceWorld(7200, docked))
        }
    }

    @Test fun dockedNpcRepairsOnlyAffordableDamageWithoutSpendingRoundTripFuel() {
        val initial = stocks()
        val depot = initial.bases.getValue(1000)
        val damaged = TacticalLosses.apply(initial.outfits.getValue(1), 1, 40)
        val ship = damaged.activeShips!!.values.single()
        val cost = TacticalSupportOperations.repairCost(ship.stack.kind).coerceAtLeast(1)
        val roundTrip = ship.boats * 2
        for ((stock, expectedRepairs) in listOf(0 to 0, roundTrip to 0, (roundTrip + cost * 2 + cost - 1) to 2)) {
            val before = initial.copy(bases = mapOf(1000 to depot.copy(supplies = stock)), outfits = mapOf(1 to damaged))
            val events = mutableListOf<NpcStrategicMovement>()
            val repaired = advance(0, before, mutableMapOf(), events)
            assertEquals(ship.damaged - expectedRepairs, repaired.outfits.getValue(1).activeShips!!.values.single().damaged)
            assertEquals(stock - expectedRepairs * cost, repaired.bases.getValue(1000).supplies)
            val nextDecision = advance(21600, repaired, mutableMapOf(), events)
            assertEquals(repaired.bases, nextDecision.bases)
            assertEquals(repaired.outfits.getValue(1).activeShips, nextDecision.outfits.getValue(1).activeShips)
            assertTrue(events.isEmpty())
        }
    }

    @Test fun dockedNpcReplacesLostBoatsFromItsOwnWarehouseOnceAndKeepsTheActiveUnit() {
        val initial = stocks()
        val full = initial.outfits.getValue(1)
        val ship = full.activeShips!!.values.single()
        val crewKind = UnitSpecifications.crews.first { it.power == 2 && it.type == 3 }.kind
        val crew = UnitStack(crewKind)
        val equipped = full.copy(
            units = UnitInventory(full.units.ships, full.units.troops +
                (crew to ReplacementBoats.crewRequired(full).toInt())),
            activeShips = ActiveShipUnits(listOf(ship.copy(boats = ship.boats - 12))))
        val warehouse = initial.warehouses.getValue(10)
        val readyWarehouse = warehouse.copy(spareShips = SpareShipBoats(mapOf(stack to 12)))
        val before = initial.copy(outfits = mapOf(1 to equipped), warehouses = mapOf(10 to readyWarehouse))

        val after = advance(0, before, mutableMapOf(), mutableListOf())
        val replaced = after.outfits.getValue(1).activeShips!!.values.single()
        assertEquals(ship, replaced)
        assertEquals(warehouse, after.warehouses.getValue(10))
        assertEquals(before.bases, after.bases)
        assertEquals(equipped.units, after.outfits.getValue(1).units)
        assertEquals(after, advance(1, after, mutableMapOf(), mutableListOf()))

        val uncrewed = before.copy(outfits = mapOf(1 to equipped.copy(units = full.units)))
        val skipped = advance(0, uncrewed, mutableMapOf(), mutableListOf())
        assertEquals(equipped.activeShips, skipped.outfits.getValue(1).activeShips)
        assertEquals(readyWarehouse, skipped.warehouses.getValue(10))

        val veteran = UnitStack(crewKind,1)
        val required = ReplacementBoats.crewRequired(full).toInt()
        val reserve = readyWarehouse.copy(units = UnitInventory(troops = mapOf(crew to required - 1, veteran to 2)))
        val crewReady = uncrewed.copy(warehouses = mapOf(10 to reserve))
        val replenished = advance(0, crewReady, mutableMapOf(), mutableListOf())
        assertEquals(ship, replenished.outfits.getValue(1).activeShips!!.values.single())
        assertEquals(full.units.troops + mapOf(crew to required - 1, veteran to 1), replenished.outfits.getValue(1).units.troops)
        assertEquals(mapOf(veteran to 1), replenished.warehouses.getValue(10).units.troops)
        assertTrue(replenished.warehouses.getValue(10).spareShips.values.isEmpty())
        assertEquals(crewReady.bases, replenished.bases)
        assertEquals(replenished, advance(1, replenished, mutableMapOf(), mutableListOf()))

        val insufficient = reserve.copy(units = UnitInventory(troops = mapOf(crew to required - 1)))
        val rejected = advance(0, crewReady.copy(warehouses = mapOf(10 to insufficient)), mutableMapOf(), mutableListOf())
        assertEquals(equipped.activeShips, rejected.outfits.getValue(1).activeShips)
        assertEquals(full.units, rejected.outfits.getValue(1).units)
        assertEquals(insufficient, rejected.warehouses.getValue(10))

        val locked = NpcStrategy.advance(simulation.copy(gameMillis = 0), galaxy, mutableMapOf(), crewReady,
            mutableListOf(), warehouseBlocked = { _, outfitId -> outfitId == equipped.id })
        assertEquals(equipped.activeShips, locked.outfits.getValue(1).activeShips)
        assertEquals(reserve, locked.warehouses.getValue(10))
    }

    @Test fun dockedNpcReplacesLossesWithNewProductionWhenLogisticsSeatIsVacant() {
        val base = galaxy.bases.single().copy(id = 1049, name = "オーディン")
        val systems = galaxy.systems.map { if (it.grid == base.grid) it.copy(name = "ヴァルハラ") else it }
        val world = Galaxy(systems, listOf(base), galaxy.starts)
        val initial = stocks()
        val fleet = initial.outfits.getValue(1)
        val ship = fleet.activeShips!!.values.single()
        val damaged = fleet.copy(baseId = base.id, position = OutfitPosition(base.grid, base.id),
            activeShips = ActiveShipUnits(listOf(ship.copy(boats = ship.boats - 12))), activeGround = null)
        val opening = initial.copy(
            bases = mapOf(base.id to initial.bases.getValue(1000).copy(baseId = base.id, food = 10_000_000)),
            outfits = mapOf(fleet.id to damaged),
            warehouses = mapOf(10 to initial.warehouses.getValue(10).copy(baseId = base.id)))
        val now = 5 * Justice.DAY
        val produced = PlanetProduction(world, null, 0, PlanetProductionPolicy(), restored = opening).settle(1, now)
        val depot = produced.bases.getValue(base.id)
        val crew = UnitStack(3)
        val required = ReplacementBoats.crewRequired(fleet).toInt()
        assertTrue(depot.units.ships.getValue(stack) > 0)
        assertTrue(depot.units.troops.getValue(crew) >= required)
        val session = simulation.copy(gameMillis = now * 1000)
        fun decide(source: PlanetStocks, blocked: Boolean = false) = NpcStrategy.advance(session, world, players(), source,
            mutableListOf(), warehouseBlocked = { _, _ -> blocked })

        val restored = decide(produced)
        assertEquals(ship, restored.outfits.getValue(fleet.id).activeShips!!.values.single())
        assertEquals(required, restored.outfits.getValue(fleet.id).units.troops[crew])
        assertEquals((depot.units.ships[stack] ?: 0) - 1, restored.bases.getValue(base.id).units.ships[stack] ?: 0)
        assertEquals((depot.units.troops[crew] ?: 0) - required, restored.bases.getValue(base.id).units.troops[crew] ?: 0)
        assertEquals(depot.units.work, restored.bases.getValue(base.id).units.work)
        assertEquals(ship.boats - 12, restored.warehouses.getValue(10).spareShips.values[stack])
        assertEquals(restored, decide(restored))

        val staffed = produced.copy(logisticsOfficers = mapOf(10002 to LogisticsOffice.EMPIRE_FIRST))
        assertTrue(LogisticsOfficePolicy(world).staffed(staffed, fleet.country, fleet.kind))
        val shortCrew = produced.copy(bases = mapOf(base.id to depot.copy(units = UnitInventory(
            depot.units.ships, depot.units.troops - crew, depot.units.work))))
        for ((source, blocked) in listOf(staffed to false, shortCrew to false, produced to true)) {
            val skipped = decide(source, blocked)
            assertEquals(damaged.activeShips, skipped.outfits.getValue(fleet.id).activeShips)
            assertEquals(source.bases, skipped.bases)
            assertEquals(source.warehouses, skipped.warehouses)
        }
    }

    @Test fun destroyedUncommandedFleetCanCommissionFromHomeStockWithANewUnitId() {
        val initial = stocks()
        val fleet = initial.outfits.getValue(1)
        val ship = fleet.activeShips!!.values.single()
        val fielded = fleet.copy(position = OutfitPosition(2488), units = UnitInventory(fleet.units.ships), activeGround = null)
        val sunk = TacticalLosses.apply(fielded, ship.id, ship.boats)
        val base = initial.bases.getValue(fleet.baseId)
        val crew = UnitStack(3)
        val required = ReplacementBoats.crewRequired(fleet).toInt()
        val available = base.copy(units = UnitInventory(mapOf(stack to 1), mapOf(crew to required)))
        val lost = initial.copy(outfits = mapOf(fleet.id to sunk))
        val events = mutableListOf<NpcStrategicMovement>()

        val waiting = advance(0, lost, mutableMapOf(), events)
        assertEquals(sunk.activeShips, waiting.outfits.getValue(fleet.id).activeShips)
        assertEquals(2488, waiting.outfits.getValue(fleet.id).position!!.grid)
        assertEquals(initial.nextShipId, waiting.nextShipId)
        val ready = waiting.copy(bases = mapOf(base.baseId to available))
        assertEquals(ready, advance(21599, ready, mutableMapOf(), events))

        val after = advance(21600, ready, mutableMapOf(), events)
        val reformed = after.outfits.getValue(fleet.id)
        assertEquals(OutfitPosition(2487, base.baseId), reformed.position)
        assertEquals(listOf(ship.copy(id = initial.nextShipId)), reformed.activeShips!!.values)
        assertEquals(required, reformed.units.troops[crew])
        assertEquals(UnitInventory(), after.bases.getValue(base.baseId).units)
        assertEquals(initial.warehouses, after.warehouses)
        assertEquals(initial.nextShipId + 1, after.nextShipId)
        assertEquals(after, advance(21600, after, mutableMapOf(), events))
        assertTrue(events.isEmpty())

        val crewShort = ready.copy(bases = mapOf(base.baseId to available.copy(units = UnitInventory(mapOf(stack to 1)))))
        val occupied = ready.copy(bases = mapOf(base.baseId to available.copy(owner = Allegiance.Country(Power.ALLIANCE.country))))
        val commanded = ready.copy(outfits = mapOf(fleet.id to sunk.copy(commanderId = 10001)))
        val garrison = ready.copy(outfits = mapOf(fleet.id to TacticalLosses.apply(fleet, ship.id, ship.boats)))
        for (blocked in listOf(crewShort, occupied, commanded, garrison)) {
            val untouched = advance(21600, blocked, mutableMapOf(), events)
            assertEquals(blocked.bases, untouched.bases)
            assertEquals(blocked.warehouses, untouched.warehouses)
            assertEquals(blocked.nextShipId, untouched.nextShipId)
            assertEquals(blocked.outfits.getValue(fleet.id).activeShips, untouched.outfits.getValue(fleet.id).activeShips)
            assertEquals(blocked.outfits.getValue(fleet.id).activeGround, untouched.outfits.getValue(fleet.id).activeGround)
        }
    }

    @Test fun depotRepairSkipsGuardShipsAndHumanCommandedOutfits() {
        val initial = stocks()
        val damaged = TacticalLosses.apply(initial.outfits.getValue(1), 1, 40)
        val human = damaged.copy(commanderId = 10001)
        val option = GuardShips.options.first { it.power == Power.EMPIRE.country.value }
        val ship = damaged.activeShips!!.values.single().copy(stack = UnitStack(option.baseKind))
        val ships = ActiveShipUnits(listOf(ship))
        val guard = damaged.copy(kind = OutfitKind.PATROL, activeShips = ships, guard = mapOf(ship.id to option.index),
            units = UnitInventory(ships.quantities(), damaged.units.troops))
        for (outfit in listOf(human, guard)) {
            val warehouse = initial.warehouses.getValue(10)
            val before = initial.copy(outfits = mapOf(1 to outfit), warehouses = mapOf(10 to if (outfit == guard)
                warehouse.copy(kind = OutfitKind.PATROL, outfitId = 0) else warehouse))
            val after = advance(0, before, mutableMapOf(), mutableListOf())
            assertEquals(outfit.activeShips, after.outfits.getValue(1).activeShips)
            assertEquals(before.bases, after.bases)
        }
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
