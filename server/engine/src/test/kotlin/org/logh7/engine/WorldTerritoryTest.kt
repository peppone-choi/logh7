package org.logh7.engine

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.*

class WorldTerritoryTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy((0..7).map {i->GalaxySystem(3+i,if(i<4)2487+i else 2015+i-4,4,"System$i",if(i<4)2 else 3)},
        (0..7).map {i->GalaxyBase(1000+i,if(i<4)2487+i else 2015+i-4,80,0,
            when(i){0->"オーディン";4->"ハイネセン";else->"Planet$i"},0,20f,86400,0,0f,2f)},
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private fun stocks()=PlanetStocks(1,0,galaxy.bases.associate {base->
        base.id to PlanetStock(base.id,galaxy.conditions.getValue(base.id).power,0,100,100,100)
    },deploymentInitialized=true)
    private fun capture(original:PlanetStocks,id:Int)=original.copy(bases=original.bases+
        (id to original.bases.getValue(id).copy(power=2)))

    @Test fun capitalOwnerIsTheDefeatedPowerAndCapitalTakesPrecedenceOverTerritoryReduction() {
        val territory=WorldTerritory(galaxy);assertNull(territory.ending(stocks()))
        assertEquals(mapOf(Power.EMPIRE to 4,Power.ALLIANCE to 4),territory.controlled(stocks()))
        val action=territory.ending(capture(stocks(),1004))!!
        assertEquals(setOf(Power.ALLIANCE),action.capitals)
        val end=SessionSimulation(rules).updateTerritory(action.systems,action.capitals).single() as WorldEvent.Ended
        assertEquals(EndReason.CAPITAL_CAPTURED,end.end.reason)
        assertEquals(setOf(Power.ALLIANCE),end.end.affected)
    }
    @Test fun reducedRegularSystemsUseCurrentOwnershipWithoutCountingFleetsOrNeutralAndInsurgentBases() {
        val territory=WorldTerritory(galaxy)
        val result=territory.ending(capture(stocks(),1005))!!
        assertTrue(result.capitals.isEmpty());assertEquals(3,result.systems[Power.ALLIANCE])
        for((power,camp) in listOf(0 to 0,3 to 1)) {
            val original=stocks();val changed=original.copy(bases=original.bases+
                (1005 to original.bases.getValue(1005).copy(power=power,camp=camp)))
            assertEquals(3,territory.controlled(changed)[Power.ALLIANCE])
        }
    }
    @Test fun mixedSystemIsControlledByNeitherSideUntilEveryBaseBelongsToTheSameRegularCamp() {
        val extra=galaxy.bases.last().copy(id=2000)
        val world=Galaxy(galaxy.systems,galaxy.bases+extra,galaxy.starts)
        val original=stocks().copy(bases=stocks().bases+(2000 to PlanetStock(2000,2,0,100,100,100)))
        val territory=WorldTerritory(world)
        assertEquals(3,territory.controlled(original)[Power.ALLIANCE])
        assertEquals(4,territory.controlled(original)[Power.EMPIRE])
        val unified=capture(original,1007)
        assertEquals(5,territory.controlled(unified)[Power.EMPIRE])
    }
    @Test fun smallLocalScenarioDoesNotEndForItsInitialSizeButStillRecognizesCapitalCapture() {
        val small=Galaxy(galaxy.systems.filter {it.grid in galaxy.starts.values},
            galaxy.bases.filter {Capital.of(it)!=null},galaxy.starts)
        val original=stocks().copy(bases=stocks().bases.filterKeys {it in setOf(1000,1004)})
        val territory=WorldTerritory(small)
        assertNull(territory.ending(original))
        assertEquals(setOf(Power.ALLIANCE),territory.ending(capture(original,1004))!!.capitals)
    }
    private class SessionStore(val rules:SessionRules):SessionDurability {
        var value=DurableWorldState(SessionSimulation(rules).exportState())
        var failTerritory=false
        val actions=mutableListOf<SessionAction>()
        override fun load()=value
        override fun commit(action:SessionAction,transition:SessionTransition) {
            if(failTerritory && action is SessionAction.Territory)throw IOException("end journal unavailable")
            value=transition.state;actions+=action
        }
        override fun close() {}
    }
    private class StockStore(var value:PlanetStocks):PlanetStockStore {
        override fun load()=value
        override fun save(stocks:PlanetStocks){value=stocks}
    }
    @Test fun committedCapitalIsReconciledBeforeAdmissionAndOnlyOnce()=runBlocking {
        val store=SessionStore(rules);val inventory=StockStore(capture(stocks(),1004))
        val events=mutableListOf<WorldEvent>()
        val engine=WorldEngine(this,rules,ticking=false,durability=store,galaxy=galaxy,stockStore=inventory,onEvent=events::add)
        val joined=CompletableDeferred<Admission>()
        engine.submit(WorldCommand.Join("late",Power.EMPIRE,false,joined,"connection",1))
        assertEquals(Admission.ENDED,joined.await())
        assertEquals(EndReason.CAPITAL_CAPTURED,engine.snapshot().session!!.ended!!.reason)
        engine.submit(WorldCommand.AdvanceTo(1000));engine.drainAndClose()
        assertEquals(1,store.actions.filterIsInstance<SessionAction.Territory>().size)
        assertEquals(1,events.filterIsInstance<WorldEvent.Ended>().size)
        assertTrue(engine.snapshot().navigators.isEmpty())
    }
    @Test fun failedEndingJournalFencesAdmissionsAndRecoveringTheWorldRetriesTheMissingDecision()=runBlocking {
        val store=SessionStore(rules).also {it.failTerritory=true}
        val inventory=StockStore(capture(stocks(),1004));val events=mutableListOf<WorldEvent>()
        var engine=WorldEngine(this,rules,ticking=false,durability=store,galaxy=galaxy,stockStore=inventory,onEvent=events::add)
        val join=CompletableDeferred<Admission>()
        engine.submit(WorldCommand.Join("late",Power.EMPIRE,false,join,"connection",1))
        assertFailsWith<IOException> {join.await()}
        assertEquals(EngineHealth.STORAGE_FAILED,engine.health());assertNull(store.value.session.ended)
        assertTrue(events.isEmpty());engine.drainAndClose()
        store.failTerritory=false
        engine=WorldEngine(this,rules,ticking=false,durability=store,galaxy=galaxy,stockStore=inventory,onEvent=events::add)
        val retry=CompletableDeferred<Admission>()
        engine.submit(WorldCommand.Join("late",Power.EMPIRE,false,retry,"new",1))
        assertEquals(Admission.ENDED,retry.await());engine.drainAndClose()
        assertEquals(setOf(Power.ALLIANCE),store.value.session.ended!!.affected)
        assertEquals(1,store.actions.filterIsInstance<SessionAction.Territory>().size)
        assertEquals(1,events.filterIsInstance<WorldEvent.Ended>().size)
    }
    @Test fun liveOccupationEndsOnTheSameActorPassWithoutOverwritingThePublishedResult()=runBlocking {
        val troop=ActiveGroundUnits(listOf(ActiveGroundUnit(1,UnitStack(0),base=1004)))
        val outfit=OutfitStock(1,2,OutfitKind.GROUND,1000,101,units=UnitInventory(troops=troop.quantities()),activeGround=troop)
        val original=stocks().copy(outfits=mapOf(1 to outfit),warehouses=mapOf(101 to OutfitWarehouse(101,2,OutfitKind.PATROL,1000)),nextShipId=2)
        val inventory=StockStore(original);val store=SessionStore(rules);var saved=0
        val commit=WorldStateCommitter {_,next->inventory.value=checkNotNull(next);saved++}
        val engine=WorldEngine(this,rules,ticking=false,durability=store,galaxy=galaxy,stockStore=inventory,
            worldStateCommitter=commit)
        engine.submit(WorldCommand.AdvanceTo(0))
        val joined=CompletableDeferred<Admission>()
        engine.submit(WorldCommand.Join("late",Power.EMPIRE,false,joined,"connection",1))
        assertEquals(Admission.ENDED,joined.await());engine.drainAndClose()
        assertEquals(2,inventory.value.bases.getValue(1004).power)
        assertEquals(EndReason.CAPITAL_CAPTURED,engine.snapshot().session!!.ended!!.reason)
        assertEquals(1,saved)
        assertTrue(store.actions[0] is SessionAction.Advance)
        assertTrue(store.actions[1] is SessionAction.Territory)
    }
    @Test fun inMemorySessionUsesTheSameTerritoryRuleAndRestartDelay()=runBlocking {
        val engine=WorldEngine(this,rules,ticking=false,galaxy=galaxy,stockStore=StockStore(capture(stocks(),1005)))
        val join=CompletableDeferred<Admission>()
        engine.submit(WorldCommand.Join("late",Power.EMPIRE,false,join,"connection",1))
        assertEquals(Admission.ENDED,join.await())
        assertEquals(EndReason.SYSTEMS_REDUCED,engine.snapshot().session!!.ended!!.reason)
        engine.submit(WorldCommand.AdvanceTo(rules.restartDelayRealMillis));engine.drainAndClose()
        assertEquals(2,engine.snapshot().session!!.generation);assertNull(engine.snapshot().session!!.ended)
    }
}
