package org.logh7.engine

import kotlin.math.hypot

/** W50: friendly ships (including mobile fortresses) block conventional weapons.
 * Ship collision radius .25 and a .05 margin are design policy. No terrain shapes are seeded yet.
 * Author: Peppone Choi. */
internal object TacticalLineOfFire {
    fun blocked(source:Int,grid:Int,power:Power,from:TacticalPoint,to:TacticalPoint,now:Long,
        players:Collection<Navigator>,stocks:PlanetStocks,galaxy:Galaxy):Boolean {
        val dx=to.x.toDouble()-from.x;val dy=to.y.toDouble()-from.y;val length=dx*dx+dy*dy
        if(length==0.0)return false
        fun intersects(point:TacticalPoint,radius:Double):Boolean {
            val t=((point.x-from.x)*dx+(point.y-from.y)*dy)/length
            return t>0 && t<1 && hypot(point.x-from.x-t*dx,point.y-from.y-t*dy)<=radius+.05
        }
        val wire=if(power==Power.EMPIRE)2 else 3
        for(p in players.filter {it.unitId!=source && it.grid==grid && it.baseId==0 && it.cpPaused && it.power==power}) {
            val at=p.motion?.pose(now,p.posture,galaxy,stocks)?.position ?:
                TacticalDeployment.initial(p.unitId,power,grid,players,stocks,galaxy).position
            if(intersects(at,.25))return true
        }
        for(o in stocks.outfits.values.filter {it.power==wire && it.positionIn(galaxy)==OutfitPosition(grid)})
            for(u in o.activeShips?.values.orEmpty().filter {it.id!=source && it.boats>0 && it.posture?.mode!=TacticalShipMode.STATIONED}) {
                val at=u.tactical?.motion?.pose(now,u.posture,galaxy,stocks)?.position ?:
                    TacticalDeployment.initial(u.id,power,grid,players,stocks,galaxy).position
                if(intersects(at,.25))return true
            }
        return galaxy.bases.filter {it.grid==grid && it.kind==1 && stocks.bases[it.id]?.let {b->b.power==wire && b.camp==0}==true}
            .any {intersects(TacticalBasePosition.at(it,now,stocks),maxOf(.25,it.diameter/2.0))}
    }
}
