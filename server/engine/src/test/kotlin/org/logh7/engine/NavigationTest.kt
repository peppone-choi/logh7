package org.logh7.engine

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class NavigationTest {
    @Test fun foreignSpyInTheCapitalHoldingRoomDoesNotPreventDocking() {
        val capital = GalaxyBase(1049, 2487, 80, 0, Capital.EMPIRE.baseName, 0, 20f, 86400, 0, 0f, 2f)
        val outpost = capital.copy(id = 1048, grid = 2486, name = "Outpost")
        val galaxy = Galaxy(
            listOf(GalaxySystem(3, 2487, 4, "Capital", 2), GalaxySystem(4, 2486, 4, "Outpost", 2)),
            listOf(capital, outpost), Power.entries.associateWith { 2487 },
        )
        val session = SessionSimulation(SessionRules.load()).also {
            it.join("pilot", Power.EMPIRE, false, "connection")
        }.exportState()
        val now = session.gameMillis / 1000
        val captain = Navigator(1, request.characterId, Power.EMPIRE, capital.grid, 1000, 1000, 1000, cpRecoveryAt = now)
        val spy = Navigator(1, 10001, Power.ALLIANCE, outpost.grid, 1000, 1000, 1000,
            baseId = outpost.id, mode = 4, hull = FlagshipHull(89, now))
        val players = mutableMapOf("pilot" to captain, "spy" to spy)
        val stocks = PlanetStocks(1, 0, galaxy.bases.associate { it.id to PlanetStock(it.id, 2, 0, 100, 100, 100) })
        val detained = assertNotNull(JusticeActions.detain(galaxy, stocks, players, spy.characterId, captain.country!!, now))
        assertEquals(capital.id, players.getValue("spy").baseId)
        assertEquals(spy.country, players.getValue("spy").country)
        val store = Store().also { it.saved = players.toMap() }
        val navigation = Navigation(galaxy, store, NavigationPolicy())
        val dock = request.copy(unitId = captain.unitId, captainCard = 59, place = PlaceMove.Dock(capital.id, 4))

        val reserved = navigation.apply(dock, session, detained)
        assertNull(reserved.rejection)
        val waiting = assertNotNull(reserved.player)
        assertEquals(capital.id, waiting.dockTarget)
        assertFalse(store.saved.getValue("pilot").cpPaused)
        val later = session.copy(gameMillis = assertNotNull(waiting.dockReadyAt) * 1000)
        val arrived = navigation.apply(request, later, detained)
        assertTrue(arrived.completedDock)
        assertEquals(capital.id, assertNotNull(arrived.player).baseId)
    }

    @Test fun deceasedCaptainCanViewTheFrozenRecordButCannotNavigateAndCanBeReplaced() {
        val store = Store()
        val simulation = SessionSimulation(SessionRules.load()).also {
            it.join("pilot", Power.EMPIRE, false, "connection")
        }
        val now = simulation.exportState().gameMillis / 1000
        val dead = Navigator(1, request.characterId, Power.EMPIRE, 2487, 100, 100, 900, cpRecoveryAt = now,
            hull = FlagshipHull(0, now, woundedUntil = now + 1))
        store.saved = mapOf("pilot" to dead)
        val nav = Navigation(galaxy(), store, NavigationPolicy())
        val stocks = BattleDeath.apply(PlanetStocks(1, 0, emptyMap()), listOf(dead.characterId), now) { _, _ -> true }
        val later = simulation.exportState().copy(gameMillis = (now + 7201) * 1000)
        val captain = request.copy(unitId = dead.unitId, captainCard = 59)
        store.fail = true
        assertEquals(dead, nav.apply(captain, later, stocks).player)
        for (action in listOf(captain.copy(destination = 2488), captain.copy(place = PlaceMove.Dock(1000, 4)),
            captain.copy(supplyFuel = true), captain.copy(returnBase = 0))) {
            val rejected = nav.apply(action, later, stocks)
            assertEquals(NavigationRejection.IDENTITY, rejected.rejection)
            assertEquals(dead, rejected.previous)
        }
        assertEquals(dead, store.saved.getValue("pilot"))
        store.fail = false
        val replacement = assertNotNull(nav.apply(request.copy(characterId = 10001), later, stocks).player)
        assertEquals(10001, replacement.characterId)
        assertEquals(replacement, store.saved.getValue("pilot"))
    }

    @Test fun bothDepartureCommandsRejectCapturedBerthsAndAllowLeavingRemovedBerths() {
        val base = GalaxyBase(1000, 2487, 60, 0, "Home", 0, 20f, 86400, 0, 0f, 2f)
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire", 2)), listOf(base),
            Power.entries.associateWith { 2487 })
        val session = SessionSimulation(SessionRules.load()).also {
            it.join("pilot", Power.EMPIRE, false, "connection")
        }.exportState()
        val before = Navigator(1, 10000, Power.EMPIRE, 2487, 1000, 1000, 1000, baseId = 1000,
            mode = 4, cpRecoveryAt = session.gameMillis / 1000)
        val captured = mapOf(1000 to PlanetStock(1000, 3, 0, 100, 100, 100))
        for (command in listOf(PlaceMove.SwitchMode(5), PlaceMove.Dock(0, 5))) {
            for (bases in listOf(captured, emptyMap())) {
                val store = Store().also { it.saved = mapOf("pilot" to before) }
                val nav = Navigation(galaxy, store, NavigationPolicy())
                val result = nav.apply(request.copy(unitId = 1010000, captainCard = 59, place = command),
                    session, PlanetStocks(1, 0, bases))
                if (bases.isEmpty()) {
                    val departed = assertNotNull(result.player)
                    assertEquals(0, departed.baseId)
                    assertEquals(5, departed.mode)
                    assertEquals(before.mcp, departed.mcp)
                } else {
                    assertEquals(NavigationRejection.ACCESS, result.rejection)
                    assertEquals(before, store.saved.getValue("pilot"))
                }
            }
        }
    }

    @Test fun bothPersonalDepartureCommandsClearThePreviousStationedTacticalPosture() {
        val base=GalaxyBase(1000,2487,60,0,"Home",0,20f,86400,0,0f,2f)
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2)),listOf(base),Power.entries.associateWith {2487})
        val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.EMPIRE,false,"connection")}.exportState()
        for(command in listOf(PlaceMove.SwitchMode(5),PlaceMove.Dock(0,5))) {
            val store=Store()
            store.saved=mapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1000,mode=4,
                posture=TacticalPosture(2487,TacticalShipMode.STATIONED,1000)))
            val nav=Navigation(galaxy,store,NavigationPolicy())
            val departed=nav.apply(request.copy(unitId=1010000,captainCard=59,place=command),session).player!!
            assertNull(departed.posture);assertEquals(0,departed.baseId)
            assertEquals(departed,Navigation(galaxy,store,NavigationPolicy()).snapshot(1).getValue("pilot"))
        }
    }
    @Test fun nativeDepartureRequiresOwnCaptainAndFlagshipQuayAndPersistsBeforeSuccess() {
        val base = GalaxyBase(1000, 2487, 60, 0, "Home", 0, 20f, 86400, 0, 0f, 2f)
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire", 2)), listOf(base),
            Power.entries.associateWith { 2487 })
        val store = Store()
        var navigation = Navigation(galaxy, store, NavigationPolicy())
        var session = SessionSimulation(SessionRules.load()).also { it.join("pilot", Power.EMPIRE, false, "connection") }.exportState()
        fun act(move: PlaceMove) = navigation.apply(request.copy(unitId = 1010000, captainCard = 59, place = move), session)
        act(PlaceMove.Dock(1000, 4))
        session = session.copy(gameMillis = session.gameMillis + 8 * 3600_000L)
        navigation.apply(request, session)
        val port = galaxy.facilities.getValue(1000).single { it.kind == 4 }
        act(PlaceMove.Spot(port.spots.single { it.kind == 0 }.id, 0))
        assertEquals(NavigationRejection.EMBARKED, act(PlaceMove.SwitchMode(5)).rejection)
        val quay = act(PlaceMove.Spot(port.spots.single { it.kind == 6 }.id, 0)).player!!
        assertEquals(NavigationRejection.CARD, navigation.apply(request.copy(unitId = 1010000,
            captainCard = 195, place = PlaceMove.SwitchMode(5)), session).rejection)
        assertEquals(NavigationRejection.UNIT, navigation.apply(request.copy(unitId = 42,
            captainCard = 59, place = PlaceMove.SwitchMode(5)), session).rejection)
        assertEquals(NavigationRejection.BASE, act(PlaceMove.SwitchMode(4)).rejection)
        navigation = Navigation(galaxy, store, NavigationPolicy()) // Reconnect/restart at the quay.
        store.fail = true
        assertFailsWith<java.io.IOException> { act(PlaceMove.SwitchMode(5)) }
        assertEquals(quay, store.saved.getValue("pilot"))
        store.fail = false
        val departed = act(PlaceMove.SwitchMode(5)).player!!
        assertEquals(0, departed.baseId); assertEquals(0, departed.spotId); assertEquals(5, departed.mode)
        assertEquals(quay.pcp, departed.pcp); assertEquals(quay.mcp, departed.mcp)
        assertEquals(departed, store.saved.getValue("pilot"))
        assertEquals(NavigationRejection.BASE, act(PlaceMove.SwitchMode(5)).rejection)
        assertNotNull(navigation.apply(request.copy(destination = 2488, unitId = 1010000, captainCard = 59), session).player)
    }
    @Test fun dockingAndRoomMovementRespectParentsAccessCpAndDurableState() {
        val base = GalaxyBase(1000, 2487, 60, 0, "Home", 0, 20f, 86400, 0, 0f, 2f)
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire", 2), GalaxySystem(4, 2015, 4, "Alliance", 3)),
            listOf(base), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
        val store = Store()
        val navigation = Navigation(galaxy, store, NavigationPolicy())
        var session = SessionSimulation(SessionRules.load()).also { it.join("pilot", Power.EMPIRE, false, "connection") }.exportState()
        fun act(move: PlaceMove) = navigation.apply(request.copy(unitId = 1010000, captainCard = 59, place = move), session)
        navigation.apply(request, session)
        assertEquals(NavigationRejection.BASE, act(PlaceMove.Dock(1234, 0)).rejection)
        val pending = act(PlaceMove.Dock(1000, 4)).player!!
        assertEquals(840, pending.mcp); assertEquals(0, pending.baseId)
        session = session.copy(gameMillis = session.gameMillis + 8 * 3600_000L)
        val docked = navigation.apply(request, session).player!!
        assertEquals(1000, docked.baseId)
        assertEquals(NavigationRejection.EMBARKED,
            navigation.apply(request.copy(destination = 2488, unitId = 1010000, captainCard = 59), session).rejection)
        val office = galaxy.facilities.getValue(1000).single { it.kind == 0 }
        val lobby = office.spots.single { it.kind == 0 }; val meeting = office.spots.single { it.kind == 2 }
        val prison = office.spots.single { it.kind == 18 }
        assertEquals(990, act(PlaceMove.Spot(lobby.id, 0)).player!!.pcp)
        val arrived = act(PlaceMove.Spot(meeting.id, 0)).player!!
        assertEquals(985, arrived.pcp); assertEquals(arrived, store.saved.getValue("pilot"))
        val denied = act(PlaceMove.Spot(prison.id, 0))
        assertEquals(NavigationRejection.ACCESS, denied.rejection)
        assertEquals(arrived, denied.previous)
        assertEquals(arrived, store.saved.getValue("pilot"))
        // U-18: a habitable non-capital base has no mansion, so its ballroom/forest are absent;
        // the hunting ground of a fief mansion is entered with owner 0 and stored as the mover's own room.
        assertTrue(galaxy.facilities.getValue(1000).none { it.kind == 18 })
        assertEquals(NavigationRejection.SPOT, act(PlaceMove.Spot(1, 0)).rejection)
        assertEquals(NavigationRejection.EMBARKED, act(PlaceMove.Dock(0, 5)).rejection)
        assertNotNull(act(PlaceMove.Spot(0, 10000)).player)
        val quay = galaxy.facilities.getValue(1000).single { it.kind == 4 }.spots.single { it.kind == 6 }
        assertEquals(NavigationRejection.ACCESS, navigation.apply(request.copy(unitId = 42,
            place = PlaceMove.Spot(quay.id, 0)), session).rejection)
        assertNotNull(act(PlaceMove.Spot(quay.id, 0)).player)
        assertNotNull(act(PlaceMove.Spot(0, 10000)).player)
        val departed = act(PlaceMove.Dock(0, 5)).player!!
        assertEquals(0, departed.baseId); assertEquals(0, departed.spotId)
        assertEquals(departed, navigation.apply(request, session).player)
    }
    @Test fun guardedRoomsAndQuaysFollowSavedOwnerAndCampInsteadOfSeed() {
        val base = GalaxyBase(1000,2487,60,0,"Home",0,20f,86400,0,0f,2f)
        // The initial map still says Empire; ownership changes only in the saved world.
        val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(base),
            Power.entries.associateWith {2487})
        for (power in Power.entries) for (owner in listOf(2,3)) for (camp in 0..1) {
            val session = SessionSimulation(SessionRules.load()).also {
                it.join("pilot",power,false,"connection")
            }.exportState()
            val stocks = PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,owner,camp,100,100,100)))
            val friendly = owner == (if(power == Power.EMPIRE) 2 else 3) && camp == 0
            val facilities = galaxy.facilitiesAt(1000,stocks)
            val port = facilities.single {it.kind == 4}
            for (kind in listOf(0,6)) {
                val store = Store().also {it.saved = mapOf("pilot" to Navigator(1,10000,power,2487,
                    1000,1000,1000,baseId=1000,mode=4,cpRecoveryAt=0))}
                val navigation = Navigation(galaxy,store,NavigationPolicy())
                val before = store.saved.getValue("pilot")
                val result = navigation.apply(request.copy(country=power.country,unitId=1010000,
                    place=PlaceMove.Spot(port.spots.single {it.kind == kind}.id,0)),session,stocks)
                if(friendly) assertNotNull(result.player) else {
                    assertEquals(NavigationRejection.ACCESS,result.rejection)
                    assertEquals(before,store.saved.getValue("pilot"))
                }
            }
        }
    }

    private fun galaxy() = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire"), GalaxySystem(4, 2015, 4, "Alliance")),
        emptyList(), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
    private val request = NavigationRequest("pilot", 10000, Power.EMPIRE, "connection", 1)
    private class Store : NavigationStore {
        var saved = emptyMap<String, Navigator>()
        var fail = false
        override fun load() = saved
        override fun save(players: Map<String, Navigator>) {
            if (fail) throw java.io.IOException("disk failure")
            saved = players.toMap()
        }
    }
    @Test fun firstAssignedReturnPlanetUsesSavedOwnershipAndCamp() {
        val original = GalaxyBase(1000, 2487, 60, 0, "Original", 0, 20f, 86400, 0, 0f, 2f)
        val alternate = original.copy(id = 1001, name = "Alternate")
        val world = Galaxy(galaxy().systems, listOf(original, alternate), galaxy().starts,
            initialOwners = mapOf(original.id to Allegiance.Country(Power.EMPIRE.country),
                alternate.id to Allegiance.Country(Power.ALLIANCE.country)))
        val session = SessionSimulation(SessionRules.load()).also {
            it.join("pilot", Power.EMPIRE, false, "connection")
        }.exportState()
        for ((owner, camp) in listOf(3 to 0, 2 to 1)) {
            val stocks = PlanetStocks(1, 0, mapOf(
                original.id to PlanetStock(original.id, owner, camp, 100, 100, 100),
                alternate.id to PlanetStock(alternate.id, 2, 0, 100, 100, 100)))
            val store = Store()
            Navigation(world, store, NavigationPolicy()).apply(request, session, stocks)
            val restored = Navigation(world, store, NavigationPolicy()).snapshot(1).getValue("pilot")
            assertEquals(alternate.id, restored.originBase)
            assertEquals(0, restored.returnBase)
            assertEquals(alternate.id, FlagshipRecovery.defeated(restored, session.gameMillis / 1000, world, stocks).baseId)
        }
    }

    @Test fun movesPersistAndReconnectCannotResetLocationOrCp() = runBlocking {
        val store = Store()
        val home = GalaxyBase(1000, 2487, 60, 0, "Home", 0, 20f, 86400, 0, 0f, 2f)
        val world = Galaxy(galaxy().systems, listOf(home), galaxy().starts,
            initialOwners = mapOf(home.id to Allegiance.Country(Power.EMPIRE.country)))
        val engine = WorldEngine(this, ticking = false, galaxy = world, navigationStore = store)
        val join = CompletableDeferred<Admission>()
        engine.submit(WorldCommand.Join("pilot", Power.EMPIRE, false, join, "connection", 1))
        assertEquals(Admission.ACCEPTED, join.await())
        suspend fun navigate(r: NavigationRequest): NavigationResult {
            val result = CompletableDeferred<NavigationResult>()
            engine.submit(WorldCommand.Navigate(r, result)); return result.await()
        }
        assertEquals(2487, navigate(request).player!!.grid)
        val moved = navigate(request.copy(destination = 2488, unitId = 1010000, captainCard = 59)).player!!
        assertEquals(2488, moved.grid); assertEquals(960, moved.mcp); assertEquals(900, moved.cruise)
        assertEquals(home.id, moved.originBase)
        assertEquals(moved, store.saved["pilot"])
        assertEquals(moved, engine.snapshot().navigators["pilot"])
        assertEquals(moved, navigate(request).player)
        assertEquals(NavigationRejection.SESSION, navigate(request.copy(connection = "stale")).rejection)
        assertEquals(NavigationRejection.UNIT, navigate(request.copy(destination = 2489, unitId = 42, captainCard = 59)).rejection)
        engine.drainAndClose()
    }
    @Test fun failedSaveNeverPublishesMovedPosition() = runBlocking {
        val store = Store()
        val engine = WorldEngine(this, ticking = false, galaxy = galaxy(), navigationStore = store)
        val join = CompletableDeferred<Admission>()
        engine.submit(WorldCommand.Join("pilot", Power.EMPIRE, false, join, "connection", 1)); join.await()
        val initial = CompletableDeferred<NavigationResult>()
        engine.submit(WorldCommand.Navigate(request, initial)); initial.await()
        store.fail = true
        val result = CompletableDeferred<NavigationResult>()
        engine.submit(WorldCommand.Navigate(request.copy(destination = 2488, unitId = 1010000, captainCard = 59), result))
        assertFailsWith<java.io.IOException> { result.await() }
        assertEquals(2487, engine.snapshot().navigators.getValue("pilot").grid)
        assertEquals(EngineHealth.STORAGE_FAILED, engine.health())
        engine.drainAndClose()
    }
}
