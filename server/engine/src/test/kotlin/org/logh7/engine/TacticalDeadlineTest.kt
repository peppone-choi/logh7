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
            rig.engine.submit(WorldCommand.Probe);runCurrent()
            fun fleet()=rig.engine.snapshot().planetStocks!!.outfits.getValue(1)
            advanceTimeBy(208);runCurrent()
            assertFalse(fleet().activeShips!!.values.single().tactical!!.retreat!!.membershipChecked)
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
        uncharged:Boolean=false,retreatDeadline:Boolean=false,blockedRetreat:Boolean=false,fleetRetreat:Boolean=false,coupDeadline:Boolean=false,tradeDeadline:Boolean=false,shipOrder:Boolean=false,fortressDeadline:Boolean=false) {
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
            if(coupDeadline) {
                players=emptyMap()
                val plot=Plot(10000,CountryId(2),0,revolt=10,bases=setOf(1049),capitalSince=15,capitalBase=1049)
                stocks=stocks.copy(bases=stocks.bases+(1049 to PlanetStock(1049,2,1,0,0,0)),
                    social=stocks.social.copy(coup=CoupState(plots=mapOf(10000 to plot))))
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
        private val galaxy=Galaxy(listOf(GalaxySystem(3,2488,4,"Home",3))+
            listOfNotNull(GalaxySystem(4,2487,4,"Odin",2).takeIf {coupDeadline}),
            listOf(GalaxyBase(1000,2488,80,if(fortressDeadline)1 else 0,"Home",0,20f,86400,0,0f,2f,mobile=fortressDeadline))+
            listOfNotNull(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f).takeIf {coupDeadline}),
            Power.entries.associateWith {2488})
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
