package org.logh7.engine

import kotlin.math.hypot

/** Commander-controlled regular ships share the durable per-unit motion with NPCs. Author: 최병호. */
internal object TacticalFleetMotion {
    fun move(request:NavigationRequest,session:SessionState,galaxy:Galaxy,navigation:Navigation,
        production:PlanetProduction,stocks:PlanetStocks,committer:WorldStateCommitter?,clockScale:Long=1):NavigationResult {
        require(clockScale in 1..86400)
        val preview=navigation.apply(request.copy(tacticalMove=null),session,stocks,persist=false)
        var player=preview.player ?: return preview
        fun reject(reason:NavigationRejection)=NavigationResult(null,player,reason)
        if(committer==null)return reject(NavigationRejection.DISABLED)
        if(!player.cpPaused || player.baseId!=0 || player.mode!=5)return reject(NavigationRejection.TACTICAL)
        if(player.hull?.woundedUntil?.let {it>session.gameMillis/1000}==true)return reject(NavigationRejection.WOUNDED)
        if(request.captainCard!=if(player.power==Power.EMPIRE)59 else 195)return reject(NavigationRejection.CARD)
        val command=checkNotNull(request.tacticalMove)
        if(player.unitId in command.units && (player.hull==null ||
            Flagships.find(player.hull.kind)?.power!=if(player.power==Power.EMPIRE)2 else 3))return reject(NavigationRejection.UNIT)
        if(command.units.distinct().size!=command.units.size)return reject(NavigationRejection.UNIT)
        val now=session.gameMillis/1000
        if(now>0xffffffffL-TacticalMotion.WAIT_SECONDS)return reject(NavigationRejection.CLOCK)
        val rotation=command.maneuver?.let {TacticalRotation(it,command.units.first(),
            (if(it==TacticalManeuver.REVERSE)10L else 5L)*clockScale,command.units,command.reverseSide)}
        if(rotation!=null && now>0xffffffffL-rotation.wait)return reject(NavigationRejection.CLOCK)
        if(rotation==null && now>0xffffffffL-5*clockScale)return reject(NavigationRejection.CLOCK)
        val players=navigation.snapshot(session.generation)
        val own=player.motion?.takeIf {it.grid==player.grid}?.pose(now,player.posture,galaxy,stocks) ?:
            TacticalDeployment.initial(player.unitId,player.power,player.grid,players.values,stocks,galaxy)
        val regular=command.units.filter {it!=player.unitId}
        val ships=regular.map {id ->
            if(id !in 1..999999)return reject(NavigationRejection.UNIT)
            val pair=stocks.outfits.values.flatMap {o->o.activeShips?.values.orEmpty().filter {it.id==id}.map {o to it}}
                .singleOrNull() ?: return reject(NavigationRejection.UNIT)
            val (o,u)=pair
            if(TacticalAuthority.commander(o,u,stocks)!=player.characterId || o.power!=(if(player.power==Power.EMPIRE)2 else 3) ||
                o.positionIn(galaxy)!=OutfitPosition(player.grid))return reject(NavigationRejection.CARD)
            if(u.boats==0 || u.support!=null)return reject(NavigationRejection.BUSY)
            pair
        }
        val poses=command.units.associateWith {id->if(id==player.unitId)own else {
            val unit=ships.single {it.second.id==id}.second
            (unit.tactical?.motion?.takeIf {it.grid==player.grid}?.pose(now,unit.posture,galaxy,stocks) ?:
                TacticalDeployment.initial(id,player.power,player.grid,players.values,stocks,galaxy)).also {pose ->
                    if(hypot((pose.position.x-own.position.x).toDouble(),(pose.position.y-own.position.y).toDouble())>
                        checkNotNull(player.commandRange).radius(now))return reject(NavigationRejection.RANGE)
                }
        }}
        if(command.maneuver==null && player.unitId in command.units && command.velocity>(player.energy ?: TacticalEnergy.ZERO).values[0]/100f)
            return reject(NavigationRejection.ENERGY)
        // 004c8110 anchors offsets to the first selected unit, not the formation centroid.
        val anchor=poses.getValue(command.units.first()).position
        val requestedMotions=poses.mapValues {(_,pose)->if(rotation!=null) {
            val direction=if(rotation.kind==TacticalManeuver.REVERSE)
                (pose.direction+(if(command.reverseSide==0)-Math.PI else Math.PI)).let {
                    kotlin.math.atan2(kotlin.math.sin(it),kotlin.math.cos(it)).toFloat()}
                else command.direction
            val delta=direction.toDouble()-poses.getValue(command.units.first()).direction
            val dx=pose.position.x-anchor.x;val dy=pose.position.y-anchor.y
            val x=if(rotation.kind==TacticalManeuver.REVERSE)pose.position.x else (anchor.x+dx*kotlin.math.cos(delta)+dy*kotlin.math.sin(delta)).toFloat()
            val y=if(rotation.kind==TacticalManeuver.REVERSE)pose.position.y else (anchor.y-dx*kotlin.math.sin(delta)+dy*kotlin.math.cos(delta)).toFloat()
            if(kotlin.math.abs(x)>100000 || kotlin.math.abs(y)>100000)return reject(NavigationRejection.RANGE)
            TacticalMotion(player.grid,now,pose,0f,direction,listOf(TacticalPoint(x,y)),rotation=rotation)
        } else TacticalMotion(player.grid,now,pose,
            command.velocity*(if(command.parallel)0.5f else 1f),if(command.parallel)pose.direction else command.direction,
            command.waypoints.map {
                val x=it.x+(pose.position.x-anchor.x);val y=it.y+(pose.position.y-anchor.y)
                if(kotlin.math.abs(x)>100000 || kotlin.math.abs(y)>100000)return reject(NavigationRejection.RANGE)
                TacticalPoint(x,y)
            },command.parallel,turnRate=TacticalMotion.NATIVE_TURN_RATE,waitSeconds=5*clockScale)}
        val attacked=stocks.tacticalCommands.air.values.filter {it.mode==1 && it.target in requestedMotions}.map {it.target}.toSet()
        val motions=requestedMotions.mapValues {(id,m)->if(id in attacked)m.copy(velocity=m.velocity*.5f) else m}
        var next=if(attacked.isEmpty())stocks else stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(air=
            stocks.tacticalCommands.air.mapValues {(_,wing)->if(wing.target in attacked)wing.copy(slowVelocity=requestedMotions.getValue(wing.target).velocity) else wing}))
        for((outfit,unit) in ships) {
            val current=next.outfits.getValue(outfit.id)
            val motion=motions.getValue(unit.id)
            val state=unit.tactical?.copy(motion=motion,nextDecisionAt=now,nextFireAt=now,target=0)
                ?: NpcTacticalState(motion,now,now)
            val updated=unit.copy(tactical=state,motionCommander=player.characterId)
            next=next.copy(outfits=next.outfits+(current.id to current.copy(activeShips=ActiveShipUnits(
                current.activeShips!!.values.map {if(it.id==unit.id)updated else it}))))
        }
        if(player.unitId in motions)player=player.copy(motion=motions.getValue(player.unitId))
        if(ships.isNotEmpty())player=player.copy(commandRange=checkNotNull(player.commandRange).reset(now))
        val prepared=production.prepareReplacement(stocks,next)
        committer.saveWorld(players+(request.account to player),prepared)
        navigation.publishCommitted(players,players+(request.account to player),session.generation)
        production.publishCommitted(stocks,prepared)
        return NavigationResult(player,preview.previous,tacticalMotions=motions)
    }
}
