package org.logh7.gateway

import org.logh7.engine.*
import org.logh7.protocol.GridForceMessages
import kotlin.math.abs

/** Friendly deployed units from the published world, including offline flagships. Author: 최병호. */
internal object GridForceReport {
    fun message(snapshot: WorldSnapshot, galaxy: Galaxy, viewer: Navigator, seconds: Long): ByteArray? {
        if (snapshot.session?.generation != viewer.generation) return null
        val power = if (viewer.power == Power.EMPIRE) 2 else 3
        val units = sortedMapOf<Int, Long>()
        // One native search has 225 cells: use a 15x15 neighbourhood until the
        // original strategic sensor range formula is restored.
        fun add(grid: Int, count: Long) {
            if (count > 0 && abs(grid % 100 - viewer.grid % 100) <= 7 && abs(grid / 100 - viewer.grid / 100) <= 7)
                units[grid] = (units[grid] ?: 0L) + count
        }
        snapshot.navigators.values.filter { it.generation == viewer.generation && it.power == viewer.power }
            .forEach { add(it.grid, 1L) }
        val ground = UnitSpecifications.crews.filter { it.category != 19 }.map { it.kind }.toSet()
        snapshot.planetStocks?.takeIf { it.generation == viewer.generation }?.outfits?.values
            ?.filter { it.power == power }?.forEach { outfit ->
                val base = galaxy.bases.single { it.id == outfit.baseId }
                add(base.grid, outfit.units.ships.values.sumOf { it.toLong() } +
                    outfit.units.troops.filterKeys { it.kind in ground }.values.sumOf { it.toLong() })
            }
        // The native importer converts a zero count to one. Never send fake units.
        return if (units.isEmpty()) null else GridForceMessages.search(seconds, viewer.characterId, power, 0,
            units.mapValues { it.value.coerceAtMost(65535).toInt() })
    }
}
