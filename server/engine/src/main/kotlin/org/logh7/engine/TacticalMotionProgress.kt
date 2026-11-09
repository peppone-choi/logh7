package org.logh7.engine

/** Actor-owned waypoint permission/completion, committed before 0423 publication. Author: Peppone Choi. */
internal object TacticalMotionProgress {
    fun advance(session:SessionState,galaxy:Galaxy,players:MutableMap<String,Navigator>,original:PlanetStocks,
        events:MutableList<TacticalMovement>):PlanetStocks {
        val now=session.gameMillis/1000
        fun audience(grid:Int)=players.mapNotNull {(account,p)->session.participants[account]?.takeIf {
            it.online && it.country==p.country && p.grid==grid && p.baseId==0 && p.cpPaused
        }?.connection?.let {p.characterId to it}}.toMap()
        fun progress(unit:Int,character:Int,motion:TacticalMotion,driftLimit:Float=BlackHoleTides.DRIFT_VELOCITY,
            invalidCommander:Boolean=false):TacticalMotion {
            val detained=original.social.justice.detained(character)
            if(detained && motion.rotation==null && motion.velocity==0f)return motion
            if(detained || motion.rotation!=null && (invalidCommander || BattleDeath.deceased(original,character))) {
                val at=if(motion.rotation!=null)motion.origin else motion.pose(now)
                val stopped=motion.copy(origin=at,velocity=0f,direction=at.direction,waypoints=listOf(at.position),
                    reportedWaypoints=0,rotation=null)
                events+=TacticalMovement(session.generation,unit,motion.grid,now,stopped,audience(motion.grid),
                    character=character,waypoint=-1)
                return stopped
            }
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
            // The native executor uses the supplied angle to choose its rotation direction.
            val reverseTarget=motion.rotation?.takeIf {it.kind==TacticalManeuver.REVERSE}?.let {
                (motion.origin.direction+(if(it.reverseSide==0)-Math.PI else Math.PI)).toFloat()
            }
            events+=TacticalMovement(session.generation,unit,motion.grid,now,next,audience(motion.grid),character=character,
                waypoint=if(next.velocity==0f)-1 else completed,reverseTarget=reverseTarget)
            return next
        }
        for((account,player) in players.toMap()) {
            if(!player.cpPaused || player.baseId!=0 || player.hull==null)continue
            val motion=player.motion?.takeIf {it.grid==player.grid} ?: if(BlackHoleTides.edge(galaxy,player.grid)==null)continue else {
                // A flagship that has not moved yet still drifts from its deployment pose.
                val pose=TacticalDeployment.initial(player.unitId,player.grid,players.values,original,galaxy)
                TacticalMotion(player.grid,now,pose,0f,pose.direction,listOf(pose.position))
            }
            // Navigator allows a parallel motion only within half its engine output.
            val next=progress(player.unitId,player.characterId,motion,(player.energy ?: TacticalEnergy.ZERO).values[0]/100f*0.5f,
                invalidCommander=player.hull.woundedUntil>now)
            if(next!=motion)players[account]=player.copy(motion=next)
        }
        val commanders=players.values.associateBy {it.characterId}
        var stocks=original
        for((id,outfit) in original.outfits) {
            val position=outfit.positionIn(galaxy)
            if(position.baseId!=0)continue
            val units=outfit.activeShips ?: continue
            val next=units.values.map {unit ->
                val state=unit.tactical?.takeIf {unit.boats>0 && it.motion.grid==position.grid} ?: return@map unit
                val commanderChanged=unit.motionCommander!=0 && unit.motionCommander!=TacticalAuthority.commander(outfit,unit,stocks)
                if(commanderChanged && state.motion.rotation==null)return@map unit
                val commanderUnavailable=state.motion.rotation!=null && commanders[unit.motionCommander]?.let {
                    (it.hull?.woundedUntil ?: 0)>now || TacticalAuthority.camp(it,stocks)!=TacticalAuthority.camp(outfit,stocks)
                }==true
                val motion=progress(unit.id,unit.motionCommander,state.motion,invalidCommander=commanderChanged || commanderUnavailable)
                if(motion==state.motion)unit else unit.copy(tactical=state.copy(motion=motion,
                    nextDecisionAt=maxOf(state.nextDecisionAt,motion.acceptedAt)))
            }
            if(next!=units.values)stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(activeShips=ActiveShipUnits(next))))
        }
        return stocks
    }
}
