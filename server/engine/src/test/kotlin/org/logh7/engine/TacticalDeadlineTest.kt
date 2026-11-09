package org.logh7.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Deadline ticks retain durable publication order and the ordinary economy cadence. */
@OptIn(ExperimentalCoroutinesApi::class)
class TacticalDeadlineTest {
    @Test fun restoredPirateCounterattackFiresBetweenRegularTicks() = runTest {
        val rig=Rig(this,testScheduler,pirateDeadline=true)
        val impacts=mutableListOf<TacticalImpact>()
        rig.engine.observeCombat(impacts::add)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            fun lastVolley()=rig.engine.snapshot().planetStocks!!.social.pirates.fleets.getValue(1).firedAt
            advanceTimeBy(833);runCurrent()
            assertEquals(rig.start,lastVolley())
            assertTrue(impacts.isEmpty())
            advanceTimeBy(1);runCurrent()
            assertEquals(rig.start+20,lastVolley())
            assertEquals(900_001,impacts.single().source)
            assertEquals(0L,rig.engine.snapshot().session!!.strategyTicks)
        } finally {rig.engine.drainAndClose()}
    }
    @Test fun restoredFleetShotFiresBetweenPeriodicTicks() = runTest {
        val rig=Rig(this,testScheduler,fleetFireDeadline=true)
        val impacts=mutableListOf<TacticalImpact>()
        fun ship()=rig.engine.snapshot().planetStocks!!.outfits.getValue(1).activeShips!!.values.single()
        rig.engine.observeCombat(impacts::add).use {
            try {
                rig.engine.submit(WorldCommand.Probe);runCurrent()
                advanceTimeBy(624);runCurrent()
                assertTrue(impacts.isEmpty())
                val before=ship()
                assertNotNull(before.shot)
                advanceTimeBy(1);runCurrent()
                assertEquals(rig.start+15,rig.engine.snapshot().session!!.gameSeconds)
                val hit=impacts.single()
                assertEquals(1,hit.source)
                assertEquals(1010001,hit.hit.target)
                val after=ship()
                assertNull(after.shot)
                assertNotNull(after.reload)
                assertTrue(after.supplies!!<before.supplies!!)
                advanceTimeBy(375);runCurrent()
                assertEquals(1,impacts.size)
            } finally {rig.engine.drainAndClose()}
        }
    }
    @Test fun restoredFlagshipShotFiresBetweenPeriodicTicks() = runTest {
        val rig=Rig(this,testScheduler,fireDeadline=true)
        val impacts=mutableListOf<TacticalImpact>()
        rig.engine.observeCombat(impacts::add).use {
            try {
                rig.engine.submit(WorldCommand.Probe);runCurrent()
                advanceTimeBy(624);runCurrent()
                assertTrue(impacts.isEmpty())
                assertNotNull(rig.players.getValue("pilot").fire!!.pending)
                advanceTimeBy(1);runCurrent()
                assertEquals(rig.start+15,rig.engine.snapshot().session!!.gameSeconds)
                assertEquals(1010001,impacts.single().hit.target)
                assertNull(rig.players.getValue("pilot").fire!!.pending)
                assertEquals(0,rig.players.getValue("pilot").charge!!.beam)
            } finally {rig.engine.drainAndClose()}
        }
    }
    @Test fun restoredSupplySchedulesItsOwnCompletionBetweenPeriodicTicks() = runTest {
        val rig=Rig(this,testScheduler,supportDeadline=true)
        val results=mutableListOf<TacticalSupportResult>()
        rig.engine.observeSupport(results::add).use {
            try {
                rig.engine.submit(WorldCommand.Probe);runCurrent()
                advanceTimeBy(624);runCurrent()
                assertTrue(results.isEmpty())
                assertEquals(9500,rig.players.getValue("pilot").supplies)
                advanceTimeBy(1);runCurrent()
                assertEquals(rig.start+15,rig.engine.snapshot().session!!.gameSeconds)
                assertFalse(results.single().cancelled)
                assertEquals(10000,rig.players.getValue("pilot").supplies)
                assertEquals(0,rig.engine.snapshot().planetStocks!!.outfits.getValue(1).supplies)
            } finally {rig.engine.drainAndClose()}
        }
    }
    @Test fun restoredPostureAloneSchedulesCompletionBetweenPeriodicTicks() = runTest {
        val rig=Rig(this,testScheduler,postureDeadline=true)
        val events=mutableListOf<TacticalPostureResult>();rig.engine.observePostures(events::add)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            advanceTimeBy(624);runCurrent();assertTrue(events.isEmpty())
            assertNotNull(rig.players.getValue("pilot").posture!!.order)
            advanceTimeBy(1);runCurrent()
            assertEquals(listOf(rig.start+15),events.map {it.seconds})
            assertEquals(TacticalShipMode.BATTLE,rig.players.getValue("pilot").posture!!.mode)
            assertNull(rig.players.getValue("pilot").posture!!.order)
        } finally {rig.engine.drainAndClose()}
    }
    @Test fun completionBetweenRegularTicksIsCommittedAtItsFirstGameSecond() = runTest {
        val rig=Rig(this,testScheduler)
        val moves=mutableListOf<TacticalMovement>()
        rig.engine.observeMovements(moves::add)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            advanceTimeBy(624);runCurrent()
            assertEquals(1f,rig.players.getValue("pilot").motion!!.velocity)
            assertTrue(moves.isEmpty())
            advanceTimeBy(1);runCurrent()
            assertEquals(0f,rig.players.getValue("pilot").motion!!.velocity)
            assertEquals(listOf(15L),moves.map {it.seconds-rig.start})
            assertEquals(-1,moves.single().waypoint)
            assertEquals(0L,rig.engine.snapshot().session!!.strategyTicks)
            assertEquals(rig.players,rig.engine.snapshot().navigators)
        } finally {rig.engine.drainAndClose()}
    }
    @Test fun unrelatedPublicationsCannotPostponeAnExistingDeadline() = runTest {
        val rig=Rig(this,testScheduler)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            repeat(12) {advanceTimeBy(50);rig.engine.submit(WorldCommand.Probe);runCurrent()}
            advanceTimeBy(25);runCurrent()
            assertEquals(0f,rig.players.getValue("pilot").motion!!.velocity)
            advanceTimeBy(375);runCurrent()
            assertEquals(1L,rig.engine.snapshot().session!!.strategyTicks)
        } finally {rig.engine.drainAndClose()}
    }
    @Test fun deadlineStorageFailurePublishesNeitherCompletionNorMovement() = runTest {
        val rig=Rig(this,testScheduler)
        val moves=mutableListOf<TacticalMovement>()
        rig.engine.observeMovements(moves::add)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent();rig.fail=true
            advanceTimeBy(625);runCurrent()
            assertEquals(EngineHealth.STORAGE_FAILED,rig.engine.health())
            assertEquals(1f,rig.players.getValue("pilot").motion!!.velocity)
            assertEquals(1f,rig.engine.snapshot().navigators.getValue("pilot").motion!!.velocity)
            assertTrue(moves.isEmpty())
            advanceTimeBy(1000);runCurrent()
            assertTrue(moves.isEmpty())
        } finally {rig.engine.drainAndClose()}
    }
    @Test fun restoredSessionUsesItsElapsedClockForTheRemainingDeadline() = runTest {
        val rig=Rig(this,testScheduler,resumedAt=500)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            advanceTimeBy(124);runCurrent()
            assertEquals(1f,rig.players.getValue("pilot").motion!!.velocity)
            advanceTimeBy(1);runCurrent()
            assertEquals(0f,rig.players.getValue("pilot").motion!!.velocity)
            assertEquals(rig.start+15,rig.engine.snapshot().session!!.gameSeconds)
        } finally {rig.engine.drainAndClose()}
    }
    @Test fun clockQueryCommitsBetweenPeriodicTicksAndRefusesAStorageFailure() = runTest {
        val rig=Rig(this,testScheduler)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            advanceTimeBy(100);runCurrent()
            assertEquals(rig.start,rig.engine.snapshot().session!!.gameSeconds)
            val sample=async {rig.engine.synchronizeTime()};runCurrent()
            assertEquals(rig.start+2,sample.await().session!!.gameSeconds)
            rig.fail=true
            // Crossing the pending completion forces the world transaction to fail.
            advanceTimeBy(525);runCurrent()
            assertFailsWith<IllegalStateException>{rig.engine.synchronizeTime()}
        } finally {rig.engine.drainAndClose()}
    }
    private class Rig(scope:CoroutineScope,scheduler:TestCoroutineScheduler,resumedAt:Long?=null,postureDeadline:Boolean=false,
        supportDeadline:Boolean=false,fireDeadline:Boolean=false,fleetFireDeadline:Boolean=false,pirateDeadline:Boolean=false) {
        private val rules=SessionRules.load()
        val start=rules.startSeconds
        var fail=false
        var players=mapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2488,1000,1000,1000,
            cpPaused=true,hull=FlagshipHull(0,start),energy=TacticalEnergy(listOf(100)+List(10){0}),
            motion=TacticalMotion(2488,start,TacticalPose(TacticalPoint(0f,0f),0f),1f,0f,
                listOf(TacticalPoint(0f,10f)),turnRate=1f).let {if(postureDeadline)it.copy(velocity=0f) else it},
            posture=if(postureDeadline)TacticalPosture(2488,order=TacticalPostureOrder(10000,TacticalShipMode.BATTLE,0,start,start+15)) else null))
        private var stocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,3,0,0,0,0)),deploymentInitialized=true)
        init {
            if(fireDeadline || fleetFireDeadline) {
                fun at(y:Float):TacticalMotion {
                    val point=TacticalPoint(180f,y)
                    return TacticalMotion(2488,start,TacticalPose(point,0f),0f,0f,listOf(point))
                }
                val pilot=players.getValue("pilot").copy(motion=at(-97f),charge=TacticalCharge(start,24000),
                    fire=if(fireDeadline)TacticalFire(1,TacticalPendingFire(1,2488,0,1010001,start,start+15)) else null)
                val enemy=Navigator(1,10001,Power.ALLIANCE,2488,1000,1000,1000,
                    cpPaused=true,hull=FlagshipHull(89,start),motion=at(-95f))
                players=mapOf("pilot" to pilot,"enemy" to enemy)
                if(fleetFireDeadline) {
                    val stack=UnitStack(32)
                    val ship=ActiveShipUnit(1,stack,tactical=NpcTacticalState(at(-96.5f),start,start),supplies=5000,
                        shot=TacticalUnitShot(10000,TacticalWeapon.GUN,enemy.unitId,start,start+15))
                    val outfit=OutfitStock(1,2,OutfitKind.FLEET,1000,10,commanderId=10000,
                        units=UnitInventory(mapOf(stack to 1)),supplies=5000,activeShips=ActiveShipUnits(listOf(ship)),
                        position=OutfitPosition(2488))
                    stocks=stocks.copy(outfits=mapOf(1 to outfit),nextShipId=2,
                        warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)))
                }
            }
            if(pirateDeadline) {
                val pilot=players.getValue("pilot")
                val point=TacticalPoint(0f,-9f)
                val motion=TacticalMotion(2488,start,TacticalPose(point,0f),0f,0f,listOf(point))
                players=mapOf("pilot" to pilot.copy(motion=motion))
                val fleet=PirateFleet(1,2488,start,listOf(PirateUnit(900_001,300)),firedAt=start)
                stocks=stocks.copy(social=stocks.social.copy(pirates=PirateState(mapOf(1 to fleet),2)))
            }
            if(supportDeadline) {
                val pilot=players.getValue("pilot")
                val pose=pilot.motion!!.copy(velocity=0f,waypoints=listOf(TacticalPoint(0f,0f)))
                players=mapOf("pilot" to pilot.copy(motion=pose,supplies=9500))
                val stack=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==2 && it.name.startsWith("輸送艦")}.kind)
                val ship=ActiveShipUnit(1,stack,tactical=NpcTacticalState(pose,start,start),supplies=500,
                    support=TacticalSupportOrder(TacticalSupportAction.SUPPLY,10000,pilot.unitId,2488,start,start+15))
                val outfit=OutfitStock(1,2,OutfitKind.FLEET,1000,10,commanderId=10000,
                    units=UnitInventory(mapOf(stack to 1)),supplies=500,activeShips=ActiveShipUnits(listOf(ship)),
                    position=OutfitPosition(2488))
                stocks=stocks.copy(outfits=mapOf(1 to outfit),nextShipId=2,
                    warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)))
            }
        }
        private val galaxy=Galaxy(listOf(GalaxySystem(3,2488,4,"Home",3)),
            listOf(GalaxyBase(1000,2488,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2488})
        private val durability=resumedAt?.let {elapsed->object:SessionDurability {
            private var saved=DurableWorldState(SessionSimulation(rules).apply {advance(elapsed)}.exportState(),elapsed)
            override fun load()=saved
            override fun commit(action:SessionAction,transition:SessionTransition){saved=transition.state}
            override fun close() {}
        }}
        val engine=WorldEngine(scope,rules,durability=durability,monotonicMillis={scheduler.currentTime},galaxy=galaxy,
            navigationStore=object:NavigationStore {
                override fun load()=players
                override fun save(next:Map<String,Navigator>){players=next}
            },stockStore=object:PlanetStockStore {
                override fun load()=stocks
                override fun save(next:PlanetStocks){stocks=next}
            },worldStateCommitter=WorldStateCommitter {next,inventory->
                if(fail)throw java.io.IOException("deadline save failed")
                players=next;stocks=checkNotNull(inventory)
            },npcPolicy=NpcTacticalPolicy(enabled=false))
    }
}
