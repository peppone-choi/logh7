package org.logh7.engine

import kotlin.math.abs
import kotlin.math.hypot

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
    /** Tactical drift toward the black hole while a unit holds still (tactical units per game second). */
    const val DRIFT_VELOCITY = 0.05f
    /** Native radar radius; the drift stops at this edge of the field, where tidal loss doubles. */
    const val EDGE = 250f

    fun inGravity(galaxy: Galaxy, grid: Int) = galaxy.blackHoles.any {
        it != grid && maxOf(abs(it % 100 - grid % 100), abs(it / 100 - grid / 100)) == 1
    }
    /** Field edge facing the nearest black hole, using the strategic grid axes (evidence:guess). */
    fun edge(galaxy: Galaxy, grid: Int): TacticalPoint? {
        val hole = galaxy.blackHoles.firstOrNull {
            it != grid && maxOf(abs(it % 100 - grid % 100), abs(it / 100 - grid / 100)) == 1 } ?: return null
        val dx = (hole % 100 - grid % 100).toDouble(); val dy = (hole / 100 - grid / 100).toDouble()
        val length = hypot(dx, dy)
        return TacticalPoint((EDGE * dx / length).toFloat(), (EDGE * dy / length).toFloat())
    }
    private fun atEdge(edge: TacticalPoint, position: TacticalPoint) =
        hypot(position.x.toDouble() - edge.x, position.y.toDouble() - edge.y) < 1.0
    /** A still unit in a gravity zone starts drifting to the edge; moving, turning or edge units are left alone. */
    fun drift(galaxy: Galaxy, motion: TacticalMotion, now: Long): TacticalMotion? {
        if (motion.velocity != 0f || motion.rotation != null || motion.turnRate > 0f) return null
        val edge = edge(galaxy, motion.grid) ?: return null
        val pose = motion.pose(now)
        if (atEdge(edge, pose.position) || now > 0xffffffffL) return null
        return TacticalMotion(motion.grid, now, pose, DRIFT_VELOCITY, pose.direction, listOf(edge), parallel = true, waitSeconds = 0)
    }
    /** Removes [percent]% (rounded down) of every active ship unit, doubled for a unit held at the field edge
     * ([seconds] given); units below 100 boats lose nothing. */
    fun loss(outfit: OutfitStock, percent: Int, galaxy: Galaxy? = null, seconds: Long? = null): OutfitStock {
        var next = outfit
        val grid = galaxy?.let { outfit.positionIn(it).grid }
        val edge = if (galaxy == null || seconds == null) null else edge(galaxy, grid!!)
        for (unit in outfit.activeShips?.values.orEmpty()) {
            val held = edge != null && unit.tactical?.motion?.takeIf { it.grid == grid }
                ?.let { atEdge(edge, it.pose(seconds!!).position) } == true
            val lost = unit.boats * (if (held) percent * 2 else percent) / 100
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
            repeat(hours.toInt()) { current = loss(current, TIDAL_PERCENT, galaxy, seconds) }
            if (current != outfit) outfits = outfits + (outfit.id to current)
        }
        return stocks.copy(outfits = outfits, tidalHour = hour)
    }
}
