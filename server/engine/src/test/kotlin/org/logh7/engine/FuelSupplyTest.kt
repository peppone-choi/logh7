package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class FuelSupplyTest {
    private val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire"), GalaxySystem(4, 2015, 4, "Alliance")),
        emptyList(), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
    private val request = NavigationRequest("pilot", 10000, Power.EMPIRE, "connection", 1, unitId = 1010000, captainCard = 59)
    private val start = SessionRules.load().startSeconds
    private fun session(seconds: Long) = SessionState(1, seconds * 1000, 0, 0,
        mapOf("pilot" to Participant(Power.EMPIRE, false, true, "connection")), emptyMap(), emptyMap(), null)
    private class Store(var saved: Map<String, Navigator>) : NavigationStore {
        var fail = false
        override fun load() = saved
        override fun save(players: Map<String, Navigator>) {
            if (fail) throw java.io.IOException("disk failed")
            saved = players.toMap()
        }
    }
    private fun store(cruise: Int = 900, mcp: Int = 1000, pcp: Int = 1000) = Store(mapOf("pilot" to
        Navigator(1, 10000, Power.EMPIRE, 2487, pcp, mcp, cruise, cpRecoveryAt = start)))
    @Test fun onePaymentPersistsDeadlineAndReconnectCompletesExactlyOnce() {
        val store = store()
        val navigation = Navigation(galaxy, store, NavigationPolicy())
        val started = navigation.apply(request.copy(supplyFuel = true), session(start)).player!!
        assertEquals(840, started.mcp); assertEquals(900, started.cruise)
        assertEquals(start + 96 * 3600, started.fuelReadyAt)
        assertEquals(started, store.saved.getValue("pilot"))
        val again = navigation.apply(request.copy(supplyFuel = true), session(start))
        assertEquals(NavigationRejection.SUPPLYING, again.rejection)
        assertEquals(840, again.previous!!.mcp)
        assertEquals(NavigationRejection.SUPPLYING, navigation.apply(request.copy(destination = 2488), session(start)).rejection)
        val reopened = Navigation(galaxy, store, NavigationPolicy())
        val readyAt = assertNotNull(started.fuelReadyAt)
        assertEquals(900, reopened.apply(request, session(readyAt - 1)).player!!.cruise)
        val completion = reopened.apply(request, session(readyAt))
        assertTrue(completion.completedFuel)
        val complete = completion.player!!
        assertEquals(1000, complete.cruise); assertNull(complete.fuelReadyAt)
        assertEquals(complete, reopened.apply(request, session(readyAt)).player)
        assertFalse(reopened.apply(request, session(readyAt)).completedFuel)
        assertEquals(NavigationRejection.FUEL_FULL, reopened.apply(request.copy(supplyFuel = true), session(readyAt)).rejection)
        assertNotNull(reopened.apply(request.copy(destination = 2488), session(readyAt)).player)
    }
    @Test fun rejectsUnauthorizedOrUnpayableRefillsWithoutCreatingJobs() {
        val store = store(mcp = 159, pcp = 319)
        val navigation = Navigation(galaxy, store, NavigationPolicy())
        for ((r, reason) in listOf(
            request.copy(connection = "stale") to NavigationRejection.SESSION,
            request.copy(unitId = 42) to NavigationRejection.UNIT,
            request.copy(captainCard = 195) to NavigationRejection.CARD,
            request to NavigationRejection.CP)) {
            assertEquals(reason, navigation.apply(r.copy(supplyFuel = true), session(start)).rejection)
            assertNull(store.saved.getValue("pilot").fuelReadyAt)
            assertEquals(159, store.saved.getValue("pilot").mcp)
        }
        store.saved = mapOf("pilot" to store.saved.getValue("pilot").copy(baseId = 1000, mode = 4))
        assertEquals(NavigationRejection.EMBARKED, Navigation(galaxy, store, NavigationPolicy())
            .apply(request.copy(supplyFuel = true), session(start)).rejection)
    }
    @Test fun emptyFuelUsesTheManualMaximumTimeAndFailedSavePublishesNothing() {
        val store = store(cruise = 0)
        val navigation = Navigation(galaxy, store, NavigationPolicy())
        store.fail = true
        assertFailsWith<java.io.IOException> { navigation.apply(request.copy(supplyFuel = true), session(start)) }
        assertNull(store.saved.getValue("pilot").fuelReadyAt)
        store.fail = false
        val started = navigation.apply(request.copy(supplyFuel = true), session(start)).player!!
        assertEquals(start + 960 * 3600, started.fuelReadyAt)
        store.fail = true
        assertFailsWith<java.io.IOException> { navigation.apply(request, session(started.fuelReadyAt!!)) }
        assertEquals(0, navigation.snapshot(1).getValue("pilot").cruise)
        store.fail = false
        assertEquals(1000, navigation.apply(request, session(started.fuelReadyAt!!)).player!!.cruise)
    }
    @Test fun smallRefillUsesMinimumTimeAndExistingDoublePcpSubstitution() {
        val navigation = Navigation(galaxy, store(cruise = 999, mcp = 0), NavigationPolicy())
        val started = navigation.apply(request.copy(supplyFuel = true), session(start)).player!!
        assertEquals(680, started.pcp); assertEquals(0, started.mcp)
        assertEquals(start + 48 * 3600, started.fuelReadyAt)
    }
    @Test fun aRejectedCommandCanSettleAndReportAnExpiredRefillWithoutChargingAgain() {
        val navigation = Navigation(galaxy, store(cruise = 999), NavigationPolicy())
        val started = navigation.apply(request.copy(supplyFuel = true), session(start)).player!!
        val readyAt = assertNotNull(started.fuelReadyAt)
        val completed = navigation.apply(request.copy(supplyFuel = true), session(readyAt))
        assertEquals(NavigationRejection.FUEL_FULL, completed.rejection)
        assertTrue(completed.completedFuel)
        val player = assertNotNull(completed.previous)
        assertEquals(1000, player.cruise); assertNull(player.fuelReadyAt)
        assertFalse(navigation.apply(request, session(readyAt)).completedFuel)
    }
}
