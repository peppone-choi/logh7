package org.logh7.engine

import kotlin.math.hypot

/** One accepted regular-ship shot; delay/cost scaling are replacement policy. Author: 최병호. */
data class TacticalUnitShot(val commander:Int,val weapon:TacticalWeapon,val target:Int,val acceptedAt:Long,val firesAt:Long) {
    init {require(commander>0 && validCombatTarget(target) && acceptedAt in 0..0xffffffffL && firesAt in acceptedAt..0xffffffffL)}
}

internal object TacticalFleetShoot {
    private fun replace(stocks:PlanetStocks,outfit:OutfitStock,unit:ActiveShipUnit)=
        stocks.copy(outfits=stocks.outfits+(outfit.id to outfit.copy(activeShips=ActiveShipUnits(
            outfit.activeShips!!.values.map {if(it.id==unit.id)unit else it}))))
    internal fun cost(unit:ActiveShipUnit,mount:TacticalWeaponMount,weapon:TacticalWeapon)=
        if(weapon==TacticalWeapon.BEAM)0 else maxOf(1,mount.consumption)*(unit.boats-unit.damaged)

    fun accept(request:NavigationRequest,session:SessionState,galaxy:Galaxy,navigation:Navigation,
        production:PlanetProduction,stocks:PlanetStocks,committer:WorldStateCommitter?,scale:Long):NavigationResult {
        val preview=navigation.apply(request.copy(tacticalShoot=null),session,stocks,persist=false)
        val player=preview.player ?: return preview
        fun reject(reason:NavigationRejection)=NavigationResult(null,player,reason)
        if(committer==null)return reject(NavigationRejection.DISABLED)
        if(!player.cpPaused || player.baseId!=0 || player.mode!=5)return reject(NavigationRejection.TACTICAL)
        if(player.retreat!=null)return reject(NavigationRejection.BUSY)
        if(request.captainCard!=if(player.power==Power.EMPIRE)59 else 195)return reject(NavigationRejection.CARD)
        val command=checkNotNull(request.tacticalShoot)
        if(command.units.isEmpty() || command.units.size>32 || command.units.distinct().size!=command.units.size)
            return reject(NavigationRejection.UNIT)
        val weapon=TacticalWeapon.entries.singleOrNull {it.wire==command.arms} ?: return reject(NavigationRejection.WEAPON)
        if(command.targetKind!=1)return reject(NavigationRejection.WEAPON)
        val now=session.gameMillis/1000
        if(now>0xffffffffL-5*scale)return reject(NavigationRejection.CLOCK)
        val players=navigation.snapshot(session.generation)
        val victim=TacticalCombat.target(command.target,player.grid,player.power,stocks,galaxy,players)
            ?: return reject(NavigationRejection.TARGET)
        val own=player.motion?.pose(now) ?: TacticalDeployment.initial(player.unitId,player.power,player.grid,players.values,stocks,galaxy)
        val ships=command.units.filter {it!=player.unitId}.map {id ->
            if(id !in 1..999999)return reject(NavigationRejection.UNIT)
            val(o,u)=stocks.outfits.values.flatMap {o->o.activeShips?.values.orEmpty().filter {it.id==id}.map {o to it}}
                .singleOrNull() ?: return reject(NavigationRejection.UNIT)
            if(o.commanderId!=player.characterId || o.power!=(if(player.power==Power.EMPIRE)2 else 3) ||
                o.positionIn(galaxy)!=OutfitPosition(player.grid))return reject(NavigationRejection.CARD)
            if(u.boats<=u.damaged || u.shot!=null || u.support!=null || u.tactical?.retreat!=null)return reject(NavigationRejection.BUSY)
            if(u.tactical?.sequence==Long.MAX_VALUE)return reject(NavigationRejection.CLOCK)
            val pose=u.tactical?.takeIf {it.motion.grid==player.grid}?.motion?.pose(now) ?:
                TacticalDeployment.initial(id,player.power,player.grid,players.values,stocks,galaxy)
            val mount=TacticalArmaments.mount(UnitSpecifications.ships.single {it.kind==u.stack.kind},weapon)
            if(mount.power==0)return reject(NavigationRejection.WEAPON)
            if(OutfitSupplies.distribution(o).getValue(id)<cost(u,mount,weapon))return reject(NavigationRejection.SUPPLY)
            if(hypot((pose.position.x-own.position.x).toDouble(),(pose.position.y-own.position.y).toDouble())>
                checkNotNull(player.commandRange).radius(now) || TacticalArmaments.effectiveness(mount,pose,
                    TacticalFleetAttack.pose(victim,now,players,stocks,galaxy).position)==0)return reject(NavigationRejection.RANGE)
            Triple(o,u,pose)
        }
        var next=player
        if(player.unitId in command.units) {
            val personal=TacticalCombat.accept(request.copy(tacticalShoot=command.copy(units=listOf(player.unitId))),
                session,galaxy,navigation,stocks,committer,scale,persist=false)
            next=personal.player ?: return personal
        }
        var inventory=stocks
        for((o,u,pose) in ships) {
            val motion=u.tactical?.takeIf {it.motion.grid==player.grid}?.motion ?:
                TacticalMotion(player.grid,now,pose,0f,pose.direction,listOf(pose.position))
            val state=u.tactical?.takeIf {it.motion.grid==player.grid} ?: NpcTacticalState(motion,now,now)
            inventory=replace(inventory,inventory.outfits.getValue(o.id),u.copy(tactical=state,motionCommander=player.characterId,
                attack=null,shot=TacticalUnitShot(player.characterId,weapon,command.target,now,now+5*scale)))
        }
        if(ships.isNotEmpty())next=next.copy(commandRange=checkNotNull(next.commandRange).reset(now))
        val prepared=production.prepareReplacement(stocks,inventory)
        committer.saveWorld(players+(request.account to next),prepared)
        navigation.publishCommitted(players,players+(request.account to next),session.generation)
        production.publishCommitted(stocks,prepared)
        return NavigationResult(next,preview.previous)
    }

