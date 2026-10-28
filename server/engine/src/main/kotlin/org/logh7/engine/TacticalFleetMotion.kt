package org.logh7.engine

import kotlin.math.hypot

/** Commander-controlled regular ships share the durable per-unit motion with NPCs. Author: 최병호. */
internal object TacticalFleetMotion {
    fun move(request:NavigationRequest,session:SessionState,galaxy:Galaxy,navigation:Navigation,
        production:PlanetProduction,stocks:PlanetStocks,committer:WorldStateCommitter?):NavigationResult {
        val preview=navigation.apply(request.copy(tacticalMove=null),session,stocks,persist=false)
        var player=preview.player ?: return preview
        fun reject(reason:NavigationRejection)=NavigationResult(null,player,reason)
        if(committer==null)return reject(NavigationRejection.DISABLED)
        if(!player.cpPaused || player.baseId!=0 || player.mode!=5 || player.retreat!=null)return reject(NavigationRejection.TACTICAL)
        if(player.hull?.woundedUntil?.let {it>session.gameMillis/1000}==true)return reject(NavigationRejection.WOUNDED)
        if(request.captainCard!=if(player.power==Power.EMPIRE)59 else 195)return reject(NavigationRejection.CARD)
        val command=checkNotNull(request.tacticalMove)
        if(command.units.distinct().size!=command.units.size)return reject(NavigationRejection.UNIT)
        val now=session.gameMillis/1000
        if(now>0xffffffffL-TacticalMotion.WAIT_SECONDS)return reject(NavigationRejection.CLOCK)
        val players=navigation.snapshot(session.generation)
        val own=player.motion?.takeIf {it.grid==player.grid}?.pose(now) ?:
            TacticalDeployment.initial(player.unitId,player.power,player.grid,players.values,stocks,galaxy)
        val regular=command.units.filter {it!=player.unitId}
        val ships=regular.map {id ->
            if(id !in 1..999999)return reject(NavigationRejection.UNIT)
            val pair=stocks.outfits.values.flatMap {o->o.activeShips?.values.orEmpty().filter {it.id==id}.map {o to it}}
                .singleOrNull() ?: return reject(NavigationRejection.UNIT)
            val (o,u)=pair
            if(o.commanderId!=player.characterId || o.power!=(if(player.power==Power.EMPIRE)2 else 3) ||
                o.positionIn(galaxy)!=OutfitPosition(player.grid))return reject(NavigationRejection.CARD)
            if(u.boats==0 || u.support!=null || u.tactical?.retreat!=null)return reject(NavigationRejection.BUSY)
            pair
        }
        val poses=command.units.associateWith {id->if(id==player.unitId)own else {
            val unit=ships.single {it.second.id==id}.second
            (unit.tactical?.motion?.takeIf {it.grid==player.grid}?.pose(now) ?:
                TacticalDeployment.initial(id,player.power,player.grid,players.values,stocks,galaxy)).also {pose ->
                    if(hypot((pose.position.x-own.position.x).toDouble(),(pose.position.y-own.position.y).toDouble())>
                        TacticalSupportRules.COMMAND_RANGE)return reject(NavigationRejection.RANGE)
                }
        }}
        if(player.unitId in command.units && command.velocity>(player.energy ?: TacticalEnergy.ZERO).values[0]/100f)
            return reject(NavigationRejection.ENERGY)
        // Keep offsets around the selected formation centre; one-unit requests are unchanged.
        val centre=TacticalPoint(poses.values.map {it.position.x.toDouble()}.average().toFloat(),
            poses.values.map {it.position.y.toDouble()}.average().toFloat())
        val motions=poses.mapValues {(_,pose)->TacticalMotion(player.grid,now,pose,command.velocity,command.direction,
            command.waypoints.map {
                val x=it.x+(pose.position.x-centre.x);val y=it.y+(pose.position.y-centre.y)
                if(kotlin.math.abs(x)>100000 || kotlin.math.abs(y)>100000)return reject(NavigationRejection.RANGE)
                TacticalPoint(x,y)
            })}
        var next=stocks
        for((outfit,unit) in ships) {
            val current=next.outfits.getValue(outfit.id)
            val motion=motions.getValue(unit.id)
            val state=unit.tactical?.copy(motion=motion,nextDecisionAt=now,nextFireAt=now,target=0,retreat=null)
                ?: NpcTacticalState(motion,now,now)
            val updated=unit.copy(tactical=state,motionCommander=player.characterId)
            next=next.copy(outfits=next.outfits+(current.id to current.copy(activeShips=ActiveShipUnits(
                current.activeShips!!.values.map {if(it.id==unit.id)updated else it}))))
        }
        if(player.unitId in motions)player=player.copy(motion=motions.getValue(player.unitId))
        val prepared=production.prepareReplacement(stocks,next)
        committer.saveWorld(players+(request.account to player),prepared)
        navigation.publishCommitted(players,players+(request.account to player),session.generation)
        production.publishCommitted(stocks,prepared)
        return NavigationResult(player,preview.previous,tacticalMotions=motions)
    }
}
