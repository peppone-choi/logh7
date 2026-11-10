package org.logh7.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalRetreatTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3)),
        listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val stocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,3,0,0,0,0)))
    private val session=SessionSimulation(rules).also {it.join("pilot",Power.ALLIANCE,false,"c")}.exportState()
        .let { it.copy(gameMillis=it.gameMillis+10000) }
    private val seconds=session.gameMillis/1000
    private val pose=TacticalPose(TacticalPoint(-300f,0f),1f)
    private val pilot=Navigator(1,10000,Power.ALLIANCE,2487,600,500,900,cpPaused=true,
        energy=TacticalEnergy(listOf(100,50)+List(9){0}),
        motion=TacticalMotion(2487,seconds-5,pose,0f,pose.direction,listOf(pose.position)))
    private val request=NavigationRequest("pilot",10000,Power.ALLIANCE,"c",1,captainCard=195,shipKind=89,
        tacticalRetreat=listOf(1010000))
    private class Store(initial:Map<String,Navigator>):NavigationStore {
        var players=initial;var fail=false
        override fun load()=players
        override fun save(players:Map<String,Navigator>){if(fail)throw java.io.IOException("disk");this.players=players.toMap()}
    }
    private fun store(player:Navigator=pilot)=Store(mapOf("pilot" to player,
        "enemy" to pilot.copy(characterId=10001,country=Power.EMPIRE.country,shipStyle=ShipStyleId.fromLegacy(Power.EMPIRE))))
    @Test fun validatesAuthorityWarpMaximumAndRadiusBeforePersistingAndDoesNotExtendExistingDeadline() {
        val store=store();val nav=Navigation(galaxy,store,NavigationPolicy())
        assertEquals(NavigationRejection.UNIT,nav.apply(request.copy(tacticalRetreat=listOf(1010001)),session,stocks).rejection)
        assertEquals(NavigationRejection.CARD,nav.apply(request.copy(captainCard=59),session,stocks).rejection)
        store.fail=true;assertFailsWith<java.io.IOException>{nav.apply(request,session,stocks)}
        assertNull(nav.snapshot(1).getValue("pilot").retreat);store.fail=false
        val after=nav.apply(request,session,stocks).player!!
        assertEquals(2486,after.retreat!!.destination);assertEquals(seconds+3720,after.retreat.completesAt)
        assertEquals(pilot.pcp,after.pcp);assertEquals(pilot.mcp,after.mcp);assertEquals(pilot.cruise,after.cruise)
        assertEquals(NavigationRejection.BUSY,nav.apply(request,session,stocks).rejection)
        for (variant in listOf(pilot.copy(energy=TacticalEnergy(listOf(100,49)+List(9){0})),
            pilot.copy(motion=pilot.motion!!.copy(origin=pose.copy(position=TacticalPoint(-250f,0f)))))) {
            val decision=Navigation(galaxy,store(variant),NavigationPolicy()).apply(request,session,stocks)
            assertEquals(if(variant.energy!!.values[1]==49)NavigationRejection.ENERGY else NavigationRejection.INSIDE_RADAR,decision.rejection)
        }
    }
    @Test fun preparationSurvivesReloadAllowsStopAndMovesAndCompletesOfflineWithoutMovingPeer() {
        val store=store();var nav=Navigation(galaxy,store,NavigationPolicy())
        val prepared=nav.apply(request,session,stocks).player!!
        nav=Navigation(galaxy,store,NavigationPolicy())
        val stop=request.copy(tacticalRetreat=null,tacticalStop=TacticalStop(listOf(1010000)))
        val stopped=nav.apply(stop,session.copy(gameMillis=session.gameMillis+120000),stocks).player!!
        assertEquals(prepared.retreat,stopped.retreat)
        val moving=nav.apply(request.copy(tacticalRetreat=null,tacticalMove=TacticalMove(listOf(1010000),1f,0f,
            listOf(TacticalPoint(-400f,0f)))),session.copy(gameMillis=session.gameMillis+180000),stocks).player!!
        assertEquals(prepared.retreat,moving.retreat)
        val deadline=prepared.retreat!!.completesAt
        nav.settleRetreats(session.copy(gameMillis=(deadline-1)*1000),stocks)
        assertEquals(2487,nav.snapshot(1).getValue("pilot").grid)
        val offline=session.copy(gameMillis=deadline*1000,participants=emptyMap())
        nav.settleRetreats(offline,stocks)
        val exited=nav.snapshot(1).getValue("pilot")
        assertEquals(2486,exited.grid);assertFalse(exited.cpPaused);assertNull(exited.motion);assertNull(exited.retreat)
        assertEquals(pilot.cruise,exited.cruise);assertEquals(pilot.moves+1,exited.moves)
        assertEquals(2487,nav.snapshot(1).getValue("enemy").grid)
        nav.settleRetreats(offline,stocks);assertEquals(exited,nav.snapshot(1).getValue("pilot"))
        val reduced=Navigation(galaxy,store(),NavigationPolicy())
        reduced.apply(request,session,stocks)
        val energy=reduced.apply(request.copy(tacticalRetreat=null,unitId=1010000,
            energy=TacticalEnergy.ZERO),session,stocks).player!!
        assertNull(energy.retreat)
    }
    @Test fun leavingAnOuterBerthClearsItsPostureAndAllowsACommandInTheNextBattle() {
        val destination = 2486
        val source = galaxy.bases.single().copy(kind = 1, radius = 300f, angle = -90f)
        val entry = TacticalDeployment.formation(0, Power.ALLIANCE).position
        val target = source.copy(id = 1001, grid = destination, name = "Enemy", radius =
            kotlin.math.hypot(entry.x, entry.y), angle = Math.toDegrees(kotlin.math.atan2(entry.x, entry.y).toDouble()).toFloat())
        val world = Galaxy(galaxy.systems + GalaxySystem(4, destination, 4, "Enemy", 2), listOf(source, target), galaxy.starts)
        val inventory = stocks.copy(bases = stocks.bases + (target.id to PlanetStock(target.id, 2, 0, 0, 0, 0)))
        val anchored = pilot.copy(posture = TacticalPosture(pilot.grid, TacticalShipMode.ANCHORED, source.id))
        val store = store(anchored)
        val ally = pilot.copy(characterId = 10002, grid = destination, motion = null)
        store.players = store.players + ("ally" to ally)
        val navigation = Navigation(world, store, NavigationPolicy())
        val queued = assertNotNull(navigation.apply(request, session, inventory).player)
        val retreat = assertNotNull(queued.retreat)
        assertEquals(destination, retreat.destination)
        assertEquals(anchored.posture, queued.posture)

        val due = session.copy(gameMillis = retreat.completesAt * 1000)
        Navigation(world, store, NavigationPolicy()).settleRetreats(due, inventory)
        val restored = Navigation(world, store, NavigationPolicy()).snapshot(1)
        val arrived = restored.getValue("pilot")
        assertEquals(destination, arrived.grid)
        assertTrue(arrived.cpPaused)
        assertNull(arrived.posture)
        assertNull(arrived.retreat)

        val command = StrategyActionRequest("pilot", "c", pilot.characterId, Power.ALLIANCE, 1,
            OrbitalStrikeActions.STRIKE, 0, 1)
        val quote = OrbitalStrikeActions.evaluate(world, SocialRequest(command, phase = OrbitalStrikeActions.QUOTE_PHASE),
            due, arrived, inventory, restored.values)
        assertNull(quote.rejection)
        assertEquals(target.id, quote.found)
        val strike = OrbitalStrikeActions.evaluate(world, SocialRequest(command.copy(base = quote.found,
            argument = quote.outcome, value = OrbitalStrikeActions.SUPPLIES)), due, arrived, inventory, restored.values)
        assertTrue(strike.changed)
        assertEquals(arrived.supplies - OrbitalStrikeActions.SUPPLIES, strike.player?.supplies)
    }

    @Test fun retreatFromAMovingBerthUsesItsCurrentRadarDistanceAtAcceptanceAndCompletion() {
        val fortress=galaxy.bases.single().copy(kind=1,mobile=true,radius=300f,angle=-90f)
        val world=Galaxy(galaxy.systems,listOf(fortress),galaxy.starts)
        val anchored=pilot.copy(posture=TacticalPosture(pilot.grid,TacticalShipMode.ANCHORED,fortress.id))
        val saved=store(anchored)
        val navigation=Navigation(world,saved,NavigationPolicy())
        val retreat=assertNotNull(navigation.apply(request,session,stocks).player?.retreat)
        assertEquals(2486,retreat.destination)

        val start=TacticalBasePosition.at(fortress,seconds)
        val fortressMotion=TacticalMotion(pilot.grid,retreat.completesAt-650,TacticalPose(start,0f),
            TacticalFortressMotion.VELOCITY,0f,listOf(TacticalPoint(-240f,0f),start),waitSeconds=0)
        val moving=stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(
            fortresses=mapOf(fortress.id to TacticalFortressMotion(pilot.grid,fortressMotion))))
        val due=session.copy(gameMillis=retreat.completesAt*1000)
        val fresh=Navigation(world,store(anchored),NavigationPolicy())
        assertEquals(NavigationRejection.INSIDE_RADAR,fresh.apply(request,due,moving).rejection)

        val restored=Navigation(world,saved,NavigationPolicy())
        restored.settleRetreats(due,moving)
        assertEquals(pilot.grid,restored.snapshot(1).getValue("pilot").grid)
        assertEquals(retreat,restored.snapshot(1).getValue("pilot").retreat)
        restored.settleRetreats(due.copy(gameMillis=due.gameMillis+600_000),moving)
        val arrived=restored.snapshot(1).getValue("pilot")
        assertEquals(retreat.destination,arrived.grid)
        assertNull(arrived.retreat)
        assertNull(arrived.posture)
    }

    @Test fun eliminatedFlagshipCancelsRestoredRetreatWithoutWarping() {
        val store=store()
        val prepared=Navigation(galaxy,store,NavigationPolicy()).apply(request,session,stocks).player!!
        val due=session.copy(gameMillis=prepared.retreat!!.completesAt*1000,participants=emptyMap(),
            eliminated=mapOf(Power.ALLIANCE.country to CountryDefeat(EndReason.SYSTEMS_REDUCED,seconds)))
        val conflicting=session.participants.mapValues {(_,member)->member.copy(country=Power.EMPIRE.country)}
        for(participants in listOf(emptyMap(),conflicting)) {
            store.players=store.players+("pilot" to prepared)
            val nav=Navigation(galaxy,store,NavigationPolicy())
            val tick=due.copy(participants=participants)
            store.fail=true
            assertFailsWith<java.io.IOException> {nav.settleRetreats(tick,stocks)}
            assertEquals(prepared,nav.snapshot(1).getValue("pilot"))
            store.fail=false
            nav.settleRetreats(tick,stocks)
            assertEquals(prepared.copy(retreat=null),store.players.getValue("pilot"))
        }
    }

    @Test fun flagshipRetreatIgnoresAnEliminatedFleetAtThePreferredExit() {
        val blocker=OutfitStock(500,2,OutfitKind.FLEET,1000,500,500,
            UnitInventory(mapOf(UnitStack(32) to 1)),position=OutfitPosition(2486))
        val pirates=PirateFleet(1,2487,seconds,listOf(PirateUnit(900001,1)))
        val occupied=stocks.copy(outfits=mapOf(500 to blocker),
            warehouses=mapOf(500 to OutfitWarehouse(500,2,OutfitKind.FLEET,1000,500)),
            social=stocks.social.copy(pirates=PirateState(mapOf(1 to pirates),2)))
        val eliminated=session.copy(eliminated=mapOf(Power.EMPIRE.country to
            CountryDefeat(EndReason.SYSTEMS_REDUCED,seconds)))
        val active=Navigation(galaxy,store(),NavigationPolicy())
        assertNotEquals(2486,assertNotNull(active.apply(request,session,occupied).player?.retreat).destination)
        val nav=Navigation(galaxy,store(),NavigationPolicy())
        val prepared=assertNotNull(nav.apply(request,eliminated,occupied).player?.retreat)
        assertEquals(2486,prepared.destination)
        nav.settleRetreats(eliminated.copy(gameMillis=prepared.completesAt*1000),occupied)
        val moved=nav.snapshot(1).getValue("pilot")
        assertEquals(2486,moved.grid)
        assertFalse(moved.cpPaused)
        assertNull(moved.retreat)
        assertEquals(pilot.moves+1,moved.moves)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun strategicCommandRecoveryCannotFinishRetreatThroughAHostileFleet() = runTest {
        val retreat = TacticalRetreat(2486, rules.startSeconds, 1, 1)
        val waiting = pilot.copy(motion = pilot.motion!!.copy(acceptedAt = rules.startSeconds), retreat = retreat)
        val saved = store(waiting)
        val blocker = OutfitStock(500, 2, OutfitKind.FLEET, 1000, 500, 500,
            UnitInventory(mapOf(UnitStack(32) to 1)), position = OutfitPosition(retreat.destination))
        var inventory = stocks.copy(outfits = mapOf(500 to blocker),
            warehouses = mapOf(500 to OutfitWarehouse(500, 2, OutfitKind.FLEET, 1000, 500)),
            deploymentInitialized = true)
        val engine = WorldEngine(this, rules, ticking = false, galaxy = galaxy, navigationStore = saved,
            stockStore = object : PlanetStockStore {
                override fun load() = inventory
                override fun save(stocks: PlanetStocks) { inventory = stocks }
            },
            worldStateCommitter = WorldStateCommitter { players, updated ->
                saved.save(players)
                inventory = checkNotNull(updated)
            }, npcPolicy = NpcTacticalPolicy(enabled = false))
        try {
            val joined = CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("pilot", Power.ALLIANCE, false, joined, "c", 1))
            assertEquals(Admission.ACCEPTED, joined.await())
            engine.submit(WorldCommand.AdvanceTo(2_000))
            runCurrent()
            val before = engine.snapshot().navigators.getValue("pilot")
            assertEquals(retreat, before.retreat)
            assertTrue(before.cpPaused)

            val command = StrategyActionRequest("pilot", "c", pilot.characterId, Power.ALLIANCE, 1, Justice.PERMIT, 0, 20)
            val reply = CompletableDeferred<SocialResult>()
            engine.submit(WorldCommand.Social(SocialRequest(command), reply))
            assertEquals(SocialRejection.BUSY, reply.await().rejection)
            val after = engine.snapshot().navigators.getValue("pilot")
            assertEquals(before.copy(cpRecoveryAt = after.cpRecoveryAt, commandRange = after.commandRange), after)
            assertEquals(after, saved.players.getValue("pilot"))
        } finally {
            engine.drainAndClose()
        }
    }

    @Test fun completedPreparationWaitsForLegalExitAndBattleEndCancelsItWithoutWarping() {
        val store=store();val nav=Navigation(galaxy,store,NavigationPolicy())
        val prepared=nav.apply(request,session,stocks).player!!
        val finished=session.copy(gameMillis=prepared.retreat!!.completesAt*1000)
        val blocker=OutfitStock(500,2,OutfitKind.FLEET,1000,500,500,
            UnitInventory(mapOf(UnitStack(32) to 1)),position=OutfitPosition(2486))
        val blocked=stocks.copy(outfits=mapOf(500 to blocker),
            warehouses=mapOf(500 to OutfitWarehouse(500,2,OutfitKind.FLEET,1000,500)))
        nav.settleRetreats(finished,blocked)
        assertEquals(prepared,nav.snapshot(1).getValue("pilot"))
        nav.settleRetreats(finished.copy(gameMillis=finished.gameMillis+1000),stocks)
        val released=nav.snapshot(1).getValue("pilot")
        assertEquals(2486,released.grid)
        assertEquals(prepared.moves+1,released.moves)
        assertNull(released.retreat)
        nav.settleRetreats(finished.copy(gameMillis=finished.gameMillis+2000),stocks)
        assertEquals(released,nav.snapshot(1).getValue("pilot"))
        store.players=store.players+("pilot" to prepared)
        store.players=store.players-"enemy"
        val restored=Navigation(galaxy,store,NavigationPolicy())
        restored.settleRetreats(finished,stocks)
        val cancelled=restored.snapshot(1).getValue("pilot")
        assertEquals(2487,cancelled.grid);assertFalse(cancelled.cpPaused);assertNull(cancelled.retreat)
        assertFailsWith<IllegalArgumentException>{TacticalRetreat(2486,Long.MAX_VALUE,120,3600)}
        assertFailsWith<IllegalArgumentException>{prepared.copy(retreat=prepared.retreat.copy(destination=2000))}
    }
}
