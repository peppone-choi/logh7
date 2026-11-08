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
            val blocked = if (startDistance < radius) {
                // A segment that initially approaches the center is inward even if it ends outside.
                val dx = point.x.toDouble() - from.x
                val dy = point.y.toDouble() - from.y
                from.x * dx + from.y * dy < 0.0
            } else closest(from, point).first < radius
            if (blocked) return true
            from = point
        }
        return false
    }
    /** Routes autonomous movement around the star using clear corners on either side. */
    internal fun route(galaxy:Galaxy,grid:Int,start:TacticalPoint,end:TacticalPoint):List<TacticalPoint> {
        val direct=listOf(end)
        if(!pathBlocked(galaxy,grid,start,direct))return direct
        val clearance=(checkNotNull(starRadius(galaxy,grid))*2).toFloat()
        val corners=listOf(TacticalPoint(clearance,clearance),TacticalPoint(-clearance,clearance),
            TacticalPoint(-clearance,-clearance),TacticalPoint(clearance,-clearance))
        val candidates=corners.indices.flatMap {i->
            listOf(listOf(corners[i],end),listOf(corners[i],corners[(i+1)%4],end),
                listOf(corners[i],corners[(i+3)%4],end))
        }
        return candidates.filterNot {pathBlocked(galaxy,grid,start,it)}
            .minByOrNull {route->(listOf(start)+route).zipWithNext().sumOf {(a,b)->
                hypot(a.x.toDouble()-b.x,a.y.toDouble()-b.y)
            }} ?: listOf(start)
    }

    /** A target inside the sensor obstacle is hidden from observers outside it. */
    fun sensorBlocked(galaxy: Galaxy, grid: Int, observer: TacticalPoint, target: TacticalPoint): Boolean {
        val radius = (starRadius(galaxy, grid) ?: return false) * 2
        return hypot(observer.x.toDouble(), observer.y.toDouble()) >= radius &&
            hypot(target.x.toDouble(), target.y.toDouble()) < radius
    }

    /** Nearest friendly defense headquarters under the current star-only contact model. */
    fun defenseObserver(galaxy:Galaxy,grid:Int,camp:Camp,now:Long,stocks:PlanetStocks):TacticalPoint? =
        galaxy.bases.asSequence().filter {base->
            base.grid==grid && stocks.bases[base.id]?.let(TacticalAuthority::camp)==camp
        }.map {TacticalBasePosition.at(it,now,stocks)}
            .minByOrNull {it.x.toDouble()*it.x+it.y.toDouble()*it.y}

    /** Manual p.49: any active ally can share a contact. Physical fire clearance is separate. */
    internal fun sharedSensorBlocked(galaxy:Galaxy,grid:Int,camp:Camp,observer:TacticalPoint,target:TacticalPoint,
        now:Long,players:Collection<Navigator>,stocks:PlanetStocks):Boolean {
        if(!sensorBlocked(galaxy,grid,observer,target))return false
        val headquarters=defenseObserver(galaxy,grid,camp,now,stocks)
        if(headquarters!=null && !sensorBlocked(galaxy,grid,headquarters,target))return false
        fun sees(unit:Int,motion:TacticalMotion?,posture:TacticalPosture?):Boolean {
            val position=motion?.takeIf {it.grid==grid}?.pose(now,posture,galaxy,stocks)?.position
                ?: posture?.takeIf {it.grid==grid && it.mode.requiresBase}
                    ?.let {state->galaxy.bases.singleOrNull {it.id==state.base && it.grid==grid}}
                    ?.let {TacticalBasePosition.at(it,now,stocks)}
                ?: TacticalDeployment.initial(unit,grid,players,stocks,galaxy).position
            return !sensorBlocked(galaxy,grid,position,target)
        }
        if(players.any {it.grid==grid && it.baseId==0 && it.cpPaused &&
                !BattleDeath.deceased(stocks,it.characterId) && TacticalAuthority.camp(it,stocks)==camp &&
                sees(it.unitId,it.motion,it.posture)})return false
        return stocks.outfits.values.none {outfit->
            outfit.positionIn(galaxy)==OutfitPosition(grid) && TacticalAuthority.camp(outfit,stocks)==camp &&
                outfit.activeShips?.values.orEmpty().any {ship->ship.boats>0 &&
                    ship.posture?.takeIf {it.grid==grid}?.mode!=TacticalShipMode.STATIONED &&
                    sees(ship.id,ship.tactical?.motion,ship.posture)}
        }
    }

    /** Weapons cannot fire through the star (W p.50 line-of-fire terrain); the segment interior is tested. */
    fun fireBlocked(galaxy: Galaxy, grid: Int, from: TacticalPoint, to: TacticalPoint): Boolean {
        val radius = starRadius(galaxy, grid) ?: return false
        val (distance, t) = closest(from, to)
        return t > 0.0 && t < 1.0 && distance < radius
    }
}
