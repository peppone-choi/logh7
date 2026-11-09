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
    @Test fun absentOrderExecutesBetweenPeriodicTicksAfterRestart() = runTest {
        val rig=Rig(this,testScheduler,resumedAt=149375,orderDeadline=true)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            advanceTimeBy(624);runCurrent()
            assertEquals(1000,rig.players.getValue("recipient").mcp)
            advanceTimeBy(1);runCurrent()
            val snapshot=rig.engine.snapshot()
            assertEquals(rig.start+ProposalActions.ABSENT_ORDER_SECONDS,snapshot.session!!.gameSeconds)
            assertEquals(Proposals.APPROVE,snapshot.planetStocks!!.social.proposals.mails.single().reply)
            assertEquals(960,rig.players.getValue("recipient").mcp)
            assertTrue(OrganizationalOfficePolicy(rig.galaxy).held(snapshot.planetStocks,10001).none {it.card==19})
        } finally {rig.engine.drainAndClose()}
    }
    @Test fun proposalExpiryRefundsBetweenPeriodicTicks() = runTest {
        val rig=Rig(this,testScheduler,mailDeadline=true)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            advanceTimeBy(624);runCurrent()
            assertEquals(960,rig.players.getValue("pilot").mcp)
            advanceTimeBy(1);runCurrent()
            assertEquals(rig.start+15,rig.engine.snapshot().session!!.gameSeconds)
            assertEquals(1000,rig.players.getValue("pilot").mcp)
            assertTrue(rig.engine.snapshot().planetStocks!!.social.proposals.mails.isEmpty())
        } finally {rig.engine.drainAndClose()}
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
        mailDeadline:Boolean=false,orderDeadline:Boolean=false) {
        private val rules=SessionRules.load()
        val start=rules.startSeconds
        var fail=false
        var players=mapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2488,1000,1000,1000,
            cpPaused=true,hull=FlagshipHull(0,start),energy=TacticalEnergy(listOf(100)+List(10){0}),
            motion=TacticalMotion(2488,start,TacticalPose(TacticalPoint(0f,0f),0f),1f,0f,
                listOf(TacticalPoint(0f,10f)),turnRate=1f).let {if(postureDeadline)it.copy(velocity=0f) else it},
            posture=if(postureDeadline)TacticalPosture(2488,order=TacticalPostureOrder(10000,TacticalShipMode.BATTLE,0,start,start+15)) else null))
        private var stocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,3,0,0,0,0)),deploymentInitialized=true)
        private val homeGrid=if(orderDeadline)2487 else 2488
        val galaxy=Galaxy(listOf(GalaxySystem(3,homeGrid,4,"Home",if(orderDeadline)2 else 3)),
            listOf(GalaxyBase(if(orderDeadline)1049 else 1000,homeGrid,80,0,
                if(orderDeadline)Capital.EMPIRE.baseName else "Home",0,20f,86400,0,0f,2f)),
            Power.entries.associateWith {homeGrid})
        init {
            if(mailDeadline) {
                players=mapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2488,1000,960,1000))
                val mail=ProposalMail(1,10000,10001,17,19,ProposalActions.RESIGNATION,false,start,start+15,mcp=40)
                stocks=stocks.copy(social=stocks.social.copy(proposals=ProposalState(listOf(mail))))
            }
            if(orderDeadline) {
                players=mapOf("pilot" to Navigator(1,10000,Power.EMPIRE,homeGrid,1000,960,1000),
                    "recipient" to Navigator(1,10001,Power.EMPIRE,homeGrid,1000,1000,1000))
                val mail=ProposalMail(1,10000,10001,17,19,ProposalActions.RESIGNATION,true,start,
                    start+Proposals.LIFETIME,mcp=40)
                stocks=OrganizationalOfficePolicy(galaxy).set(stocks.copy(
                    bases=mapOf(1049 to PlanetStock(1049,2,0,0,0,0)),
                    social=stocks.social.copy(proposals=ProposalState(listOf(mail)))),
                    OfficeHolding(19,1049,10001))
            }
        }
        private val durability=resumedAt?.let {elapsed->object:SessionDurability {
            private var saved=DurableWorldState(SessionSimulation(rules).apply {
                if(orderDeadline) {
                    join("recipient",Power.EMPIRE,false,"before-restart")
                    disconnect("recipient","before-restart")
                }
                advance(elapsed)
            }.exportState(),elapsed)
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
