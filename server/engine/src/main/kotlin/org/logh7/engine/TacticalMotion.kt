package org.logh7.engine

import kotlin.math.atan2
import kotlin.math.hypot

/** Wire map coordinates are x/y, imported into native world x/z. Author: 최병호. */
data class TacticalPoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite() && kotlin.math.abs(x) <= 100_000 && kotlin.math.abs(y) <= 100_000) }
}
data class TacticalPose(val position: TacticalPoint, val direction: Float) {
    init { require(direction.isFinite()) }
}
data class TacticalMove(val units: List<Int>, val velocity: Float, val direction: Float, val waypoints: List<TacticalPoint>) {
    init {
        require(units.size in 1..32 && units.all { it > 0 } && units.distinct().size == units.size)
        require(velocity.isFinite() && velocity in 0f..1f && direction.isFinite() && waypoints.size in 1..32)
    }
}
/** Parametric movement survives restart without writing interpolated positions on every tick.
 * The current native import has a base speed of 1 map unit/G-second (run-29).
 * Binding the manual hull speed and acceleration remains separate from this provisional constant.
 */
data class TacticalMotion(val grid: Int, val acceptedAt: Long, val origin: TacticalPose,
    val velocity: Float, val direction: Float, val waypoints: List<TacticalPoint>) {
    init {
        require(grid in 0..4999 && acceptedAt in 0..0xffffffffL - WAIT_SECONDS)
        require(velocity.isFinite() && velocity in 0f..1f && direction.isFinite() && waypoints.size in 1..32)
    }
    val startsAt get() = acceptedAt + WAIT_SECONDS
    fun remaining(seconds: Long): List<TacticalPoint> {
        require(seconds in 0..0xffffffffL)
        var distance = maxOf(0, seconds - startsAt) * velocity.toDouble()
        var from = origin.position
        for ((index, target) in waypoints.withIndex()) {
            val length = hypot(target.x.toDouble() - from.x, target.y.toDouble() - from.y)
            if (distance < length || velocity == 0f) return waypoints.drop(index)
            distance -= length
            from = target
        }
        return listOf(waypoints.last())
    }
    fun pose(seconds: Long): TacticalPose {
        require(seconds in 0..0xffffffffL)
        if (seconds <= startsAt || velocity == 0f) return origin
        var distance = (seconds - startsAt) * velocity.toDouble()
        var from = origin.position
        for (target in waypoints) {
            val dx = target.x.toDouble() - from.x
            val dy = target.y.toDouble() - from.y
            val length = hypot(dx, dy)
            if (distance < length) return TacticalPose(TacticalPoint((from.x + dx * distance / length).toFloat(),
                (from.y + dy * distance / length).toFloat()), atan2(dy, dx).toFloat())
            distance -= length
            from = target
        }
        return TacticalPose(from, direction)
    }
    companion object { const val WAIT_SECONDS = 5L }
}

/** Same provisional formation for initialization and a first authoritative move origin. */
object TacticalDeployment {
    fun initial(unit: Int, power: Power, grid: Int, players: Collection<Navigator>, stocks: PlanetStocks?, galaxy: Galaxy): TacticalPose {
        val wirePower = if (power == Power.EMPIRE) 2 else 3
        val ids = (players.filter { it.power == power && it.grid == grid && it.baseId == 0 }.map { it.unitId } +
            stocks?.outfits?.values.orEmpty().filter { it.power == wirePower && it.positionIn(galaxy) == OutfitPosition(grid) }
                .flatMap { it.activeShips?.values.orEmpty().map { ship -> ship.id } }).distinct().sorted()
        val index = ids.indexOf(unit)
        require(index >= 0)
        return TacticalPose(TacticalPoint((if (power == Power.EMPIRE) 700f else -700f) + (index / 10) * 30f,
            (index % 10 - 4.5f) * 40f), if (power == Power.EMPIRE) Math.PI.toFloat() else 0f)
    }
}
