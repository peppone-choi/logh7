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

data class TacticalStop(val units: List<Int>, val bases: List<Int> = emptyList()) {
    init {
        require(units.size <= 32 && bases.size <= 32 && (units.isNotEmpty() || bases.isNotEmpty()))
        require(units.all { it > 0 } && units.distinct().size == units.size)
        require(bases.all { it in 1..65535 } && bases.distinct().size == bases.size)
    }
}
enum class TacticalManeuver { TURN, REVERSE }

/** Group leader is retained so reconnect never turns a formation into independent rotations. */
data class TacticalRotation(val kind:TacticalManeuver,val leader:Int,val wait:Long,val selection:List<Int> = listOf(leader)) {
    init {require(leader>0 && wait in 1..0xffffffffL && selection.size in 1..32 && selection.first()==leader &&
        selection.all {it>0} && selection.distinct().size==selection.size)}
}

data class TacticalMove(val units: List<Int>, val velocity: Float, val direction: Float, val waypoints: List<TacticalPoint>,val parallel:Boolean=false,
    val maneuver:TacticalManeuver?=null) {
    init {
        require(units.size in 1..32 && units.all { it > 0 } && units.distinct().size == units.size)
        require(velocity.isFinite() && velocity in 0f..1f && direction.isFinite() && waypoints.size in 1..32)
        require(maneuver==null || !parallel && waypoints.size==1)
    }
}
/** Parametric movement survives restart without writing interpolated positions on every tick.
 * The current native import has a base speed of 1 map unit/G-second (run-29).
 * Binding the manual hull speed and acceleration remains separate from this provisional constant.
 */
data class TacticalMotion(val grid: Int, val acceptedAt: Long, val origin: TacticalPose,
    val velocity: Float, val direction: Float, val waypoints: List<TacticalPoint>,val parallel:Boolean=false,
    val reportedWaypoints:Int=0,val rotation:TacticalRotation?=null) {
    init {
        require(grid in 0..4999 && acceptedAt in 0..0xffffffffL - WAIT_SECONDS)
        require(velocity.isFinite() && velocity in 0f..1f && direction.isFinite() && waypoints.size in 1..32)
        require(reportedWaypoints in waypoints.indices)
        require(rotation==null || (!parallel && reportedWaypoints==0 && waypoints.size==1 && velocity==0f && acceptedAt<=0xffffffffL-rotation.wait))
    }
    val startsAt get() = acceptedAt + (rotation?.wait ?: WAIT_SECONDS)
    fun completedWaypoints(seconds:Long):Int {
        require(seconds in 0..0xffffffffL)
        if(rotation!=null)return if(seconds>=startsAt)1 else 0
        if(seconds<=startsAt || velocity==0f)return 0
        var distance=(seconds-startsAt)*velocity.toDouble()
        var from=origin.position
        for((index,target) in waypoints.withIndex()) {
            val length=hypot(target.x.toDouble()-from.x,target.y.toDouble()-from.y)
            if(distance<length)return index
            distance-=length;from=target
        }
        return waypoints.size
    }
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
        if(rotation!=null)return if(seconds<startsAt)origin else TacticalPose(waypoints.single(),direction)
        if (seconds <= startsAt || velocity == 0f) return origin
        var distance = (seconds - startsAt) * velocity.toDouble()
        var from = origin.position
        for (target in waypoints) {
            val dx = target.x.toDouble() - from.x
            val dy = target.y.toDouble() - from.y
            val length = hypot(dx, dy)
            if (distance < length) return TacticalPose(TacticalPoint((from.x + dx * distance / length).toFloat(),
                (from.y + dy * distance / length).toFloat()), if(parallel)origin.direction else atan2(dx, dy).toFloat())
            distance -= length
            from = target
        }
        return TacticalPose(from, if(parallel)origin.direction else direction)
    }
    companion object { const val WAIT_SECONDS = 5L }
}

/** Same provisional formation for initialization and a first authoritative move origin. */
object TacticalDeployment {
    /** Native radar uses a radius of 250; keep every initial unit inside it. */
    fun formation(index: Int, power: Power): TacticalPose {
        require(index in 0..599)
        val side = if (power == Power.EMPIRE) 1 else -1
        return TacticalPose(TacticalPoint(side * (180f - index / 20 * 4f), (index % 20 - 9.5f) * 10f),
            if (power == Power.EMPIRE) (-Math.PI / 2).toFloat() else (Math.PI / 2).toFloat())
    }
    fun initial(unit: Int, power: Power, grid: Int, players: Collection<Navigator>, stocks: PlanetStocks?, galaxy: Galaxy): TacticalPose {
        val wirePower = if (power == Power.EMPIRE) 2 else 3
        val ids = (players.filter { it.power == power && it.grid == grid && it.baseId == 0 }.map { it.unitId } +
            stocks?.outfits?.values.orEmpty().filter { it.power == wirePower && it.positionIn(galaxy) == OutfitPosition(grid) }
                .flatMap { it.activeShips?.values.orEmpty().map { ship -> ship.id } }).distinct().sorted()
        val index = ids.indexOf(unit)
        require(index >= 0)
        return formation(index, power)
    }
}
