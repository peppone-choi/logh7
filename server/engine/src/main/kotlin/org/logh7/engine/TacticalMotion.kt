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
data class TacticalRotation(val kind:TacticalManeuver,val leader:Int,val wait:Long,val selection:List<Int> = listOf(leader),val reverseSide:Int=0) {
    init {require(leader>0 && wait in 1..0xffffffffL && selection.size in 1..32 && selection.first()==leader &&
        selection.all {it>0} && selection.distinct().size==selection.size && reverseSide in 0..1 && (kind==TacticalManeuver.REVERSE || reverseSide==0))}
}

data class TacticalMove(val units: List<Int>, val velocity: Float, val direction: Float, val waypoints: List<TacticalPoint>,val parallel:Boolean=false,
    val maneuver:TacticalManeuver?=null,val reverseSide:Int=0) {
    init {
        require(units.size in 1..32 && units.all { it > 0 } && units.distinct().size == units.size)
        require(velocity.isFinite() && velocity in 0f..1f && direction.isFinite() && waypoints.size in 1..32)
        require(maneuver==null || !parallel && waypoints.size==1)
        require(reverseSide in 0..1 && (maneuver==TacticalManeuver.REVERSE || reverseSide==0))
    }
}
/** Parametric movement survives restart without writing interpolated positions on every tick.
 * The current native import has a base speed of 1 map unit/G-second (run-29).
 * Binding the manual hull speed and acceleration remains separate from this provisional constant.
 */
data class TacticalMotion(val grid: Int, val acceptedAt: Long, val origin: TacticalPose,
    val velocity: Float, val direction: Float, val waypoints: List<TacticalPoint>,val parallel:Boolean=false,
    val reportedWaypoints:Int=0,val rotation:TacticalRotation?=null,val turnRate:Float=0f,val waitSeconds:Long=WAIT_SECONDS) {
    init {
        require(grid in 0..4999 && acceptedAt in 0..0xffffffffL - WAIT_SECONDS)
        require(velocity.isFinite() && velocity in 0f..1f && direction.isFinite() && waypoints.size in 1..32)
        require(reportedWaypoints in waypoints.indices)
        require(waitSeconds in 0..5000 && acceptedAt<=0xffffffffL-waitSeconds)
        require(turnRate.isFinite() && turnRate in 0f..1000f && (rotation==null || turnRate==0f))
        require(rotation==null || (!parallel && reportedWaypoints==0 && waypoints.size==1 && velocity==0f && acceptedAt<=0xffffffffL-rotation.wait))
    }
    val startsAt get() = acceptedAt + (rotation?.wait ?: waitSeconds)
    /** First integer game second whose durable progress differs from the last report. */
    internal val nextProgressAt: Long? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if(rotation==null && velocity==0f || completedWaypoints(0xffffffffL)<=reportedWaypoints) null
        else {
            var low=startsAt;var high=0xffffffffL
            while(low<high) {
                val middle=low+(high-low)/2
                if(completedWaypoints(middle)>reportedWaypoints)high=middle else low=middle+1
            }
            low
        }
    }
    fun completedWaypoints(seconds:Long):Int {
        require(seconds in 0..0xffffffffL)
        if(rotation!=null)return if(seconds>=startsAt)1 else 0
        if(turnRate>0f)return turningProgress(seconds).completed
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
        if(turnRate>0f)return waypoints.drop(turningProgress(seconds).completed.coerceAtMost(waypoints.lastIndex))
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
        if(turnRate>0f)return turningProgress(seconds).pose
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
    private data class Progress(val pose:TacticalPose,val completed:Int)
    /** Native normal paths turn before each translation, then to the final facing.
     * A zero rate explicitly retains the historical linear schedule of saved paths.
     */
    private fun turningProgress(seconds:Long):Progress {
        if(seconds<=startsAt || velocity==0f)return Progress(origin,0)
        var time=(seconds-startsAt).toDouble()
        var pose=origin
        fun delta(target:Float)=kotlin.math.atan2(kotlin.math.sin(target.toDouble()-pose.direction),
            kotlin.math.cos(target.toDouble()-pose.direction))
        for((index,target) in waypoints.withIndex()) {
            val dx=target.x.toDouble()-pose.position.x;val dy=target.y.toDouble()-pose.position.y
            val length=hypot(dx,dy)
            val facing=if(parallel || length==0.0)pose.direction else atan2(dx,dy).toFloat()
            if(!parallel && length>0.0) {
                val angle=delta(facing);val duration=kotlin.math.abs(angle)/turnRate
                if(time<duration)return Progress(pose.copy(direction=(pose.direction+angle*time/duration).toFloat()),index)
                time-=duration;pose=pose.copy(direction=facing)
            }
            val duration=length/velocity
            if(time<duration)return Progress(TacticalPose(TacticalPoint((pose.position.x+dx*time/duration).toFloat(),
                (pose.position.y+dy*time/duration).toFloat()),facing),index)
            time-=duration;pose=TacticalPose(target,facing)
        }
        if(!parallel) {
            val angle=delta(direction);val duration=kotlin.math.abs(angle)/turnRate
            if(time<duration)return Progress(pose.copy(direction=(pose.direction+angle*time/duration).toFloat()),waypoints.lastIndex)
            pose=pose.copy(direction=direction)
        }
        return Progress(pose,waypoints.size)
    }
    companion object {
        const val WAIT_SECONDS = 5L
        // Matches WorldMessages' provisional native angular rate, radians/G-second.
        const val NATIVE_TURN_RATE = 1f
    }
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
