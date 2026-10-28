package org.logh7.engine

/** Actor-owned waypoint permission/completion, committed before 0423 publication. Author: 최병호. */
internal object TacticalMotionProgress {
    fun advance(session:SessionState,galaxy:Galaxy,players:MutableMap<String,Navigator>,original:PlanetStocks,
        events:MutableList<TacticalMovement>):PlanetStocks {
        val now=session.gameMillis/1000
        fun progress(unit:Int,character:Int,motion:TacticalMotion):TacticalMotion {
            val completed=motion.completedWaypoints(now)
            if(completed<=motion.reportedWaypoints)return motion
            val next=if(completed==motion.waypoints.size) {
                val pose=motion.pose(now)
                motion.copy(origin=pose,velocity=0f,direction=pose.direction,waypoints=listOf(pose.position),reportedWaypoints=0,rotation=null)
            } else motion.copy(reportedWaypoints=completed)
            val audience=players.mapNotNull {(account,p)->session.participants[account]?.takeIf {
                it.online && it.power==p.power && p.grid==motion.grid && p.baseId==0 && p.cpPaused
            }?.connection?.let {p.characterId to it}}.toMap()
            events+=TacticalMovement(session.generation,unit,motion.grid,now,next,audience,character=character,
                waypoint=if(next.velocity==0f)-1 else completed,turned=motion.rotation?.kind==TacticalManeuver.REVERSE)
            return next
        }
        for((account,player) in players.toMap()) {
            val motion=player.motion?.takeIf {player.cpPaused && player.baseId==0 && player.hull!=null && it.grid==player.grid} ?: continue
            val next=progress(player.unitId,player.characterId,motion)
            if(next!=motion)players[account]=player.copy(motion=next)
        }
        var stocks=original
        for((id,outfit) in original.outfits) {
            val position=outfit.positionIn(galaxy)
            if(position.baseId!=0)continue
            val units=outfit.activeShips ?: continue
            val next=units.values.map {unit ->
                val state=unit.tactical?.takeIf {unit.boats>0 && it.motion.grid==position.grid &&
                    (unit.motionCommander==0 || unit.motionCommander==outfit.commanderId)} ?: return@map unit
                val motion=progress(unit.id,unit.motionCommander,state.motion)
                if(motion==state.motion)unit else unit.copy(tactical=state.copy(motion=motion))
            }
            if(next!=units.values)stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(activeShips=ActiveShipUnits(next))))
        }
        return stocks
    }
}
