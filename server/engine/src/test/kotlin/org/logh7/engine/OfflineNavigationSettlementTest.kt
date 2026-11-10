package org.logh7.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Offline captains' due navigation work completes on the world clock (checklist §3). Author: Peppone Choi. */
class OfflineNavigationSettlementTest {
    private val rules = SessionRules.load()
    private val start = rules.startSeconds
    private val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire"), GalaxySystem(4, 2015, 4, "Alliance")),
        emptyList(), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
    private val request = NavigationRequest("pilot", 10000, Power.EMPIRE, "connection", 1, unitId = 1010000, captainCard = 59)
    private val settle = NavigationRequest("pilot", 10000, Power.EMPIRE, "", 1, offlineSettle = true)
    private fun session(seconds: Long, online: Boolean) = SessionState(1, seconds * 1000, 0, 0,
        mapOf("pilot" to Participant(Power.EMPIRE, false, online, if (online) "connection" else null)), emptyMap(), emptyMap(), null)
    private class Store(var saved: Map<String, Navigator>) : NavigationStore {
        var saves = 0
        override fun load() = saved
        override fun save(players: Map<String, Navigator>) { saves++; saved = players.toMap() }
    }

    @Test fun anOfflineRefillCompletesOnceWithoutRecoveringCpOrNeedingAConnection() {
        val store = Store(mapOf("pilot" to Navigator(1, 10000, Power.EMPIRE, 2487, 1000, 1000, 900, cpRecoveryAt = start)))
        var nav = Navigation(galaxy, store, NavigationPolicy())
        val started = nav.apply(request.copy(supplyFuel = true), session(start, online = true)).player!!
        val readyAt = assertNotNull(started.fuelReadyAt)
        // Not due yet: the job only starts (WAITING -> RUNNING, as at login); fuel and balance stay.
        val early = nav.apply(settle, session(readyAt - 1, online = false))
        assertFalse(early.completedFuel)
        assertEquals(started.cruise, early.player!!.cruise); assertEquals(readyAt, early.player!!.fuelReadyAt)
        assertEquals(started.mcp, early.player!!.mcp)
        // An online captain is settled by its own requests, never by the offline path.
        assertEquals(NavigationRejection.SESSION, nav.apply(settle, session(readyAt, online = true)).rejection)
        val done = nav.apply(settle, session(readyAt, online = false))
        assertTrue(done.completedFuel)
        val complete = done.player!!
        assertEquals(1000, complete.cruise); assertNull(complete.fuelReadyAt)
        assertTrue(complete.strategy!!.jobs.isEmpty())
        // CP recovery needs the login's abilities: balance and recovery clock are untouched offline.
        assertEquals(started.mcp, complete.mcp); assertEquals(started.cpRecoveryAt, complete.cpRecoveryAt)
        assertEquals(complete, store.saved.getValue("pilot"))
        // Restart and repeat: the completed job is not applied twice, and the next login recovers the elapsed CP.
        nav = Navigation(galaxy, store, NavigationPolicy())
        assertFalse(nav.apply(settle, session(readyAt, online = false)).completedFuel)
        val login = nav.apply(request, session(readyAt, online = true))
        assertFalse(login.completedFuel)
        assertEquals(1000, login.player!!.mcp)
    }

    @Test fun knownAbilitiesSettleRecoveryBeforeTheOfflineJobAsAtLogin() {
        val store = Store(mapOf("pilot" to Navigator(1, 10000, Power.EMPIRE, 2487, 100, 1000, 900, cpRecoveryAt = start)))
        val nav = Navigation(galaxy, store, NavigationPolicy())
        val started = nav.apply(request.copy(supplyFuel = true), session(start, online = true)).player!!
        val readyAt = assertNotNull(started.fuelReadyAt)
        val done = nav.apply(settle.copy(politics = 100, offlineAbilities = true), session(readyAt, online = false))
        assertTrue(done.completedFuel)
        // Same arithmetic as the login path (NavigationRecoveryTest): +100 PCP per 2h at politics 100, capped.
        assertEquals(1000, done.player!!.pcp)
        assertTrue(done.player!!.cpRecoveryAt!! > started.cpRecoveryAt!!)
        assertFailsWith<IllegalArgumentException> { request.copy(offlineAbilities = true) }
    }

