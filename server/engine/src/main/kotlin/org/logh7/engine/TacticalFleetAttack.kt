package org.logh7.engine

import kotlin.math.hypot

/** Durable regular-ship order. Weapon cadence is replacement policy. Author: 최병호. */
data class TacticalUnitAttack(val commander:Int,val order:TacticalAttackOrder) {
    init {require(commander>0)}
}

internal object TacticalFleetAttack {
    private fun replace(stocks:PlanetStocks,outfit:OutfitStock,unit:ActiveShipUnit):PlanetStocks =
        stocks.copy(outfits=stocks.outfits+(outfit.id to outfit.copy(activeShips=ActiveShipUnits(
            outfit.activeShips!!.values.map {if(it.id==unit.id)unit else it}))))

    fun accept(request:NavigationRequest,session:SessionState,galaxy:Galaxy,navigation:Navigation,
        production:PlanetProduction,stocks:PlanetStocks,committer:WorldStateCommitter?,scale:Long):NavigationResult {
        val preview=navigation.apply(request.copy(tacticalAttack=null),session,stocks,persist=false)
        val player=preview.player ?: return preview
        fun reject(reason:NavigationRejection)=NavigationResult(null,player,reason)
        if(committer==null)return reject(NavigationRejection.DISABLED)
        if(!player.cpPaused || player.baseId!=0 || player.mode!=5)return reject(NavigationRejection.TACTICAL)
        if(request.captainCard!=if(player.power==Power.EMPIRE)59 else 195)return reject(NavigationRejection.CARD)
        val command=checkNotNull(request.tacticalAttack)
        if(command.units.isEmpty() || command.units.size>32 || command.units.distinct().size!=command.units.size)
            return reject(NavigationRejection.UNIT)
        if(command.mode !in 0..2 || command.target!=0)return reject(NavigationRejection.TARGET)
        if(command.mode!=0 && player.retreat!=null)return reject(NavigationRejection.BUSY)
        val now=session.gameMillis/1000
        if(now>0xffffffffL-5*scale)return reject(NavigationRejection.CLOCK)
        val players=navigation.snapshot(session.generation)
        val own=player.motion?.pose(now) ?: TacticalDeployment.initial(player.unitId,player.power,player.grid,players.values,stocks,galaxy)
        val ships=command.units.filter {it!=player.unitId}.map {id ->
            if(id !in 1..999999)return reject(NavigationRejection.UNIT)
            val pair=stocks.outfits.values.flatMap {o->o.activeShips?.values.orEmpty().filter {it.id==id}.map {o to it}}
                .singleOrNull() ?: return reject(NavigationRejection.UNIT)
            val(o,u)=pair
            if(o.commanderId!=player.characterId || o.power!=(if(player.power==Power.EMPIRE)2 else 3) ||
                o.positionIn(galaxy)!=OutfitPosition(player.grid))return reject(NavigationRejection.CARD)
            if(u.boats<=u.damaged || u.support!=null || u.tactical?.retreat!=null)return reject(NavigationRejection.BUSY)
            val pose=u.tactical?.takeIf {it.motion.grid==player.grid}?.motion?.pose(now) ?:
                TacticalDeployment.initial(id,player.power,player.grid,players.values,stocks,galaxy)
            if(hypot((pose.position.x-own.position.x).toDouble(),(pose.position.y-own.position.y).toDouble())>
                checkNotNull(player.commandRange).radius(now))return reject(NavigationRejection.RANGE)
            Triple(o,u,pose)
        }
        if(player.unitId in command.units && (player.hull?.kind!=request.shipKind || command.mode!=0 && player.fire?.pending!=null))
            return reject(NavigationRejection.UNIT)
        val order=if(command.mode==0)null else TacticalAttackOrder(command.mode,now,now+5*scale,now+5*scale)
        var inventory=stocks
        for((o,u,pose) in ships) {
            val current=inventory.outfits.getValue(o.id)
            val motion=u.tactical?.takeIf {it.motion.grid==player.grid}?.motion ?:
                TacticalMotion(player.grid,now,pose,0f,pose.direction,listOf(pose.position))
            val state=u.tactical?.takeIf {it.motion.grid==player.grid} ?: NpcTacticalState(motion,now,now)
            inventory=replace(inventory,current,u.copy(tactical=state,motionCommander=player.characterId,attack=order?.let {TacticalUnitAttack(player.characterId,it)},shot=null))
        }
        var next=if(player.unitId in command.units)player.copy(attack=order,
            fire=if(command.mode==0)player.fire?.copy(pending=null) else player.fire) else player
        if(ships.isNotEmpty())next=next.copy(commandRange=checkNotNull(player.commandRange).reset(now))
        val prepared=production.prepareReplacement(stocks,inventory)
        committer.saveWorld(players+(request.account to next),prepared)
        navigation.publishCommitted(players,players+(request.account to next),session.generation)
        production.publishCommitted(stocks,prepared)
        return NavigationResult(next,preview.previous)
    }

