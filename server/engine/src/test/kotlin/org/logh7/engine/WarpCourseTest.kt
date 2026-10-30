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

    @Test fun navigationChargesDistanceAndKeepsStateOnShortCruise() {
        val store = object : NavigationStore {
            var saved = emptyMap<String, Navigator>()
            override fun load() = saved
            override fun save(players: Map<String, Navigator>) { saved = players.toMap() }
        }
        val session = SessionSimulation(SessionRules.load()).also { it.join("pilot", Power.EMPIRE, false, "connection") }.exportState()
        val request = NavigationRequest("pilot", 10000, Power.EMPIRE, "connection", 1, unitId = 1010000, captainCard = 59)
        val navigation = Navigation(galaxy, store, NavigationPolicy())
        val diagonal = navigation.apply(request.copy(destination = 2588), session).player!!
        assertEquals(2588, diagonal.grid); assertEquals(858, diagonal.cruise); assertEquals(960, diagonal.mcp)
        assertEquals(NavigationRejection.RANGE, navigation.apply(request.copy(destination = 2891), session).rejection)
        val far = navigation.apply(request.copy(destination = 2888), session).player!!
        assertEquals(558, far.cruise); assertEquals(920, far.mcp)
        assertTrue(far.grid == 2888 || WarpCourse.cruiseCost(2888, far.grid) in setOf(100, 142))
        store.saved = mapOf("pilot" to far.copy(cruise = 250))
        val short = Navigation(galaxy, store, NavigationPolicy()).apply(request.copy(destination = far.grid + 3), session)
        assertEquals(NavigationRejection.CRUISE, short.rejection)
        assertEquals(far.copy(cruise = 250), store.saved.getValue("pilot"))
    }
}
