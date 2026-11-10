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

    @Test fun capturedHistoricalCapitalKeepsItsPortButLosesTheFormerOwnersCapitalFacilities() {
        val odin = base(1049, 2487, 60, "オーディン")
        val heinessen = base(1177, 2015, 61, "ハイネセン")
        val galaxy = Galaxy(listOf(GalaxySystem(3, odin.grid, 4, "Valhalla", 2),
            GalaxySystem(4, heinessen.grid, 4, "Bharat", 3)), listOf(odin, heinessen),
            mapOf(Power.EMPIRE to odin.grid, Power.ALLIANCE to heinessen.grid))
        val original = PlanetStocks(1, 0, mapOf(odin.id to PlanetStock(odin.id, 2, 0, 0, 0, 0),
            heinessen.id to PlanetStock(heinessen.id, 3, 0, 0, 0, 0)))
        assertTrue(galaxy.facilitiesAt(odin.id, original).any { it.kind == 22 })
        assertTrue(galaxy.facilitiesAt(heinessen.id, original).any { it.kind == 24 })

        val captured = original.copy(bases = original.bases + (odin.id to PlanetStock(odin.id, 3, 0, 0, 0, 0)))
        val facilities = galaxy.facilitiesAt(odin.id, captured)
        val oldOffice = galaxy.facilitiesAt(odin.id, original).single { it.kind == 30 }.spots.single { it.kind == 7 }
        val pilot = Navigator(1, 10000, Power.ALLIANCE, odin.grid, 1000, 1000, 900, baseId = odin.id, mode = 4)
        val session = SessionSimulation(SessionRules.load()).also { it.join("pilot", Power.ALLIANCE, false, "c") }.exportState()
        val navigation = Navigation(galaxy, object : NavigationStore {
            override fun load() = mapOf("pilot" to pilot)
            override fun save(players: Map<String, Navigator>) = error("expected a read-only preview")
        }, NavigationPolicy())
        fun enter(spot: PlanetSpot) = navigation.apply(NavigationRequest("pilot", pilot.characterId, Power.ALLIANCE, "c", 1,
            unitId = pilot.unitId, place = PlaceMove.Spot(spot.id, 0)), session, captured, persist = false)
        assertEquals(NavigationRejection.SPOT, enter(oldOffice).rejection)

        assertEquals(Places.forBase(odin, 3, capitalSystem = 0), facilities)
        val quay = facilities.single { it.kind == 4 }.spots.single { it.kind == 6 }
        assertEquals(7, quay.image)
        assertEquals(quay.id, assertNotNull(enter(quay).player).spotId)
        assertTrue(galaxy.facilitiesAt(heinessen.id, captured).any { it.kind == 24 })

        val moved = captured.copy(strategic = captured.strategic.copy(capitals = mapOf(Power.ALLIANCE.country to odin.id)))
        assertEquals(Places.forBase(odin, 3, capitalSystem = 3), galaxy.facilitiesAt(odin.id, moved))
        assertFalse(galaxy.facilitiesAt(heinessen.id, moved).any { it.kind == 24 })
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
