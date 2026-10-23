package org.logh7.engine

enum class GridKind { SPACE, STAR_SYSTEM, IMPASSABLE }
enum class GridTerrain { NONE, PLASMA_STORM, SARGASSO_SPACE }

/** 같은 세력이라도 정규군과 반란군은 별도 진영이다. W p.31. */
data class Camp(val power: Power, val insurgent: Boolean)

/** units 집계는 호출자 책임이며 기함/탑재 유닛 계산을 추측하지 않는다. */
data class CampPresence(
    val camp: Camp,
    val units: Int,
    val hasNonSoloUnit: Boolean,
    val hasShipUnit: Boolean,
    val hasPlanetOrFortress: Boolean
)

data class GridState(val kind: GridKind, val terrain: GridTerrain, val inTactics: Boolean, val presences: List<CampPresence>)

/** units는 진입하려는 부대 전체의 유닛 수다. 일부만 진입시키지 않는다. */
data class GridEntry(val camp: Camp, val units: Int, val soloShip: Boolean)

enum class GridEntryVerdict { ALLOWED, IMPASSABLE_GRID, TERRAIN_BLOCKED, CAMP_LIMIT, UNIT_LIMIT, SOLO_SHIP_BLOCKED }

/** W p.31 요약에 따른 순수 판정. 이동·전술 개시·상태 변경은 수행하지 않는다. */
object GridEntryRules {
    const val MAX_UNITS_PER_CAMP = 300
    const val MAX_CAMPS = 2

    fun evaluate(grid: GridState, entry: GridEntry): GridEntryVerdict {
        require(entry.units >= 1)
        require(grid.presences.all { it.units in 0..MAX_UNITS_PER_CAMP })
        require(grid.presences.map { it.camp }.distinct().size == grid.presences.size)
        require(grid.presences.size <= MAX_CAMPS)

        if (grid.kind == GridKind.IMPASSABLE) return GridEntryVerdict.IMPASSABLE_GRID
        if (grid.terrain != GridTerrain.NONE) return GridEntryVerdict.TERRAIN_BLOCKED

        val own = grid.presences.find { it.camp == entry.camp }
        if (own == null && grid.presences.size >= MAX_CAMPS) return GridEntryVerdict.CAMP_LIMIT
        val ownUnits = own?.units ?: 0
        if (entry.units > MAX_UNITS_PER_CAMP - ownUnits) return GridEntryVerdict.UNIT_LIMIT

        if (entry.soloShip) {
            val enemies = grid.presences.filter { it.camp != entry.camp }
            // R5 잠정 해석(SPEC §3): 적 비독행 유닛은 모든 그리드, 적 행성/요새는 성계만.
            // 일본어 원문·원작 동작 대조 전이므로 원작 확정 사실로 취급하지 않는다.
            val blocked = enemies.any { it.hasNonSoloUnit } ||
                (grid.kind == GridKind.STAR_SYSTEM && enemies.any { it.hasPlanetOrFortress })
            val exempt = grid.inTactics && own?.hasShipUnit == true
            if (blocked && !exempt) return GridEntryVerdict.SOLO_SHIP_BLOCKED
        }
        return GridEntryVerdict.ALLOWED
    }
}