    fun advance(session:SessionState,galaxy:Galaxy,players:MutableMap<String,Navigator>,original:PlanetStocks,
        impacts:MutableList<TacticalImpact>):PlanetStocks {
        var stocks=original
        val now=session.gameMillis/1000
        for(outfitId in original.outfits.keys.sorted()) for(id in original.outfits.getValue(outfitId).activeShips?.values.orEmpty()
            .filter {it.shot!=null}.map {it.id}) {
            var outfit=stocks.outfits.getValue(outfitId)
            var unit=outfit.activeShips!!.values.single {it.id==id}
            val shot=unit.shot ?: continue
            val state=checkNotNull(unit.tactical)
            val position=outfit.positionIn(galaxy)
            val power=if(outfit.power==2)Power.EMPIRE else Power.ALLIANCE
            val commander=players.values.singleOrNull {it.characterId==shot.commander && it.power==power}
            if(outfit.commanderId!=shot.commander || position.baseId!=0 || state.motion.grid!=position.grid ||
                unit.boats<=unit.damaged || unit.support!=null || state.retreat!=null || commander==null ||
                commander.baseId!=0 || !commander.cpPaused || commander.grid!=position.grid) {
                stocks=replace(stocks,outfit,unit.copy(shot=null));continue
            }
            if(now<shot.firesAt)continue
            // Clear even a missed/cancelled shot; retries cannot turn a one-shot command into auto fire.
            stocks=replace(stocks,outfit,unit.copy(shot=null))
            val victim=TacticalCombat.target(shot.target,position.grid,power,stocks,galaxy,players) ?: continue
            val spec=UnitSpecifications.ships.single {it.kind==unit.stack.kind}
            val mount=TacticalArmaments.mount(spec,shot.weapon)
            val origin=state.motion.pose(now)
            val effect=TacticalArmaments.effectiveness(mount,origin,TacticalFleetAttack.pose(victim,now,players,stocks,galaxy).position)
            val cost=cost(unit,mount,shot.weapon)
            if(effect==0 || mount.power==0 || state.sequence==Long.MAX_VALUE || OutfitSupplies.distribution(outfit).getValue(id)<cost)continue
            val resolved=TacticalCombat.strike(session,galaxy,players,stocks,id,position.grid,state.sequence+1,now,shot.target,shot.weapon,
                mount.copy(power=((mount.power.toLong()*(unit.boats-unit.damaged)+spec.number-1)/spec.number).toInt()),effect,origin,victim)
            stocks=resolved.first;impacts+=resolved.second
            outfit=stocks.outfits.getValue(outfitId)
            if(cost>0)outfit=OutfitSupplies.change(outfit,id,-cost)
            unit=outfit.activeShips!!.values.single {it.id==id}
            stocks=replace(stocks,outfit,unit.copy(tactical=state.copy(sequence=state.sequence+1)))
        }
        return stocks
    }
}
