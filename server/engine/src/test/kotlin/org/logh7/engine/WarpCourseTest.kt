package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class WarpCourseTest {
    private val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire", 2), GalaxySystem(4, 2015, 4, "Alliance", 3),
        GalaxySystem(5, 2591, 4, "Edge", 2)), emptyList(), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))

    @Test fun cruiseIsTheLightYearDistance() {
        assertEquals(100, WarpCourse.cruiseCost(2487, 2488))
        assertEquals(142, WarpCourse.cruiseCost(2487, 2588))
        assertEquals(200, WarpCourse.cruiseCost(2487, 2687))
        assertEquals(224, WarpCourse.cruiseCost(2487, 2589))
        assertEquals(283, WarpCourse.cruiseCost(2487, 2689))
        assertEquals(300, WarpCourse.cruiseCost(2487, 2490))
    }

    @Test fun onlyLongSpaceWarpsDrift() {
        assertEquals(0, WarpCourse.errorPercent(2487, 2588, false))
        assertEquals(10, WarpCourse.errorPercent(2487, 2589, false))
        assertEquals(20, WarpCourse.errorPercent(2487, 2490, false))
        assertEquals(0, WarpCourse.errorPercent(2487, 2490, true))
        repeat(200) { salt -> assertEquals(2488, WarpCourse.arrival(galaxy, 2487, 2488, salt.toLong()) { true }) }
    }

    @Test fun driftIsReproducibleAndLandsOnEnterableNeighbouringSpace() {
        val arrivals = (0L until 1000).map { salt -> WarpCourse.arrival(galaxy, 2487, 2490, salt) { true } }
        assertEquals(arrivals, (0L until 1000).map { salt -> WarpCourse.arrival(galaxy, 2487, 2490, salt) { true } })
        val neighbours = setOf(2389, 2390, 2391, 2489, 2491, 2589, 2591, 2590)
        assertTrue(arrivals.all { it == 2490 || it in neighbours })
        assertFalse(2591 in arrivals) // A star system is never a drift destination.
        val drifted = arrivals.count { it != 2490 }
        assertTrue(drifted in 120..280, "drift count $drifted")
        val blocked = (0L until 1000).map { salt -> WarpCourse.arrival(galaxy, 2487, 2490, salt) { it == 2389 } }
        assertTrue(blocked.all { it == 2490 || it == 2389 }); assertEquals(drifted, blocked.count { it == 2389 })
        assertTrue((0L until 1000).all { salt -> WarpCourse.arrival(galaxy, 2487, 2490, salt) { false } == 2490 })
    }

    private class Store : NavigationStore {
        var saved = emptyMap<String, Navigator>()
        override fun load() = saved
        override fun save(players: Map<String, Navigator>) { saved = players.toMap() }
    }
    private val start = SessionSimulation(SessionRules.load()).also { it.join("pilot", Power.EMPIRE, false, "connection") }.exportState()
    private val request = NavigationRequest("pilot", 10000, Power.EMPIRE, "connection", 1, unitId = 1010000, captainCard = 59)
    private fun later(hours: Long) = start.copy(gameMillis = start.gameMillis + hours * 3600_000L)

    @Test fun adjacentWarpIsImmediateAtTheBaseCost() {
        val moved = Navigation(galaxy, Store(), NavigationPolicy()).apply(request.copy(destination = 2488), start).player!!
        assertEquals(2488, moved.grid); assertEquals(960, moved.mcp); assertEquals(900, moved.cruise); assertNull(moved.warpTarget)
    }

    @Test fun longerWarpsReserveDistanceCpAndExecuteAfterTheWait() {
        val store = Store()
        var navigation = Navigation(galaxy, store, NavigationPolicy())
        val reserved = navigation.apply(request.copy(destination = 2588), start)
        val pending = reserved.player!!
        assertEquals(2487, pending.grid); assertEquals(2588, pending.warpTarget); assertEquals(920, pending.mcp)
        assertEquals(1000, pending.cruise); assertEquals(start.gameMillis / 1000 + 3600, pending.warpReadyAt)
        assertEquals(pending, store.saved.getValue("pilot"))
        assertEquals(NavigationRejection.TRAVELLING, navigation.apply(request.copy(destination = 2488), later(0)).rejection)
        navigation = Navigation(galaxy, store, NavigationPolicy()) // A restart keeps the reservation.
        val early = navigation.apply(request, start.copy(gameMillis = start.gameMillis + 3599_000L))
        assertEquals(2487, early.player!!.grid); assertFalse(early.completedWarp)
        val arrived = navigation.apply(request, later(1))
        assertTrue(arrived.completedWarp); assertFalse(arrived.warpRejected)
        assertEquals(2588, arrived.player!!.grid); assertEquals(858, arrived.player.cruise); assertNull(arrived.player.warpTarget)
        assertEquals(1, arrived.player.moves); assertEquals(arrived.player, store.saved.getValue("pilot"))
        // Three grids: 120 CP (40 per started 100 ly) and two G-hours.
        val three = navigation.apply(request.copy(destination = 2288), later(1)).player!!
        assertEquals(2288, three.warpTarget); assertEquals(three.mcp, arrived.player.mcp - 120)
        assertEquals(later(1).gameMillis / 1000 + 7200, three.warpReadyAt)
    }

    @Test fun anExecutionThatCanNoLongerProceedKeepsTheOriginWithoutRefund() {
        val store = Store()
        val navigation = Navigation(galaxy, store, NavigationPolicy())
        val pending = navigation.apply(request.copy(destination = 2687), start).player!!
        assertEquals(920, pending.mcp)
        store.saved = mapOf("pilot" to pending.copy(cruise = 150)) // Fuel spent elsewhere before execution.
        val result = Navigation(galaxy, store, NavigationPolicy()).apply(request, later(1))
        assertTrue(result.completedWarp); assertTrue(result.warpRejected)
        assertEquals(2487, result.player!!.grid); assertEquals(150, result.player.cruise); assertNull(result.player.warpTarget)
        assertEquals(920, result.player.mcp); assertEquals(0, result.player.moves)
    }

    @Test fun shortCruiseIsRejectedBeforeAnyPayment() {
        val store = Store()
        val navigation = Navigation(galaxy, store, NavigationPolicy())
        navigation.apply(request, start)
        store.saved = store.saved.mapValues { (_, p) -> p.copy(cruise = 250) }
        val short = Navigation(galaxy, store, NavigationPolicy()).apply(request.copy(destination = 2490), start)
        assertEquals(NavigationRejection.CRUISE, short.rejection)
        assertEquals(1000, store.saved.getValue("pilot").mcp); assertNull(store.saved.getValue("pilot").warpTarget)
        assertEquals(NavigationRejection.RANGE, Navigation(galaxy, store, NavigationPolicy())
            .apply(request.copy(destination = 2891), start).rejection)
    }
}
