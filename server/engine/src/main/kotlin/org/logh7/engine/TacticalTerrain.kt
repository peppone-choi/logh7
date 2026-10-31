package org.logh7.engine

import kotlin.math.hypot

/**
 * Star obstacles of a star-system grid's tactical field (constmsg group 71 "地形情報"). The star sits at the
 * origin; its movement radius (万km) by class O..M is the client table, converted at 1 tactical unit =
 * 3000万km and applied to movement and line of fire (design approved 2026-10-30, evidence:guess).
 * Author: Peppone Choi.
 */
object TacticalTerrain {
    const val KM_PER_UNIT = 3000.0 // 万km
    /** Movement-obstacle radius in 万km for star classes O, B, A, F, G, K, M (group 71 #22..#28). */
    val movementRadius = listOf(30000, 9500, 1200, 680, 430, 300, 150)

    fun starRadius(galaxy: Galaxy, grid: Int): Double? =
        galaxy.systems.firstOrNull { it.grid == grid }?.star?.let { movementRadius.getOrNull(it) }?.div(KM_PER_UNIT)

    /** Closest distance from the star (origin) to the segment, and where on the segment (0..1) it lies. */
    private fun closest(from: TacticalPoint, to: TacticalPoint): Pair<Double, Double> {
        val dx = to.x.toDouble() - from.x; val dy = to.y.toDouble() - from.y
        val length = dx * dx + dy * dy
        val t = if (length == 0.0) 0.0 else ((-from.x * dx - from.y * dy) / length).coerceIn(0.0, 1.0)
        return hypot(from.x + t * dx, from.y + t * dy) to t
    }
    /** A move path from [start] through [waypoints] that would enter the star is refused. A unit already
     * inside the circle may only move outward. */
    fun pathBlocked(galaxy: Galaxy, grid: Int, start: TacticalPoint, waypoints: List<TacticalPoint>): Boolean {
        val radius = starRadius(galaxy, grid) ?: return false
        var from = start
        for (point in waypoints) {
            val startDistance = hypot(from.x.toDouble(), from.y.toDouble())
            val blocked = if (startDistance < radius) hypot(point.x.toDouble(), point.y.toDouble()) < startDistance
                else closest(from, point).first < radius
            if (blocked) return true
            from = point
        }
        return false
    }
    /** Weapons cannot fire through the star (W p.50 line-of-fire terrain); the segment interior is tested. */
    fun fireBlocked(galaxy: Galaxy, grid: Int, from: TacticalPoint, to: TacticalPoint): Boolean {
        val radius = starRadius(galaxy, grid) ?: return false
        val (distance, t) = closest(from, to)
        return t > 0.0 && t < 1.0 && distance < radius
    }
}
