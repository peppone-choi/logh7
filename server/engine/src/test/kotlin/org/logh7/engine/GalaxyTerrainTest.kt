package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** Manual p.101 navigable outline, corridor plasma storms and black-hole gravity. Author: Peppone Choi. */
class GalaxyTerrainTest {
    private val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Odin", 2), GalaxySystem(4, 2015, 4, "Heinessen", 3),
        GalaxySystem(5, 1253, 4, "Iserlohn", 2), GalaxySystem(6, 3751, 4, "Fezzan", 0)), emptyList(),
        mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015), terrain = Galaxy.manualTerrain())

    @Test fun manualOutlineKeepsOnlyTheTwoCorridorsOpen() {
        assertEquals(GridKind.IMPASSABLE, galaxy.kind(0)) // Outer corner.
        assertEquals(GridKind.STAR_SYSTEM, galaxy.kind(1253)); assertEquals(GridKind.STAR_SYSTEM, galaxy.kind(3751))
        assertEquals(GridKind.SPACE, galaxy.kind(1252)); assertEquals(GridKind.SPACE, galaxy.kind(3750)) // Corridor cells.
        assertEquals(GridKind.SPACE, galaxy.kind(2051)); assertEquals(GridTerrain.PLASMA_STORM, galaxy.terrain(2051))
        assertEquals(GridTerrain.NONE, galaxy.terrain(0))
        // Rows 13..36 have no navigable cell between the lobes: the corridors are the only crossings.
        for (y in 13..36) assertTrue((48..55).any { galaxy.terrain(y * 100 + it) == GridTerrain.PLASMA_STORM }, "row $y")
        assertEquals(listOf(831, 3060, 3723), galaxy.blackHoles)
        assertTrue(galaxy.blackHoles.all { galaxy.kind(it) == GridKind.IMPASSABLE })
        assertTrue(Galaxy.neutronStars.all { galaxy.kind(it) == GridKind.SPACE })
        assertFailsWith<IllegalArgumentException> {
            Galaxy(listOf(GalaxySystem(3, 0, 4, "Outside")), emptyList(), Power.entries.associateWith { 0 },
                terrain = Galaxy.manualTerrain())
        }
    }

    @Test fun plasmaStormsAndBlackHolesRejectEntry() {
        val state = GridForces.state(galaxy, 2051, emptyMap())
        assertEquals(GridEntryVerdict.TERRAIN_BLOCKED, GridEntryRules.evaluate(state, GridEntry(Camp(Power.EMPIRE, false), 1, true)))
        assertEquals(GridEntryVerdict.IMPASSABLE_GRID, GridEntryRules.evaluate(GridForces.state(galaxy, 3060, emptyMap()),
            GridEntry(Camp(Power.EMPIRE, false), 1, true)))
    }

    @Test fun gravityAddsDriftAndPullsTowardTheBlackHole() {
        // 3059 is next to the black hole at 3060; an adjacent warp normally never drifts.
        val arrivals = (0L until 2000).map { salt -> WarpCourse.arrival(galaxy, 3058, 3059, salt) { true } }
        val drifted = arrivals.filter { it != 3059 }
        assertTrue(drifted.size in 300..500, "drift ${drifted.size}")
        assertTrue(drifted.all { it == 2960 || it == 3160 }, drifted.distinct().toString())
        assertFalse(3060 in arrivals)
        assertEquals(3159, WarpCourse.arrival(galaxy, 3158, 3159, 7) { false }) // No enterable neighbour.
    }
}
