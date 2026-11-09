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
    @Test fun restoredCapitalOccupationTransfersPowerAtItsDeadline() = runTest {
        val hold=CoupActions.CAPITAL_DAYS*Justice.DAY
        val rig=Rig(this,testScheduler,resumedAt=hold*1000/SessionRules.load().speed,coupDeadline=true)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            fun stock()=rig.engine.snapshot().planetStocks!!
            advanceTimeBy(624);runCurrent()
            assertEquals(1,stock().bases.getValue(1049).camp)
            assertEquals(15L,stock().social.coup.plots.getValue(10000).capitalSince)
            advanceTimeBy(1);runCurrent()
            assertEquals(hold+15,rig.engine.snapshot().session!!.gameSeconds)
            assertEquals(0,stock().bases.getValue(1049).camp)
            assertTrue(stock().social.coup.plots.isEmpty())
            assertTrue(stock().strategic.offices.any {it.card==1 && it.character==10000 && it.seat==1049})
        } finally {rig.engine.drainAndClose()}
    }

    @Test fun restoredPirateOccupationReducesPublicOrderOnceAtItsDailyDeadline() = runTest {
        val day=Justice.DAY
        val rig=Rig(this,testScheduler,resumedAt=day*1000/SessionRules.load().speed,pirateHarmDeadline=true)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            fun peace()=rig.engine.snapshot().planetStocks!!.bases.getValue(1000).economy!!.peace
            advanceTimeBy(624);runCurrent()
            val initialPeace=peace()
            assertEquals(15L,rig.engine.snapshot().planetStocks!!.social.pirates.harmedThrough)
            advanceTimeBy(1);runCurrent()
            assertEquals(day+15,rig.engine.snapshot().session!!.gameSeconds)
            assertEquals(initialPeace-PirateActions.HARM,peace())
            advanceTimeBy(375);runCurrent()
            assertEquals(initialPeace-PirateActions.HARM,peace())
        } finally {rig.engine.drainAndClose()}
    }

    @Test fun restoredPiratesSpawnAndMoveAtTheirStrategicDeadline() = runTest {
        val week=PirateActions.SPAWN_DAYS*Justice.DAY
        val rig=Rig(this,testScheduler,resumedAt=week*1000/SessionRules.load().speed,pirateStrategyDeadline=true)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            fun fleets()=rig.engine.snapshot().planetStocks!!.social.pirates.fleets
            advanceTimeBy(624);runCurrent()
            assertEquals(setOf(1),fleets().keys)
            assertEquals(2489,fleets().getValue(1).grid)
            advanceTimeBy(1);runCurrent()
            assertEquals(week+15,rig.engine.snapshot().session!!.gameSeconds)
            assertEquals(setOf(1,2),fleets().keys)
            assertNotEquals(2489,fleets().getValue(1).grid)
            assertEquals(2490,fleets().getValue(2).grid)
        } finally {rig.engine.drainAndClose()}
    }

    @Test fun restoredPirateCounterattackFiresBetweenRegularTicks() = assertPirateCounterattack(regular=false)

    @Test fun restoredPirateCounterattackAgainstRegularShipsFiresBetweenRegularTicks() = assertPirateCounterattack(regular=true)

    private fun assertPirateCounterattack(regular:Boolean) = runTest {
        val rig=Rig(this,testScheduler,pirateDeadline=true,pirateRegularTarget=regular)
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

    @Test fun restoredDeadlineWaitsForTheIdentityProviderAndResumesImmediately() = runTest {
        val rig=Rig(this,testScheduler,retreatDeadline=true,fleetRetreat=true,awaitIdentities=true)
        try {
            advanceTimeBy(300);runCurrent()
            assertNull(rig.engine.snapshot().planetStocks)
            rig.engine.provideIdentities {emptyList()}.use {
                runCurrent()
                val fleet=rig.engine.snapshot().planetStocks!!.outfits.getValue(1)
                assertTrue(fleet.activeShips!!.values.single().tactical!!.retreat!!.membershipChecked)
                assertEquals(rig.start+7,rig.engine.snapshot().session!!.gameSeconds)
                advanceTimeBy(325);runCurrent()
                assertEquals(OutfitPosition(2487),rig.engine.snapshot().planetStocks!!.outfits.getValue(1).position)
            }
        } finally {rig.engine.drainAndClose()}
    }

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

    @Test fun restoredSabotageExplodesWithoutCombatAtItsDeadline() = runTest {
        val rig=Rig(this,testScheduler,espionageDeadline=true)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            fun justice()=rig.engine.snapshot().planetStocks!!.social.justice
            advanceTimeBy(624);runCurrent()
            assertEquals(rig.start+15,justice().bombs.getValue(10001).explodesAt)
            assertEquals(0L,rig.players.getValue("pilot").hull!!.woundedUntil)
            advanceTimeBy(1);runCurrent()
            assertEquals(rig.start+15,rig.engine.snapshot().session!!.gameSeconds)
            assertTrue(justice().bombs.isEmpty())
            assertTrue(justice().infiltrations.isEmpty())
            assertEquals(rig.start+15+EspionageActions.DISABLED_DAYS*Justice.DAY,justice().disabled[1000])
            assertEquals(rig.start+15+Justice.WOUND_SECONDS,rig.players.getValue("pilot").hull!!.woundedUntil)
        } finally {rig.engine.drainAndClose()}
    }

    @Test fun restoredDetentionEndsInAQuietWorldAtItsDeadline() = runTest {
        val rig=Rig(this,testScheduler,justiceDeadline=true)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            fun stock()=rig.engine.snapshot().planetStocks!!
            advanceTimeBy(624);runCurrent()
            assertTrue(stock().social.justice.detained(10000))
            assertEquals(10000019,rig.players.getValue("pilot").spotId)
            advanceTimeBy(1);runCurrent()
            assertEquals(rig.start+15,rig.engine.snapshot().session!!.gameSeconds)
            assertFalse(stock().social.justice.detained(10000))
            assertEquals(0,rig.players.getValue("pilot").spotId)
            assertEquals(9,stock().strategic.people.getValue(10000).rank)
            assertEquals(50L,stock().strategic.people.getValue(10000).merit)
            advanceTimeBy(375);runCurrent()
            assertEquals(9,stock().strategic.people.getValue(10000).rank)
        } finally {rig.engine.drainAndClose()}
    }

    @Test fun restoredOperationEndsBetweenRegularTicksWithoutCombat() = runTest {
        val resumedAt=OperationPlanRules.DURATION_GAME_SECONDS*1000/SessionRules.load().speed
        val rig=Rig(this,testScheduler,resumedAt=resumedAt,operationDeadline=true)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            fun operation()=rig.engine.snapshot().planetStocks!!.strategic.operations.single()
            advanceTimeBy(624);runCurrent()
            assertEquals(StrategicPlanState.STARTED,operation().state)
            advanceTimeBy(1);runCurrent()
            assertEquals(rig.start+OperationPlanRules.DURATION_GAME_SECONDS+15,rig.engine.snapshot().session!!.gameSeconds)
            assertEquals(OperationOutcome.FULL_SUCCESS,operation().outcome)
            assertEquals(100L,rig.engine.snapshot().planetStocks!!.strategic.people.getValue(10000).merit)
            advanceTimeBy(375);runCurrent()
            assertEquals(100L,rig.engine.snapshot().planetStocks!!.strategic.people.getValue(10000).merit)
        } finally {rig.engine.drainAndClose()}
    }

    @Test fun restoredFlagshipOfferExpiresBetweenRegularTicksWithoutEitherPartyOnline() = runTest {
        val rig=Rig(this,testScheduler,exchangeOffer=true)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            fun offers()=rig.engine.snapshot().planetStocks!!.flagshipMarket.offers
            advanceTimeBy(624);runCurrent()
            assertEquals(rig.start+15,offers().single().expiresAt)
            advanceTimeBy(1);runCurrent()
            assertTrue(offers().isEmpty())
            assertEquals(rig.start+15,rig.engine.snapshot().session!!.gameSeconds)
        } finally {rig.engine.drainAndClose()}
    }

    @Test fun restoredTradePassageExpiresBetweenRegularTicks() = runTest {
        val rig=Rig(this,testScheduler,tradeDeadline=true)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            fun passages()=rig.engine.snapshot().planetStocks!!.social.trade.passages
            advanceTimeBy(624);runCurrent()
            assertEquals(mapOf(2 to rig.start+15),passages())
            advanceTimeBy(1);runCurrent()
            assertTrue(passages().isEmpty())
            assertEquals(rig.start+15,rig.engine.snapshot().session!!.gameSeconds)
            assertEquals(0L,rig.engine.snapshot().session!!.strategyTicks)
        } finally {rig.engine.drainAndClose()}
    }

    @Test fun restoredMovingFortressStopsAtItsFinalWaypointBetweenRegularTicks() = runTest {
        val rig=Rig(this,testScheduler,fortressDeadline=true)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            fun fortress()=rig.engine.snapshot().planetStocks!!.tacticalCommands.fortresses.getValue(1000)
            advanceTimeBy(624);runCurrent()
            assertEquals(.5f,fortress().motion.velocity)
            advanceTimeBy(1);runCurrent()
            assertEquals(rig.start+15,fortress().reportedAt)
            assertEquals(0f,fortress().motion.velocity)
            assertEquals(TacticalPoint(7.5f,0f),fortress().motion.origin.position)
        } finally {rig.engine.drainAndClose()}
    }

    @Test fun restoredShipOrderEntersStockAtItsDeadlineWithoutCombat() = runTest {
        val rig=Rig(this,testScheduler,shipOrder=true)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            fun stock()=rig.engine.snapshot().planetStocks!!
            val stack=UnitStack(119)
            advanceTimeBy(624);runCurrent()
            assertEquals(ShipOrder(stack.kind,rig.start+15),stock().social.production.orders[1000])
            assertNull(stock().bases.getValue(1000).units.ships[stack])
            advanceTimeBy(1);runCurrent()
            assertEquals(rig.start+15,rig.engine.snapshot().session!!.gameSeconds)
            assertTrue(stock().social.production.orders.isEmpty())
            assertEquals(1,stock().bases.getValue(1000).units.ships[stack])
            assertEquals(0L,rig.engine.snapshot().session!!.strategyTicks)
            advanceTimeBy(375);runCurrent()
            assertEquals(1,stock().bases.getValue(1000).units.ships[stack])
        } finally {rig.engine.drainAndClose()}
    }

    @Test fun restoredFleetRetreatChecksMembershipAndCompletesBetweenPeriodicTicks() = runTest {
        val rig=Rig(this,testScheduler,retreatDeadline=true,fleetRetreat=true)
        try {
            fun fleet()=rig.engine.snapshot().planetStocks!!.outfits.getValue(1)
            advanceTimeBy(208);runCurrent()
            assertNull(rig.engine.snapshot().planetStocks)
            advanceTimeBy(1);runCurrent()
            assertTrue(fleet().activeShips!!.values.single().tactical!!.retreat!!.membershipChecked)
            assertEquals(rig.start+5,rig.engine.snapshot().session!!.gameSeconds)
            advanceTimeBy(415);runCurrent()
            assertEquals(OutfitPosition(2488),fleet().position)
            advanceTimeBy(1);runCurrent()
            assertEquals(rig.start+15,rig.engine.snapshot().session!!.gameSeconds)
            assertEquals(OutfitPosition(2487),fleet().position)
            assertNull(fleet().activeShips!!.values.single().tactical)
        } finally {rig.engine.drainAndClose()}
    }
    @Test fun blockedPersonalRetreatKeepsItsReservationAndLetsTheClockAdvance() = runTest {
        val rig=Rig(this,testScheduler,retreatDeadline=true,blockedRetreat=true)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            val prepared=rig.players.getValue("pilot").retreat
            assertNotNull(prepared)
            advanceTimeBy(625);runCurrent()
            assertEquals(rig.start+15,rig.engine.snapshot().session!!.gameSeconds)
            assertEquals(prepared,rig.players.getValue("pilot").retreat)
            advanceTimeBy(375);runCurrent()
            assertEquals(rig.start+24,rig.engine.snapshot().session!!.gameSeconds)
            assertEquals(prepared,rig.players.getValue("pilot").retreat)
            assertEquals(2488,rig.players.getValue("pilot").grid)
        } finally {rig.engine.drainAndClose()}
    }
    @Test fun restoredPersonalRetreatCompletesBetweenPeriodicTicks() = runTest {
        val rig=Rig(this,testScheduler,retreatDeadline=true)
        try {
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            advanceTimeBy(624);runCurrent()
            assertEquals(2488,rig.players.getValue("pilot").grid)
            advanceTimeBy(1);runCurrent()
            val player=rig.players.getValue("pilot")
            assertEquals(rig.start+15,rig.engine.snapshot().session!!.gameSeconds)
            assertEquals(2487,player.grid)
            assertNull(player.retreat)
            assertEquals(1,player.moves)
            assertEquals(player,rig.engine.snapshot().navigators.getValue("pilot"))
        } finally {rig.engine.drainAndClose()}
    }
    @Test fun unchargedAutomaticAttackLeavesTheClockFreeToAdvance() = runTest {
        val rig=Rig(this,testScheduler,automaticFireDeadline=true,uncharged=true)
        val impacts=mutableListOf<TacticalImpact>()
        rig.engine.observeCombat(impacts::add).use {
            try {
                rig.engine.submit(WorldCommand.Probe);runCurrent()
                advanceTimeBy(625);runCurrent()
                assertEquals(rig.start+15,rig.engine.snapshot().session!!.gameSeconds)
                assertEquals(rig.start+15,rig.players.getValue("pilot").attack!!.nextAt)
                assertTrue(impacts.isEmpty())
                advanceTimeBy(375);runCurrent()
                assertEquals(rig.start+24,rig.engine.snapshot().session!!.gameSeconds)
                assertTrue(impacts.isEmpty())
            } finally {rig.engine.drainAndClose()}
        }
    }

    @Test fun restoredFleetAutomaticAttackStartsBetweenPeriodicTicks() = runTest {
        val rig=Rig(this,testScheduler,fleetFireDeadline=true,automaticFireDeadline=true)
        val impacts=mutableListOf<TacticalImpact>()
        rig.engine.observeCombat(impacts::add).use {
            try {
                rig.engine.submit(WorldCommand.Probe);runCurrent()
                advanceTimeBy(624);runCurrent()
                assertTrue(impacts.isEmpty())
                advanceTimeBy(1);runCurrent()
                val snapshot=rig.engine.snapshot()
                assertEquals(rig.start+15,snapshot.session!!.gameSeconds)
                assertTrue(impacts.isNotEmpty())
                assertTrue(impacts.all {it.source==1 && it.hit.target==1010001 && it.hit.seconds==rig.start+15})
                val ship=snapshot.planetStocks!!.outfits.getValue(1).activeShips!!.values.single()
                assertTrue(ship.attack!!.order.nextAt>rig.start+15)
            } finally {rig.engine.drainAndClose()}
        }
    }

    @Test fun restoredAutomaticAttackStartsBetweenPeriodicTicks() = runTest {
        val rig=Rig(this,testScheduler,automaticFireDeadline=true)
        val impacts=mutableListOf<TacticalImpact>()
        rig.engine.observeCombat(impacts::add).use {
            try {
                rig.engine.submit(WorldCommand.Probe);runCurrent()
                advanceTimeBy(624);runCurrent()
                assertTrue(impacts.isEmpty())
                advanceTimeBy(1);runCurrent()
                assertEquals(rig.start+15,rig.engine.snapshot().session!!.gameSeconds)
                assertTrue(impacts.isNotEmpty())
                assertTrue(impacts.all {it.hit.target==1010001 && it.hit.seconds==rig.start+15})
                assertTrue(rig.players.getValue("pilot").attack!!.nextAt>rig.start+15)
            } finally {rig.engine.drainAndClose()}
        }
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
        supportDeadline:Boolean=false,fireDeadline:Boolean=false,fleetFireDeadline:Boolean=false,automaticFireDeadline:Boolean=false,
        uncharged:Boolean=false,pirateDeadline:Boolean=false,pirateRegularTarget:Boolean=false,pirateStrategyDeadline:Boolean=false,
        pirateHarmDeadline:Boolean=false,retreatDeadline:Boolean=false,blockedRetreat:Boolean=false,fleetRetreat:Boolean=false,
        awaitIdentities:Boolean=false,mailDeadline:Boolean=false,orderDeadline:Boolean=false,coupDeadline:Boolean=false,justiceDeadline:Boolean=false,espionageDeadline:Boolean=false,tradeDeadline:Boolean=false,fortressDeadline:Boolean=false,shipOrder:Boolean=false,
        operationDeadline:Boolean=false,exchangeOffer:Boolean=false) {
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
        val galaxy=Galaxy(listOf(GalaxySystem(3,homeGrid,4,"Home",if(orderDeadline)2 else 3))+
            listOfNotNull(GalaxySystem(4,2490,4,"Frontier").takeIf {pirateStrategyDeadline},
                GalaxySystem(5,2487,4,"Odin",2).takeIf {coupDeadline}),
            listOf(GalaxyBase(if(orderDeadline)1049 else 1000,homeGrid,80,if(fortressDeadline)1 else 0,
                if(orderDeadline)Capital.EMPIRE.baseName else "Home",0,20f,86400,0,0f,2f,mobile=fortressDeadline))+
            listOfNotNull(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f).takeIf {coupDeadline}),
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
        init {
            if(espionageDeadline) {
                val pilot=players.getValue("pilot")
                players=mapOf("pilot" to pilot.copy(baseId=1000,mode=4,spotId=10000019,cpPaused=false,motion=null,energy=null))
                val bomb=Bomb(10001,1000,10000019,start+15)
                val infiltration=Infiltration(10001,1000,10000019,start+15)
                stocks=stocks.copy(social=stocks.social.copy(justice=JusticeState(
                    bombs=mapOf(10001 to bomb),infiltrations=mapOf(10001 to infiltration))))
            }
            if(justiceDeadline) {
                val pilot=players.getValue("pilot")
                players=mapOf("pilot" to pilot.copy(baseId=1000,mode=4,spotId=10000019,cpPaused=false,motion=null,energy=null,hull=null))
                val detention=Detention(10000,CountryId(2),start,start+15,base=1000)
                stocks=stocks.copy(strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=8,merit=100))),
                    social=stocks.social.copy(justice=JusticeState(detentions=mapOf(10000 to detention))))
            }
            if(coupDeadline) {
                players=emptyMap()
                val plot=Plot(10000,CountryId(2),0,revolt=10,bases=setOf(1049),capitalSince=15,capitalBase=1049)
                stocks=stocks.copy(bases=stocks.bases+(1049 to PlanetStock(1049,2,1,0,0,0)),
                    social=stocks.social.copy(coup=CoupState(plots=mapOf(10000 to plot))))
            }
            if(operationDeadline) {
                players=emptyMap()
                val plan=StrategicOperation(1,10000,147,CountryId(3),2488,StrategicForce.FLEET,StrategicPurpose.DEFEND,1,
                    start+15,StrategicPlanState.STARTED,start+15,defended=setOf(1000),issuer=10001)
                stocks=stocks.copy(strategic=stocks.strategic.copy(operations=listOf(plan)))
            }
            if(exchangeOffer) {
                players=emptyMap()
                stocks=stocks.copy(flagshipMarket=FlagshipMarketState(listOf(
                    FlagshipExchangeOffer(10000,10001,89,93,1000,10002321,start+15))))
            }
            if(tradeDeadline) {
                players=emptyMap()
                stocks=stocks.copy(social=stocks.social.copy(trade=TradeState(passages=mapOf(2 to start+15))))
            }
            if(shipOrder) {
                players=emptyMap()
                stocks=stocks.copy(social=stocks.social.copy(
                    production=stocks.social.production.copy(orders=mapOf(1000 to ShipOrder(119,start+15)))))
            }
            if(retreatDeadline) {
                val point=TacticalPoint(-300f,0f)
                val motion=TacticalMotion(2488,start,TacticalPose(point,0f),0f,0f,listOf(point))
                val pilot=players.getValue("pilot").copy(motion=motion,energy=TacticalEnergy(listOf(0,50)+List(9){0}),
                    retreat=TacticalRetreat(2487,start,1,14))
                val enemy=Navigator(1,10001,Power.ALLIANCE,2488,1000,1000,1000,
                    cpPaused=true,hull=FlagshipHull(89,start))
                players=mapOf("pilot" to pilot,"enemy" to enemy)
                if(fleetRetreat) {
                    players=players+("pilot" to pilot.copy(retreat=null))
                    val stack=UnitStack(119)
                    val unit=ActiveShipUnit(1,stack,motionCommander=pilot.characterId,
                        tactical=NpcTacticalState(motion,start,start,retreat=TacticalRetreat(2487,start,5,10)))
                    val fleet=OutfitStock(1,pilot.country!!,OutfitKind.FLEET,1000,10,commanderId=pilot.characterId,
                        units=UnitInventory(mapOf(stack to 1)),activeShips=ActiveShipUnits(listOf(unit)),position=OutfitPosition(2488))
                    stocks=stocks.copy(outfits=mapOf(1 to fleet),nextShipId=2,
                        warehouses=mapOf(10 to OutfitWarehouse(10,fleet.country,OutfitKind.FLEET,1000,1)))
                }
                if(blockedRetreat) {
                    val stack=UnitStack(119)
                    val blocker=OutfitStock(1,3,OutfitKind.FLEET,1000,10,units=UnitInventory(mapOf(stack to 1)),
                        activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack))),position=OutfitPosition(2487))
                    stocks=stocks.copy(outfits=mapOf(1 to blocker),nextShipId=2,
                        warehouses=mapOf(10 to OutfitWarehouse(10,blocker.country,OutfitKind.FLEET,1000,1)))
                }
            }
            if(fireDeadline || fleetFireDeadline || automaticFireDeadline) {
                fun at(y:Float):TacticalMotion {
                    val point=TacticalPoint(180f,y)
                    return TacticalMotion(2488,start,TacticalPose(point,0f),0f,0f,listOf(point))
                }
                val automatic=if(automaticFireDeadline)TacticalAttackOrder(if(uncharged)1 else 2,start,start+15,start+15) else null
                val pilot=players.getValue("pilot").copy(motion=at(-97f),charge=TacticalCharge(start,if(uncharged)0 else 24000),
                    fire=if(fireDeadline)TacticalFire(1,TacticalPendingFire(1,2488,0,1010001,start,start+15)) else null,
                    attack=automatic.takeUnless {fleetFireDeadline})
                val enemy=Navigator(1,10001,Power.ALLIANCE,2488,1000,1000,1000,
                    cpPaused=true,hull=FlagshipHull(89,start),motion=at(-95f))
                players=mapOf("pilot" to pilot,"enemy" to enemy)
                if(fleetFireDeadline) {
                    val stack=UnitStack(32)
                    val ship=ActiveShipUnit(1,stack,tactical=NpcTacticalState(at(-96.5f),start,start),supplies=5000,
                        shot=if(automatic==null)TacticalUnitShot(10000,TacticalWeapon.GUN,enemy.unitId,start,start+15) else null,
                        attack=automatic?.let {TacticalUnitAttack(10000,it)})
                    val outfit=OutfitStock(1,2,OutfitKind.FLEET,1000,10,commanderId=10000,
                        units=UnitInventory(mapOf(stack to 1)),supplies=5000,activeShips=ActiveShipUnits(listOf(ship)),
                        position=OutfitPosition(2488))
                    stocks=stocks.copy(outfits=mapOf(1 to outfit),nextShipId=2,
                        warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)))
                }
            }
            if(pirateHarmDeadline) {
                players=emptyMap()
                val fleet=PirateFleet(1,2488,15,listOf(PirateUnit(900_001,300)))
                stocks=stocks.copy(bases=mapOf(1000 to PlanetStock(1000,3,0,0,0,0,PlanetEconomy(1000,500,50,40))),
                    social=stocks.social.copy(pirates=PirateState(mapOf(1 to fleet),2,
                        nextSpawnAt=PirateActions.SPAWN_DAYS*Justice.DAY,harmedThrough=15)))
            }
            if(pirateStrategyDeadline) {
                players=emptyMap()
                val fleet=PirateFleet(1,2489,15,listOf(PirateUnit(900_001,300)))
                val spawnAt=PirateActions.SPAWN_DAYS*Justice.DAY+15
                stocks=stocks.copy(social=stocks.social.copy(pirates=PirateState(mapOf(1 to fleet),2,spawnAt)))
            }
            if(pirateDeadline) {
                val pilot=players.getValue("pilot")
                val point=TacticalPoint(0f,-9f)
                val motion=TacticalMotion(2488,start,TacticalPose(point,0f),0f,0f,listOf(point))
                players=mapOf("pilot" to pilot.copy(motion=motion))
                val fleet=PirateFleet(1,2488,start,listOf(PirateUnit(900_001,300)),firedAt=start)
                stocks=stocks.copy(social=stocks.social.copy(pirates=PirateState(mapOf(1 to fleet),2)))
                if(pirateRegularTarget) {
                    players=emptyMap()
                    val stack=UnitStack(32)
                    val ship=ActiveShipUnit(1,stack,tactical=NpcTacticalState(motion,start,start))
                    val outfit=OutfitStock(1,2,OutfitKind.FLEET,1000,10,units=UnitInventory(mapOf(stack to 1)),
                        activeShips=ActiveShipUnits(listOf(ship)),position=OutfitPosition(2488))
                    stocks=stocks.copy(outfits=mapOf(1 to outfit),nextShipId=2,
                        warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)))
                }
            }
            if(fortressDeadline) {
                players=emptyMap()
                val motion=TacticalMotion(2488,start,TacticalPose(TacticalPoint(0f,0f),0f),.5f,0f,
                    listOf(TacticalPoint(2f,0f),TacticalPoint(7.5f,0f)),waitSeconds=0)
                stocks=stocks.copy(bases=mapOf(1000 to PlanetStock(1000,2,0,0,0,0)),
                    strategic=StrategicState(offices=listOf(OfficeHolding(51,1000,10000))),
                    tacticalCommands=TacticalCommandState(fortresses=mapOf(1000 to TacticalFortressMotion(2488,motion))))
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
            },npcPolicy=NpcTacticalPolicy(enabled=false),awaitIdentities=awaitIdentities)
    }
}
