package org.logh7.engine

import kotlin.math.abs

/**
 * Black-hole damage in the eight gravity grids around each black hole. No source describes black-hole
 * damage; 1% of each ship unit's boats per game hour while staying, and 3% when a warp error pulls a
 * fleet into the zone, are the design approved 2026-10-30 (docs/server/design-blackhole-tactical-terrain.md
 * §2.4, evidence:guess). Flagship hulls are not damaged. Author: Peppone Choi.
 */
object BlackHoleTides {
    const val TIDAL_PERCENT = 1
    const val PULL_PERCENT = 3
    const val HOUR = 3600L
    /** Missed hours applied at most once per settlement (a long-closed server does not wipe fleets). */
    const val MAXIMUM_BACKLOG_HOURS = 24L

    fun inGravity(galaxy: Galaxy, grid: Int) = galaxy.blackHoles.any {
        it != grid && maxOf(abs(it % 100 - grid % 100), abs(it / 100 - grid / 100)) == 1
    }
    /** Removes [percent]% (rounded down) of every active ship unit; units below 100 boats lose nothing. */
    fun loss(outfit: OutfitStock, percent: Int): OutfitStock {
        var next = outfit
        for (unit in outfit.activeShips?.values.orEmpty()) {
            val lost = unit.boats * percent / 100
            if (lost > 0) next = TacticalLosses.apply(next, unit.id, lost)
        }
        return next
    }
    /** The hour watermark is kept only while some outfit stays in a gravity zone, so a fleet entering the
     * zone starts losing boats from the next full game hour and other worlds never change. */
    fun settle(galaxy: Galaxy, stocks: PlanetStocks, seconds: Long): PlanetStocks {
        val caught = stocks.outfits.values.filter { inGravity(galaxy, it.positionIn(galaxy).grid) }
        if (caught.isEmpty()) return if (stocks.tidalHour == 0L) stocks else stocks.copy(tidalHour = 0)
        val hour = maxOf(1L, seconds / HOUR)
        if (stocks.tidalHour == 0L || hour < stocks.tidalHour) return stocks.copy(tidalHour = hour)
        val hours = minOf(MAXIMUM_BACKLOG_HOURS, hour - stocks.tidalHour)
        if (hours == 0L) return stocks
        var outfits = stocks.outfits
        for (outfit in caught) {
            var current = outfit
            repeat(hours.toInt()) { current = loss(current, TIDAL_PERCENT) }
            if (current != outfit) outfits = outfits + (outfit.id to current)
        }
        return stocks.copy(outfits = outfits, tidalHour = hour)
    }
}
