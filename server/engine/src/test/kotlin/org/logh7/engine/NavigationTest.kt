package org.logh7.engine

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class NavigationTest {
    @Test fun dockingAndRoomMovementRespectParentsAccessCpAndDurableState() {
        val base = GalaxyBase(1000, 2487, 60, 0, "Home", 0, 20f, 86400, 0, 0f, 2f)
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire", 2), GalaxySystem(4, 2015, 4, "Alliance", 3)),
            listOf(base), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
        val store = Store()
        val navigation = Navigation(galaxy, store, NavigationPolicy())
        val session = SessionSimulation(SessionRules.load()).also { it.join("pilot", Power.EMPIRE, false, "connection") }.exportState()
        fun act(move: PlaceMove) = navigation.apply(request.copy(unitId = 1010000, captainCard = 59, place = move), session)
        navigation.apply(request, session)
        assertEquals(NavigationRejection.BASE, act(PlaceMove.Dock(1234, 0)).rejection)
        val docked = act(PlaceMove.Dock(1000, 4)).player!!
        assertEquals(840, docked.mcp); assertEquals(1000, docked.baseId)
        assertEquals(NavigationRejection.EMBARKED,
            navigation.apply(request.copy(destination = 2488, unitId = 1010000, captainCard = 59), session).rejection)
        val office = galaxy.facilities.getValue(1000).single { it.kind == 0 }
        val lobby = office.spots.single { it.kind == 0 }; val meeting = office.spots.single { it.kind == 2 }
        val prison = office.spots.single { it.kind == 18 }
        assertEquals(990, act(PlaceMove.Spot(lobby.id, 0)).player!!.pcp)
        val arrived = act(PlaceMove.Spot(meeting.id, 0)).player!!
        assertEquals(985, arrived.pcp); assertEquals(arrived, store.saved.getValue("pilot"))
        assertEquals(NavigationRejection.ACCESS, act(PlaceMove.Spot(prison.id, 0)).rejection)
        assertEquals(NavigationRejection.SPOT, act(PlaceMove.Spot(1, 0)).rejection)
        assertEquals(NavigationRejection.EMBARKED, act(PlaceMove.Dock(0, 5)).rejection)
        assertNotNull(act(PlaceMove.Spot(0, 10000)).player)
        val departed = act(PlaceMove.Dock(0, 5)).player!!
        assertEquals(0, departed.baseId); assertEquals(0, departed.spotId)
        assertEquals(departed, navigation.apply(request, session).player)
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
    @Test fun movesPersistAndReconnectCannotResetLocationOrCp() = runBlocking {
        val store = Store()
        val engine = WorldEngine(this, ticking = false, galaxy = galaxy(), navigationStore = store)
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
