package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class DockingWorkTest {
    private val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Home", 2)),
        listOf(1000, 1001).map { GalaxyBase(it, 2487, 60, 0, "Base", 0, 20f, 86400, 0, 0f, 2f) },
        Power.entries.associateWith { 2487 })
    private val request = NavigationRequest("pilot", 10000, Power.EMPIRE, "connection", 1, unitId = 1010000, captainCard = 59)
    private fun session(seconds: Long) = SessionState(1, seconds * 1000, 0, 0,
        mapOf("pilot" to Participant(Power.EMPIRE, false, true, "connection")), emptyMap(), emptyMap(), null)
    private class Store : NavigationStore {
        var players = mapOf("pilot" to Navigator(1, 10000, Power.EMPIRE, 2487, 1000, 1000, 900, cpRecoveryAt = 0))
        var fail = false
        override fun load() = players
        override fun save(players: Map<String, Navigator>) {
            if (fail) throw java.io.IOException("disk failure")
            this.players = players
        }
    }
    @Test fun waitKeepsOriginChargesOnceAndSurvivesRestartWithoutRefillingFuel() {
        val store = Store()
        var nav = Navigation(galaxy, store, NavigationPolicy())
        val dock = request.copy(place = PlaceMove.Dock(1000, 4))
        val queued = nav.apply(dock, session(0)).player!!
        assertEquals(840, queued.mcp); assertEquals(0, queued.baseId); assertEquals(5, queued.mode)
        assertEquals(1000, queued.dockTarget); assertEquals(28800L, queued.dockReadyAt)
        assertEquals(0, queued.moves)
        for (command in listOf(dock, request.copy(destination = 2488), request.copy(supplyFuel = true),
            request.copy(place = PlaceMove.Spot(10000001, 0)))) {
            assertEquals(NavigationRejection.TRAVELLING, nav.apply(command, session(0)).rejection)
            assertEquals(queued, store.players.getValue("pilot"))
        }
        nav = Navigation(galaxy, store, NavigationPolicy())
        assertFalse(nav.apply(request, session(28799)).completedDock)
        assertEquals(0, nav.snapshot(1).getValue("pilot").baseId)
        val arrival = nav.apply(request, session(28800))
        assertTrue(arrival.completedDock); assertFalse(arrival.completedFuel)
        val arrived = arrival.player!!
        assertEquals(1000, arrived.baseId); assertEquals(4, arrived.mode); assertEquals(900, arrived.cruise)
        assertNull(arrived.dockTarget); assertNull(arrived.dockReadyAt); assertEquals(1, arrived.moves)
        assertTrue(arrived.strategy!!.jobs.isEmpty())
        nav = Navigation(galaxy, store, NavigationPolicy())
        assertFalse(nav.apply(request, session(28800)).completedDock)
        assertEquals(arrived, nav.snapshot(1).getValue("pilot"))
        val next = nav.apply(request.copy(place = PlaceMove.Dock(1001, 4)), session(28800)).player!!
        assertEquals(1000, next.baseId); assertEquals(1001, next.dockTarget)
        assertEquals(1L, next.strategy!!.jobs.single().id)
        assertEquals(1001, nav.apply(request, session(57600)).player!!.baseId)
    }
    @Test fun failedAcceptanceAndArrivalWritesCannotPublishJobPaymentOrLocation() {
        val store = Store()
        val nav = Navigation(galaxy, store, NavigationPolicy())
        store.fail = true
        assertFailsWith<java.io.IOException> { nav.apply(request.copy(place = PlaceMove.Dock(1000, 4)), session(0)) }
        assertNull(nav.snapshot(1).getValue("pilot").dockTarget)
        assertEquals(1000, nav.snapshot(1).getValue("pilot").mcp)
        store.fail = false
        nav.apply(request.copy(place = PlaceMove.Dock(1000, 4)), session(0))
        store.fail = true
        assertFailsWith<java.io.IOException> { nav.apply(request, session(28800)) }
        val waiting = nav.snapshot(1).getValue("pilot")
        assertEquals(0, waiting.baseId); assertEquals(840, waiting.mcp); assertEquals(1000, waiting.dockTarget)
        store.fail = false
        assertTrue(nav.apply(request, session(28800)).completedDock)
    }
    @Test fun aDeniedCommandAtTheDeadlineStillReportsTheDurableArrival() {
        val nav = Navigation(galaxy, Store(), NavigationPolicy())
        nav.apply(request.copy(place = PlaceMove.Dock(1000, 4)), session(0))
        val result = nav.apply(request.copy(destination = 2488), session(28800))
        assertEquals(NavigationRejection.EMBARKED, result.rejection)
        assertTrue(result.completedDock); assertEquals(1000, result.previous!!.baseId)
        assertFalse(nav.apply(request, session(28800)).completedDock)
    }
}
