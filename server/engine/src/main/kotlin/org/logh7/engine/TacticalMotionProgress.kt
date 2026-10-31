package org.logh7.engine

/** Actor-owned waypoint permission/completion, committed before 0423 publication. Author: Peppone Choi. */
internal object TacticalMotionProgress {
    fun advance(session:SessionState,galaxy:Galaxy,players:MutableMap<String,Navigator>,original:PlanetStocks,
        events:MutableList<TacticalMovement>):PlanetStocks {
        val now=session.gameMillis/1000
        fun audience(grid:Int)=players.mapNotNull {(account,p)->session.participants[account]?.takeIf {
            it.online && it.power==p.power && p.grid==grid && p.baseId==0 && p.cpPaused
        }?.connection?.let {p.characterId to it}}.toMap()
        fun progress(unit:Int,character:Int,motion:TacticalMotion,driftLimit:Float=BlackHoleTides.DRIFT_VELOCITY):TacticalMotion {
            val completed=motion.completedWaypoints(now)
            // A still player-commanded unit beside a black hole is pulled toward the field edge (BlackHoleTides).
            // Autonomous units are excluded: NpcTactics owns their motion and would cancel the drift every tick.
            if(completed<=motion.reportedWaypoints)return BlackHoleTides.drift(galaxy,motion,now,driftLimit)?.takeIf {character>0}?.also {
                events+=TacticalMovement(session.generation,unit,motion.grid,now,it,audience(motion.grid),character=character,waypoint=0)
            } ?: motion
            val next=if(completed==motion.waypoints.size) {
                val pose=motion.pose(now)
                motion.copy(origin=pose,velocity=0f,direction=pose.direction,waypoints=listOf(pose.position),reportedWaypoints=0,rotation=null)
            } else motion.copy(reportedWaypoints=completed)
            events+=TacticalMovement(session.generation,unit,motion.grid,now,next,audience(motion.grid),character=character,
                waypoint=if(next.velocity==0f)-1 else completed,turned=motion.rotation?.kind==TacticalManeuver.REVERSE)
            return next
        }
        for((account,player) in players.toMap()) {
            if(!player.cpPaused || player.baseId!=0 || player.hull==null)continue
            val motion=player.motion?.takeIf {it.grid==player.grid} ?: if(BlackHoleTides.edge(galaxy,player.grid)==null)continue else {
                // A flagship that has not moved yet still drifts from its deployment pose.
                val pose=TacticalDeployment.initial(player.unitId,player.power,player.grid,players.values,original,galaxy)
                TacticalMotion(player.grid,now,pose,0f,pose.direction,listOf(pose.position))
            }
            // Navigator allows a parallel motion only within half its engine output.
            val next=progress(player.unitId,player.characterId,motion,(player.energy ?: TacticalEnergy.ZERO).values[0]/100f*0.5f)
            if(next!=motion)players[account]=player.copy(motion=next)
        }
        var stocks=original
        for((id,outfit) in original.outfits) {
            val position=outfit.positionIn(galaxy)
            if(position.baseId!=0)continue
            val units=outfit.activeShips ?: continue
            val next=units.values.map {unit ->
                val state=unit.tactical?.takeIf {unit.boats>0 && it.motion.grid==position.grid &&
                    (unit.motionCommander==0 || unit.motionCommander==TacticalAuthority.commander(outfit,unit,stocks))} ?: return@map unit
                val motion=progress(unit.id,unit.motionCommander,state.motion)
                if(motion==state.motion)unit else unit.copy(tactical=state.copy(motion=motion,
                    nextDecisionAt=maxOf(state.nextDecisionAt,motion.acceptedAt)))
            }
            if(next!=units.values)stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(activeShips=ActiveShipUnits(next))))
        }
        return stocks
    }
}
