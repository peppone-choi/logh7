package org.logh7.engine

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class NavigationTest {
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