    @Test fun offlineSettlementNeverCreatesAFlagshipAndCarriesNoAction() {
        val store = Store(emptyMap())
        val nav = Navigation(galaxy, store, NavigationPolicy())
        assertEquals(NavigationRejection.SESSION, nav.apply(settle, session(start, online = false)).rejection)
        assertTrue(store.saved.isEmpty())
        assertFailsWith<IllegalArgumentException> { settle.copy(destination = 2488) }
        assertFailsWith<IllegalArgumentException> { settle.copy(supplyFuel = true) }
    }

    // World clock: the fleet travels with its offline commander and stays consistent after the tick.
    private val home = GalaxyBase(1000, 2487, 80, 0, "Home", 0, 20f, 86400, 0, 0f, 2f)
    private val fleetGalaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Home", 3), GalaxySystem(4, 2488, 4, "Enemy", 2)),
        listOf(home, home.copy(id = 1001, grid = 2488, name = "Enemy")), Power.entries.associateWith { 2487 })
    private val fleetSession = SessionSimulation(rules).also { it.join("pilot", Power.ALLIANCE, false, "c") }.exportState()
    private val fleetRequest = NavigationRequest("pilot", 10000, Power.ALLIANCE, "c", 1, unitId = 1010000, captainCard = 195)
    private class World(var stocks: PlanetStocks, player: Navigator) {
        var players = mapOf("pilot" to player)
        var commits = 0
        val nav = object : NavigationStore {
            override fun load() = players
            override fun save(players: Map<String, Navigator>) { this@World.players = players.toMap() }
        }
        val inventory = object : PlanetStockStore {
            override fun load() = stocks
            override fun save(stocks: PlanetStocks) { this@World.stocks = stocks }
        }
        val commit = WorldStateCommitter { navigation, inventory -> commits++; players = navigation.toMap(); stocks = checkNotNull(inventory) }
    }
    private fun world(): World {
        val quay = fleetGalaxy.facilities.getValue(1000).single { it.kind == 4 }.spots.single { it.kind == 6 }
        return World(PlanetStocks(1, 0, mapOf(1000 to PlanetStock(1000, 3, 0, 100, 100, 100),
            1001 to PlanetStock(1001, 2, 0, 100, 100, 100)),
            mapOf(1 to OutfitStock(1, 3, OutfitKind.FLEET, 1000, 10, commanderId = 10000,
                units = UnitInventory(mapOf(UnitStack(89) to 10), mapOf(UnitStack(4) to 3, UnitStack(7) to 40)))),
            mapOf(10 to OutfitWarehouse(10, 3, OutfitKind.FLEET, 1000, 1,
                units = UnitInventory(mapOf(UnitStack(89) to 200)))), deploymentInitialized = true),
            Navigator(1, 10000, Power.ALLIANCE, 2487, 1000, 1000, 1000, baseId = 1000, mode = 4, spotId = quay.id))
    }

    private fun reservedWarp(world: World): Navigator {
        val navigation = Navigation(fleetGalaxy, world.nav, NavigationPolicy())
        val production = PlanetProduction(fleetGalaxy, world.inventory, rules.startSeconds, PlanetProductionPolicy())
        for (r in listOf(fleetRequest.copy(place = PlaceMove.SwitchMode(5)), fleetRequest.copy(destination = 2489)))
            assertNull(FleetNavigation.apply(r, fleetSession, fleetGalaxy, navigation, production,
                production.settle(1, fleetSession.gameMillis / 1000), world.commit) { false }.rejection)
        return world.players.getValue("pilot").also { assertEquals(2489, it.warpTarget); assertEquals(2487, it.grid) }
    }
    private val profile = StrategicPerson(abilities = List(8) { 50 })

    @Test fun worldClockDoesNotCompleteADeceasedCaptainsSavedRefill() = runBlocking {
        val pilot = Navigator(1, 10000, Power.ALLIANCE, 2487, 1000, 1000, 999, cpRecoveryAt = start)
        val world = World(world().stocks, pilot)
        val nav = Navigation(fleetGalaxy, world.nav, NavigationPolicy())
        val reserved = assertNotNull(nav.apply(fleetRequest.copy(supplyFuel = true), fleetSession).player).copy(mcpUsed = 0)
        world.players = mapOf("pilot" to reserved)
        val readyAt = assertNotNull(reserved.fuelReadyAt)
        world.stocks = BattleDeath.apply(world.stocks, listOf(pilot.characterId), start) { _, _ -> true }
        val engine = WorldEngine(this, ticking = false, galaxy = fleetGalaxy, navigationStore = world.nav,
            stockStore = world.inventory, worldStateCommitter = world.commit, npcPolicy = NpcTacticalPolicy(enabled = false))
        try {
            val joined = CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("pilot", Power.ALLIANCE, false, joined, "c", 1, profile))
            assertEquals(Admission.ACCEPTED, joined.await())
            engine.submit(WorldCommand.Disconnect("pilot", "c"))
            engine.submit(WorldCommand.AdvanceTo((readyAt - start) * 1000 / rules.speed + 1))
            val saved = CompletableDeferred<Navigator>()
            engine.submit(WorldCommand.Quiesce({ world.players.getValue("pilot") }, saved))
            assertEquals(reserved, saved.await())
        } finally { engine.drainAndClose() }
    }

    @Test fun anOfflineCaptainPublishesTheCommittedDockAndOnlyTheActualFollowers() = runBlocking {
        val world = world()
        val pilot = world.players.getValue("pilot").copy(baseId = 0, mode = 5, spotId = 0)
        world.players = mapOf("pilot" to pilot, "aide" to pilot.copy(characterId = 10001),
            "observer" to pilot.copy(characterId = 10002))
        val outfit = world.stocks.outfits.getValue(1).copy(aideId = 10001, position = OutfitPosition(pilot.grid))
        world.stocks = world.stocks.copy(outfits = mapOf(outfit.id to outfit))
        val navigation = Navigation(fleetGalaxy, world.nav, NavigationPolicy())
        val production = PlanetProduction(fleetGalaxy, world.inventory, rules.startSeconds, PlanetProductionPolicy())
        val queued = FleetNavigation.apply(fleetRequest.copy(place = PlaceMove.Dock(1000, 4)),
            fleetSession, fleetGalaxy, navigation, production, production.settle(1, start), world.commit) { false }
        val due = assertNotNull(queued.player?.dockReadyAt)
        val engine = WorldEngine(this, ticking = false, galaxy = fleetGalaxy, navigationStore = world.nav,
            stockStore = world.inventory, worldStateCommitter = world.commit, npcPolicy = NpcTacticalPolicy(enabled = false))
        val arrivals = mutableListOf<Triple<OfflineDockArrival, WorldSnapshot, Map<String, Navigator>>>()
        engine.observeOfflineDockArrivals { arrivals += Triple(it, engine.snapshot(), world.players) }
        try {
            for (account in world.players.keys) {
                val joined = CompletableDeferred<Admission>()
                engine.submit(WorldCommand.Join(account, Power.ALLIANCE, false, joined, account, 1, profile))
                assertEquals(Admission.ACCEPTED, joined.await())
            }
            engine.submit(WorldCommand.Disconnect("pilot", "pilot"))
            val deadline = (due - start) * 1000 / rules.speed
            engine.submit(WorldCommand.AdvanceTo(deadline))
            engine.submit(WorldCommand.AdvanceTo(deadline + 1000))
            engine.drainAndClose()
            val (event, published, persisted) = arrivals.single()
            assertEquals(setOf(10001), event.followers)
            assertEquals(mapOf(10001 to "aide", 10002 to "observer"), event.recipients)
            assertEquals(persisted, published.navigators)
            assertEquals(persisted.getValue("pilot"), event.captain)
            assertEquals(1000, persisted.getValue("aide").baseId)
            assertEquals(0, persisted.getValue("observer").baseId)
            assertEquals(OutfitPosition(pilot.grid, 1000), published.planetStocks!!.outfits.getValue(1).position)
        } finally { engine.drainAndClose() }
    }

    @Test fun aReservedFleetWarpWaitsForOfflineCommanderRecoveryAndThenNotifiesViewers() = runBlocking {
        val world = world()
        val reserved = reservedWarp(world)
        val now = fleetSession.gameMillis / 1000
        val recovery = now + 3840
        world.players = mapOf("pilot" to reserved.copy(hull = FlagshipHull(93, now, woundedUntil = recovery)))
        val engine = WorldEngine(this, ticking = false, galaxy = fleetGalaxy, navigationStore = world.nav,
            stockStore = world.inventory, worldStateCommitter = world.commit, npcPolicy = NpcTacticalPolicy(enabled = false))
        val arrivals = java.util.concurrent.CopyOnWriteArrayList<NpcStrategicMovement>()
        engine.observeStrategicMovements { arrivals += it }
        try {
            val joined = CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("pilot", Power.ALLIANCE, false, joined, "c", 1, profile)); assertEquals(Admission.ACCEPTED, joined.await())
            engine.submit(WorldCommand.Disconnect("pilot", "c"))
            engine.submit(WorldCommand.AdvanceTo(150_000))
            val waiting = CompletableDeferred<Navigator>()
            engine.submit(WorldCommand.Quiesce({ world.players.getValue("pilot") }, waiting))
            assertEquals(2487, waiting.await().grid)
            assertTrue(arrivals.isEmpty())
            engine.submit(WorldCommand.AdvanceTo(160_000))
            engine.drainAndClose()
            val arrived = world.players.getValue("pilot")
            assertNull(arrived.warpTarget); assertNotEquals(2487, arrived.grid)
            assertEquals(reserved.moves + 1, arrived.moves, "the warp executes exactly once across ticks")
            assertEquals(OutfitPosition(arrived.grid, arrived.baseId), world.stocks.outfits.getValue(1).position,
                "the fleet moves with its offline commander")
            assertTrue(arrived.mcp >= reserved.mcp && arrived.pcp >= reserved.pcp, "recovery is settled before arrival, never lost")
            assertEquals(arrived.grid, engine.snapshot().navigators.getValue("pilot").grid, "the snapshot shows the arrival")
            // Viewers get the NPC-arrival delivery (force report / tactical rebuild), exactly once.
            val arrival = arrivals.single()
            assertEquals(1, arrival.outfit); assertEquals(2487, arrival.source); assertEquals(arrived.grid, arrival.destination)
            assertTrue(arrival.units.isNotEmpty()); assertTrue(arrival.recipients.isEmpty(), "the offline captain gets no packets")
        } finally { engine.drainAndClose() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun aRestoredWarpUsesRegistryAbilitiesAtItsDeadlineWithoutAClientRequest() = runTest {
        val world = world()
        val reserved = reservedWarp(world)
        val readyAt = assertNotNull(reserved.warpReadyAt)
        val elapsed = ((readyAt - start) * 1000 + rules.speed - 1) / rules.speed - 625
        val durability = object : SessionDurability {
            private var saved = DurableWorldState(SessionSimulation(rules).apply {
                join("pilot", Power.ALLIANCE, false, "c")
                disconnect("pilot", "c")
                advance(elapsed)
            }.exportState(), elapsed)
            override fun load() = saved
            override fun commit(action: SessionAction, transition: SessionTransition) { saved = transition.state }
            override fun close() {}
        }
        val engine = WorldEngine(this, rules, durability = durability, monotonicMillis = { testScheduler.currentTime },
            galaxy = fleetGalaxy, navigationStore = world.nav, stockStore = world.inventory,
            worldStateCommitter = world.commit, npcPolicy = NpcTacticalPolicy(enabled = false), awaitIdentities = true)
        try {
            advanceTimeBy(100); runCurrent()
            engine.provideIdentities { listOf(StrategicIdentity(10000, Power.ALLIANCE.country, 5, 0, List(8) { 50 })) }.use {
                advanceTimeBy(524); runCurrent()
                assertEquals(2487, world.players.getValue("pilot").grid)
                advanceTimeBy(1); runCurrent()
                assertEquals(readyAt, engine.snapshot().session!!.gameSeconds)
                assertEquals(2489, world.players.getValue("pilot").grid)
                assertEquals(OutfitPosition(2489), world.stocks.outfits.getValue(1).position)
            }
        } finally { engine.drainAndClose() }
    }

    @Test fun savedAbilitiesAllowOfflineWarpWithoutALoginProfile() = assertOfflineWarpWithoutLoginProfile(saved=true)

    @Test fun registeredAbilitiesAllowOfflineWarpWithoutAWorldProfile() = assertOfflineWarpWithoutLoginProfile(saved=false)

    private fun assertOfflineWarpWithoutLoginProfile(saved:Boolean) = runBlocking {
        val world = world()
        val reserved = reservedWarp(world)
        if(saved)world.stocks = world.stocks.copy(strategic = world.stocks.strategic.copy(people = mapOf(10000 to profile)))
        world.players = mapOf("pilot" to reserved.copy(pcp = 100, cpRecoveryAt = start))
        val engine = WorldEngine(this, ticking = false, galaxy = fleetGalaxy, navigationStore = world.nav,
            stockStore = world.inventory, worldStateCommitter = world.commit, npcPolicy = NpcTacticalPolicy(enabled = false))
        if(!saved)engine.provideIdentities {listOf(StrategicIdentity(10000,Power.ALLIANCE.country,5,0,List(8){50}))}
        try {
            val joined = CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("pilot", Power.ALLIANCE, false, joined, "c", 1))
            assertEquals(Admission.ACCEPTED, joined.await())
            engine.submit(WorldCommand.Disconnect("pilot", "c"))
            engine.submit(WorldCommand.AdvanceTo(400_000))
            engine.drainAndClose()
            val arrived = world.players.getValue("pilot")
            assertEquals(2489, arrived.grid)
            assertNull(arrived.warpTarget)
            assertTrue(arrived.pcp > 100)
            assertEquals(OutfitPosition(2489), world.stocks.outfits.getValue(1).position)
        } finally { engine.drainAndClose() }
    }

    @Test fun withoutKnownAbilitiesAnOfflineWarpWaitsForLoginSoRecoveryIsNotForfeited() = runBlocking {
        val world = world()
        val reserved = reservedWarp(world)
        val engine = WorldEngine(this, ticking = false, galaxy = fleetGalaxy, navigationStore = world.nav,
            stockStore = world.inventory, worldStateCommitter = world.commit, npcPolicy = NpcTacticalPolicy(enabled = false))
        try {
            val joined = CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("pilot", Power.ALLIANCE, false, joined, "c", 1)); assertEquals(Admission.ACCEPTED, joined.await())
            engine.submit(WorldCommand.Disconnect("pilot", "c"))
            engine.submit(WorldCommand.AdvanceTo(150_000))
            engine.drainAndClose()
            assertEquals(reserved, world.players.getValue("pilot"), "the reservation is left for the login settlement")
        } finally { engine.drainAndClose() }
    }
}