    fun advance(session:SessionState,galaxy:Galaxy,players:MutableMap<String,Navigator>,original:PlanetStocks,
        scale:Long,impacts:MutableList<TacticalImpact>):PlanetStocks {
        var stocks=original
        val now=session.gameMillis/1000
        for(outfitId in original.outfits.keys.sorted()) for(id in original.outfits.getValue(outfitId).activeShips?.values.orEmpty()
            .filter {it.attack!=null}.map {it.id}) {
            var outfit=stocks.outfits.getValue(outfitId)
            var unit=outfit.activeShips!!.values.single {it.id==id}
            val command=unit.attack ?: continue
            val position=outfit.positionIn(galaxy)
            val commander=players.values.singleOrNull {it.characterId==command.commander &&
                it.power==(if(outfit.power==2)Power.EMPIRE else Power.ALLIANCE)}
            if(outfit.commanderId!=command.commander || position.baseId!=0 || unit.tactical?.motion?.grid!=position.grid ||
                unit.boats<=unit.damaged || unit.support!=null || unit.tactical.retreat!=null ||
                commander==null || commander.baseId!=0 || !commander.cpPaused || commander.grid!=position.grid) {
                stocks=replace(stocks,outfit,unit.copy(attack=null));continue
            }
            if(now<command.order.nextAt || now>0xffffffffL-5*scale || unit.tactical.sequence>Long.MAX_VALUE-3)continue
            val power=if(outfit.power==2)Power.EMPIRE else Power.ALLIANCE
            val spec=UnitSpecifications.ships.single {it.kind==unit.stack.kind}
            val ids=(players.values.map {it.unitId}+stocks.outfits.values.flatMap {it.activeShips?.values.orEmpty().map {u->u.id}})
                .distinct().sorted()
            if(ids.none {TacticalCombat.target(it,position.grid,power,stocks,galaxy,players)!=null}) {
                stocks=replace(stocks,outfit,unit.copy(attack=null));continue
            }
            if(command.order.mode==1 && !TacticalReload.salvoReady(unit,now,scale))continue
            for(weapon in TacticalWeapon.entries) {
                outfit=stocks.outfits.getValue(outfitId);unit=outfit.activeShips!!.values.single {it.id==id}
                val state=checkNotNull(unit.tactical)
                val mount=TacticalArmaments.mount(spec,weapon)
                val cost=TacticalFleetShoot.cost(unit,mount,weapon)
                if(mount.power==0 || !TacticalReload.canFire(unit,weapon,now,scale) || OutfitSupplies.distribution(outfit).getValue(id)<cost)continue
                val origin=state.motion.pose(now)
                val victim=ids.asSequence().mapNotNull {targetId -> TacticalCombat.target(targetId,position.grid,power,stocks,galaxy,players)
                    ?.let {targetId to it}}.firstOrNull {(_,t)->
                        TacticalArmaments.effectiveness(mount,origin,pose(t,now,players,stocks,galaxy).position)>0} ?: continue
                val effect=TacticalArmaments.effectiveness(mount,origin,pose(victim.second,now,players,stocks,galaxy).position)
                val resolved=TacticalCombat.strike(session,galaxy,players,stocks,id,position.grid,state.sequence+1,now,
                    victim.first,weapon,mount.copy(power=((mount.power.toLong()*(unit.boats-unit.damaged)+spec.number-1)/spec.number).toInt()),
                    effect,origin,victim.second)
                stocks=resolved.first;impacts+=resolved.second
                outfit=stocks.outfits.getValue(outfitId)
                if(cost>0)outfit=OutfitSupplies.change(outfit,id,-cost)
                unit=outfit.activeShips!!.values.single {it.id==id}
                stocks=replace(stocks,outfit,unit.copy(tactical=state.copy(sequence=state.sequence+1),
                    reload=(unit.reload ?: TacticalReload()).fired(weapon,now,scale),
                    attack=command.copy(order=command.order.copy(nextAt=now+5*scale))))
            }
        }
        return stocks
    }

    internal fun pose(target:TacticalCombat.Target,now:Long,players:Map<String,Navigator>,stocks:PlanetStocks,galaxy:Galaxy):TacticalPose =
        target.personal?.second?.let {it.motion?.pose(now) ?: TacticalDeployment.initial(it.unitId,it.power,it.grid,players.values,stocks,galaxy)}
            ?: target.regular!!.let {(o,u)->u.tactical?.motion?.pose(now) ?: TacticalDeployment.initial(u.id,
                if(o.power==2)Power.EMPIRE else Power.ALLIANCE,o.positionIn(galaxy).grid,players.values,stocks,galaxy)}
}
