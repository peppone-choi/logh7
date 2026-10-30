package org.logh7.engine

/** CD manual PDF p.32-34: kinds are constmsg groups 73/74/75. Author: Peppone Choi. */
enum class SpotAccess(val wire: Int) { CLOSED(0), PUBLIC(1), PRIVATE(2), RANK(3), GUARDED(4) }
data class PlanetSpot(val id: Int, val kind: Int, val image: Int, val access: SpotAccess)
data class PlanetFacility(val id: Int, val kind: Int, val baseId: Int, val spots: List<PlanetSpot>)

/** Facility/room types follow the manual; per-planet placement and images are design-fallback-v1. */
object Places {
    private val rooms = mapOf(
        0 to listOf(0, 2, 18), 1 to listOf(0), 2 to listOf(3), 3 to listOf(4),
        4 to listOf(0, 5, 6), 5 to listOf(7), 6 to listOf(7), 7 to listOf(7),
        8 to listOf(7), 9 to listOf(7), 10 to listOf(7), 11 to listOf(7),
        12 to listOf(7), 13 to listOf(7), 14 to listOf(8), 15 to listOf(9, 10, 11),
        16 to listOf(12), 17 to listOf(1, 13), 18 to listOf(0, 14, 13, 15),
        19 to listOf(1), 20 to listOf(0, 16, 17), 21 to listOf(8),
        22 to listOf(0, 19, 20, 22), 23 to listOf(21), 24 to listOf(0, 2, 67),
        25 to listOf(0, 2, 104), 29 to listOf(0, 2), 30 to listOf(0, 7),
        31 to listOf(0, 2), 32 to listOf(0, 18), 33 to listOf(0, 2),
        34 to listOf(0, 2), 35 to listOf(0, 2), 36 to listOf(0, 2),
        37 to listOf(0, 2), 38 to listOf(0, 2))
    /** Original images inspected visually; assignments are design, not an original lookup table. */
    fun image(kind: Int, power: Int): Int {
        val empire = power == 2
        return when (kind) {
            0, 1 -> if (empire) 16 else 19 // marble / modern public lobby
            2 -> if (empire) 23 else 21 // conference room
            3 -> if (empire) 2 else 3
            4 -> 4
            5, 8 -> 24 // control room
            6 -> if (empire) 5 else 7 // flagship hangar
            7 -> if (empire) 31 else 32 // administrative office
            9, 10 -> if (empire) 14 else 8 // officers' club
            11 -> 9 // bar
            12 -> if (empire) 34 else 33 // residence
            13 -> if (empire) 35 else 36 // hotel room
            14 -> 17 // ballroom
            15 -> 11 // forest
            16 -> 12 // lecture theatre
            17 -> 13 // tactical simulator
            18 -> 1 // holding room; no labelled prison image survives
            19 -> 39 // Black Pearl chamber
            20 -> 40 // imperial bedroom
            21 -> if (empire) 18 else 19 // reception
            22 -> 26 // throne room
            else -> if (empire) 29 else 30 // official's office
        }
    }
    fun access(kind: Int): SpotAccess = when (kind) {
        0 -> SpotAccess.GUARDED
        18 -> SpotAccess.CLOSED
        6, 12, 13, 16, 17, 19, 20 -> SpotAccess.PRIVATE
        9, 10 -> SpotAccess.RANK
        in 22..104 -> SpotAccess.PRIVATE
        else -> SpotAccess.PUBLIC
    }
    fun forBase(base: GalaxyBase, power: Int = 0): List<PlanetFacility> {
        val capitalEmpire = base.grid == 2487 && base.name == "オーディン"
        val capitalAlliance = base.grid == 2015 && base.name == "ハイネセン"
        val kinds = when {
            // The manual's automatic-production table includes ships at fortresses too.
            base.kind == 1 -> listOf(1, 2, 4, 9, 10, 11, 13, 17, 21)
            BaseCondition.habitable(base) -> (0..17).toList() + listOf(19)
            else -> listOf(0, 1, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 17)
        }
        val additions = when {
            capitalEmpire -> listOf(18, 20, 22, 23, 29, 30, 31, 32, 35, 37, 38)
            capitalAlliance -> listOf(20, 23, 24, 29, 33, 34, 36)
            else -> emptyList()
        }
        return (kinds + additions).distinct().map { kind ->
            val facilityId = Math.addExact(Math.multiplyExact(base.id, 100), kind + 1)
            val offices = when {
                kind == 0 && power == 2 -> listOf(63)
                kind == 0 && power == 3 -> listOf(if (capitalAlliance) 102 else 101)
                kind == 1 && power == 2 -> listOf(if (capitalEmpire) 65 else 64)
                kind == 1 && capitalAlliance -> listOf(103)
                else -> emptyList()
            }
            PlanetFacility(facilityId, kind, base.id, (rooms.getValue(kind) + offices).map { room ->
                PlanetSpot(Math.addExact(Math.multiplyExact(base.id, 10_000), kind * 200 + room + 1),
                    room, image(room, power), access(room))
            })
        }
    }
}
