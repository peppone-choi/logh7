package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class PlacesTest {
    private fun base(id: Int, grid: Int, model: Int, name: String, kind: Int = 0) =
        GalaxyBase(id, grid, model, kind, name, 0, 20f, 86400, 0, 0f, 2f)

    @Test fun facilitiesHaveUniqueStableIdsAndRoomsStayWithinClientLimits() {
        val bases = listOf(base(1000, 2487, 60, "オーディン"), base(1001, 2015, 61, "ハイネセン"),
            base(1002, 2000, 30, "Gas"), base(65535, 2000, 110, "Fortress", 1))
        val facilities = bases.flatMap { Places.forBase(it, if (it.grid == 2015) 3 else 2) }
        assertEquals(facilities.size, facilities.map { it.id }.distinct().size)
        assertTrue(bases.all { Places.forBase(it).size <= 36 })
        assertTrue(facilities.all { it.baseId in bases.map { b -> b.id } && it.spots.size <= 20 })
        val spots = facilities.flatMap { it.spots }
        assertEquals(spots.size, spots.map { it.id }.distinct().size)
        assertTrue(spots.all { it.id > 0 && it.image in 1..43 })
        assertEquals(facilities.takeWhile { it.baseId == 1000 }, Places.forBase(bases[0], 2))
        assertTrue(22 in Places.forBase(bases[0], 2).map { it.kind })
        assertTrue(24 in Places.forBase(bases[1], 3).map { it.kind })
        assertFalse(3 in Places.forBase(bases[2], 2).map { it.kind }) // no park on gas outpost
    }

    @Test fun capitalsRequireHabitabilityAndAtmosphereWhileOutpostsRemainArtificial() {
        val capital = BaseCondition.initial(base(1, 2487, 60, "オーディン"), 2)
        assertEquals(1, capital.habitability); assertEquals(1, capital.atmosphere)
        assertEquals(2, capital.power); assertTrue(capital.adultPopulation <= capital.population)
        assertEquals(0, BaseCondition.initial(base(2, 2000, 30, "Gas"), 3).habitability)
        assertFailsWith<IllegalArgumentException> { BaseCondition.initial(base(1, 2487, 30, "オーディン"), 2) }
    }

    @Test fun backgroundAssignmentsDistinguishPublicRoomsAndFactionArchitecture() {
        assertEquals(23, Places.image(2, 2)); assertEquals(21, Places.image(2, 3))
        assertEquals(5, Places.image(6, 2)); assertEquals(7, Places.image(6, 3))
        assertEquals(12, Places.image(16, 2)); assertEquals(13, Places.image(17, 3))
        assertEquals(39, Places.image(19, 2)); assertEquals(40, Places.image(20, 2))
        assertEquals(SpotAccess.CLOSED, Places.access(18))
        assertEquals(SpotAccess.PRIVATE, Places.access(63))
        assertEquals(SpotAccess.PUBLIC, Places.access(3))
    }
}
