package org.logh7.engine

import kotlin.math.hypot

/** Manual W53: five seconds to begin, 150 seconds preparation. Author: 최병호. */
data class TacticalRetreat(val destination: Int, val acceptedAt: Long, val waitSeconds: Long,
    val preparationSeconds: Long) {
    val startsAt get() = acceptedAt + waitSeconds
    val completesAt get() = startsAt + preparationSeconds
    init {
        require(destination in 0..4999 && acceptedAt in 0..0xffffffffL && waitSeconds in 1..5000 && preparationSeconds in 1..150000)
        require(completesAt <= 0xffffffffL)
    }
}

internal object RetreatRules {
    fun outside(pose: TacticalPose) = hypot(pose.position.x.toDouble(), pose.position.y.toDouble()) > 250.0

    /** Provisional destination: prefer the adjacent legal exit in the radial direction. */
    fun destination(galaxy: Galaxy, player: Navigator, players: Collection<Navigator>, stocks: PlanetStocks?, pose: TacticalPose): Int? {
        val forces = GridForces.collect(galaxy, player.generation, players, stocks)
        val x = player.grid % 100; val y = player.grid / 100
        return (-1..1).flatMap { dy -> (-1..1).mapNotNull { dx ->
            if (dx == 0 && dy == 0 || x + dx !in 0..99 || y + dy !in 0..49) null
            else Triple((y + dy) * 100 + x + dx, dx, dy)
        } }.sortedWith(compareByDescending<Triple<Int, Int, Int>> {
            (it.second * pose.position.x + it.third * pose.position.y) / hypot(it.second.toDouble(), it.third.toDouble())
        }.thenBy { it.first }).firstOrNull {
            allowed(galaxy, player, it.first, forces)
        }?.first
    }
    fun allowed(galaxy: Galaxy, player: Navigator, destination: Int, forces: Map<Int, List<GridForces.Force>>): Boolean =
        GridEntryRules.evaluate(GridForces.state(galaxy, destination, forces),
            GridEntry(Camp(player.power, false), 1, true)) == GridEntryVerdict.ALLOWED
}
