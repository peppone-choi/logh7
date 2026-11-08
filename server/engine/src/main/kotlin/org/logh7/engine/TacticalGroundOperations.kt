package org.logh7.engine

import kotlin.math.hypot

/** TARGET/CLEAR are the reconstructed ground battle window's 0417/0418 orders (design-ground-battle-window.md). */
enum class TacticalGroundAction { DEPLOY, WITHDRAW, TARGET, CLEAR }
data class TacticalGroundCommand(val action:TacticalGroundAction,val units:List<Int>,val skill:Int=0,val target:Int=0) {
    init {require(units.size in 1..32 && units.all {it in 1..999999} && units.distinct().size==units.size && skill in 0..255 &&
        (if(action==TacticalGroundAction.TARGET)target in 1..999999 && target !in units else target==0) &&
        (action==TacticalGroundAction.DEPLOY || skill==0))}
}
/** Manual waiting five seconds plus twenty seconds operation, separate from automatic ground exchanges. Author: Peppone Choi. */
data class TacticalGroundOrder(val action:TacticalGroundAction,val character:Int,val grid:Int,val carrier:Int,val base:Int,
    val acceptedAt:Long,val readyAt:Long) {
    companion object {const val PREPARATION_SECONDS=25}
    init {require(character>0 && grid in 0..4999 && carrier in 1..999999 && base in 1..65535 &&
        acceptedAt in 0..0xffffffffL && readyAt in acceptedAt..0xffffffffL)}
}
data class TacticalGroundResult(val generation:Long,val order:TacticalGroundOrder,val troops:List<Int>,val seconds:Long,
    val recipients:Map<Int,String>,val cancelled:Boolean=false)

internal object TacticalGroundOperations {
    private fun owned(o:OutfitStock,id:Int,p:Navigator,galaxy:Galaxy,stocks:PlanetStocks)=o.activeShips?.values?.singleOrNull {it.id==id}?.let {TacticalAuthority.commander(o,it,stocks)==p.characterId}==true &&
        TacticalAuthority.camp(o,stocks)==TacticalAuthority.camp(p,stocks) && o.positionIn(galaxy)==OutfitPosition(p.grid)
    private fun berth(o:OutfitStock,carrier:Int,grid:Int)=o.activeShips?.values?.singleOrNull {it.id==carrier && it.boats>0}
        ?.posture?.takeIf {it.grid==grid && it.mode.requiresBase && it.order==null}
    private fun ground(o:OutfitStock,carrier:Int,base:Int,action:TacticalGroundAction)=o.activeGround?.values.orEmpty().filter {
        if(action==TacticalGroundAction.DEPLOY)it.carrier==carrier else it.origin==carrier && it.base==base
    }
    private fun admits(base:GalaxyBase,troops:List<ActiveGroundUnit>)=troops.all {u->
        GroundBattleRules.admitsCrewType(base,UnitSpecifications.crews.single {it.kind==u.stack.kind}.type)
    }
    private fun operational(ship:ActiveShipUnit,stocks:PlanetStocks)=
        ship.boats>ship.damaged && !stocks.tacticalCommands.confused(ship.id)
    private fun hasRoom(o:OutfitStock,ship:ActiveShipUnit,returning:Int)=
        o.activeGround?.values.orEmpty().count {it.carrier==ship.id}+returning<=GroundTransfer.capacity(ship.stack.kind)
    fun guard(request:NavigationRequest,session:SessionState,navigation:Navigation,stocks:PlanetStocks):NavigationResult? {
        if(request.tacticalStop!=null || request.tacticalGround!=null)return null
        val ids=request.tacticalPosture?.units ?: request.tacticalMove?.units ?: request.tacticalRetreat ?:
            request.tacticalShoot?.units ?: request.tacticalAttack?.units ?: request.tacticalSupport?.let {listOf(it.unit)} ?: return null
        val p=navigation.snapshot(session.generation)[request.account] ?: return null
        val member=session.participants[request.account] ?: return null
        if(p.characterId!=request.characterId || p.country!=request.country || member.country!=request.country || request.generation!=session.generation ||
            !member.online || member.connection!=request.connection)return null
        return if(stocks.outfits.values.any {o->o.activeGround?.values?.any {it.order?.carrier in ids}==true})
            NavigationResult(null,p,NavigationRejection.BUSY) else null
    }
    /** The commander of a landed troop's outfit chooses which enemy troop on the same base it attacks (evidence:guess). */
    private fun assign(request:NavigationRequest,session:SessionState,galaxy:Galaxy,navigation:Navigation,production:PlanetProduction,
        stocks:PlanetStocks,committer:WorldStateCommitter?):NavigationResult {
        val preview=navigation.apply(request.copy(tacticalGround=null),session,stocks,persist=false)
        val p=preview.player ?: return preview
        fun reject(reason:NavigationRejection)=NavigationResult(null,p,reason)
        if(committer==null)return reject(NavigationRejection.DISABLED)
        if(BattleDeath.deceased(stocks,p.characterId))return reject(NavigationRejection.IDENTITY)
        if(!p.cpPaused || p.baseId!=0 || p.mode!=5)return reject(NavigationRejection.TACTICAL)
        val now=session.gameMillis/1000;val command=checkNotNull(request.tacticalGround)
        if(p.hull?.woundedUntil?.let {it>now}==true)return reject(NavigationRejection.WOUNDED)
        val own=p.country ?: return reject(NavigationRejection.CARD)
        fun landed(id:Int)=stocks.outfits.values.firstNotNullOfOrNull {o->o.activeGround?.values?.singleOrNull {it.id==id && it.base>0}?.let {o to it}}
        val sites=command.units.map {id->
            val (outfit,unit)=landed(id) ?: return reject(NavigationRejection.UNIT)
            if(outfit.country!=own || GroundCommandAuthority.commander(outfit,unit,stocks,galaxy)!=p.characterId)return reject(NavigationRejection.CARD)
            if(galaxy.bases.singleOrNull {it.id==unit.base}?.grid!=p.grid)return reject(NavigationRejection.GRID)
            unit.base
        }.toSet()
        val c=stocks.tacticalCommands
        val targets=if(command.action==TacticalGroundAction.CLEAR)c.groundTargets-command.units.toSet() else {
            val (enemy,victim)=landed(command.target) ?: return reject(NavigationRejection.TARGET)
            if(enemy.country==own || sites!=setOf(victim.base))return reject(NavigationRejection.TARGET)
            c.groundTargets+command.units.associateWith {command.target}
        }
        val players=navigation.snapshot(session.generation)
        val next=stocks.copy(tacticalCommands=c.copy(groundTargets=targets))
        val prepared=production.prepareReplacement(stocks,next)
        committer.saveWorld(players,prepared)
        production.publishCommitted(stocks,prepared)
        return NavigationResult(p,preview.previous)
    }
    fun accept(request:NavigationRequest,session:SessionState,galaxy:Galaxy,navigation:Navigation,production:PlanetProduction,
        stocks:PlanetStocks,committer:WorldStateCommitter?,scale:Long):NavigationResult {
        if(request.tacticalGround?.action.let {it==TacticalGroundAction.TARGET || it==TacticalGroundAction.CLEAR})
            return assign(request,session,galaxy,navigation,production,stocks,committer)
        val preview=navigation.apply(request.copy(tacticalGround=null),session,stocks,persist=false)
        val p=preview.player ?: return preview
        fun reject(reason:NavigationRejection)=NavigationResult(null,p,reason)
        if(committer==null)return reject(NavigationRejection.DISABLED)
        if(BattleDeath.deceased(stocks,p.characterId))return reject(NavigationRejection.IDENTITY)
        if(!p.cpPaused || p.baseId!=0 || p.mode!=5)return reject(NavigationRejection.TACTICAL)
        val now=session.gameMillis/1000;val command=checkNotNull(request.tacticalGround)
        if(p.hull?.woundedUntil?.let {it>now}==true)return reject(NavigationRejection.WOUNDED)
        if(command.skill!=0)return reject(NavigationRejection.ACCESS) // Skill semantics are not reconstructed.
        val readyAt=now+TacticalGroundOrder.PREPARATION_SECONDS*scale
        if(readyAt>0xffffffffL)return reject(NavigationRejection.CLOCK)
        val players=navigation.snapshot(session.generation)
        val centre=(p.motion?.pose(now,p.posture,galaxy,stocks) ?: TacticalDeployment.initial(p.unitId,p.grid,players.values,stocks,galaxy)).position
        val radius=checkNotNull(p.commandRange).radius(now)
        var next=stocks
        for(id in command.units) {
            val o=next.outfits.values.singleOrNull {it.activeShips?.values?.any {s->s.id==id}==true} ?: return reject(NavigationRejection.UNIT)
            if(!owned(o,id,p,galaxy,next))return reject(NavigationRejection.CARD)
            val ship=o.activeShips!!.values.single {it.id==id}
            val state=berth(o,id,p.grid) ?: return reject(NavigationRejection.BASE)
            if(!operational(ship,next))return reject(NavigationRejection.ACCESS)
            val base=galaxy.bases.singleOrNull {it.id==state.base && it.grid==p.grid} ?: return reject(NavigationRejection.BASE)
            if(ship.support!=null || ship.tactical?.retreat!=null || o.activeGround?.values?.any {it.order?.carrier==id}==true)
                return reject(NavigationRejection.BUSY)
            val motion = ship.tactical?.motion?.takeIf { it.grid == p.grid && state.mode == TacticalShipMode.ANCHORED }
            val point = motion?.pose(now,state,galaxy,stocks)?.position ?: TacticalBasePosition.at(base,now,stocks)
            if(hypot((point.x-centre.x).toDouble(),(point.y-centre.y).toDouble())>radius)return reject(NavigationRejection.RANGE)
            val troops=ground(o,id,state.base,command.action)
            if(troops.isEmpty())return reject(NavigationRejection.UNIT)
            if(troops.any {it.order!=null})return reject(NavigationRejection.BUSY)
            if(command.action==TacticalGroundAction.DEPLOY) {
                // p.51: fortresses and special (gas etc.) planets refuse 装甲兵 (U-09).
                if(!admits(base,troops))return reject(NavigationRejection.CREW)
                val occupied=next.outfits.values.filter {it.country==o.country}.sumOf {other->
                    other.activeGround?.values.orEmpty().count {it.base==base.id || it.order?.let {a->a.action==TacticalGroundAction.DEPLOY && a.base==base.id}==true}}
                if(occupied+troops.size>GroundBattleRules.BOX_CAPACITY_PER_FACTION)return reject(NavigationRejection.GRID)
            } else if(!hasRoom(o,ship,troops.size))return reject(NavigationRejection.UNIT)
            val order=TacticalGroundOrder(command.action,p.characterId,p.grid,id,state.base,now,readyAt)
            val selected=troops.map {it.id}.toSet()
            val changed=o.copy(activeGround=ActiveGroundUnits(o.activeGround!!.values.map {if(it.id in selected)it.copy(order=order) else it}))
            next=next.copy(outfits=next.outfits+(o.id to changed))
        }
        val player=p.copy(commandRange=checkNotNull(p.commandRange).reset(now))
        val prepared=production.prepareReplacement(stocks,next)
        committer.saveWorld(players+(request.account to player),prepared)
        navigation.publishCommitted(players,players+(request.account to player),session.generation);production.publishCommitted(stocks,prepared)
        return NavigationResult(player,preview.previous,groundReadyAt=readyAt)
    }
    fun advance(session:SessionState,galaxy:Galaxy,players:Map<String,Navigator>,stocks:PlanetStocks,
        events:MutableList<TacticalGroundResult>):PlanetStocks {
        var next=stocks;val now=session.gameMillis/1000
        val pending=stocks.outfits.values.flatMap {o->o.activeGround?.values.orEmpty().mapNotNull {it.order}}.distinct()
            .sortedWith(compareBy({it.readyAt},{it.acceptedAt},{it.carrier}))
        for(order in pending) {
            val o=next.outfits.values.singleOrNull {it.activeGround?.values?.any {u->u.order==order}==true} ?: continue
            val p=players.values.singleOrNull {it.characterId==order.character}
            val state=berth(o,order.carrier,order.grid)
            val carrier=o.activeShips?.values?.singleOrNull {it.id==order.carrier}
            val units=o.activeGround!!.values.filter {it.order==order}
            val base=galaxy.bases.singleOrNull {it.id==order.base && it.grid==order.grid}
            val valid=session.ended==null && p!=null && base!=null && carrier!=null && operational(carrier,next) &&
                !BattleDeath.deceased(next,p.characterId) && p.cpPaused && p.baseId==0 && p.grid==order.grid &&
                owned(o,order.carrier,p,galaxy,next) && state?.base==order.base &&
                when(order.action) {
                    TacticalGroundAction.DEPLOY -> admits(base,units)
                    TacticalGroundAction.WITHDRAW -> hasRoom(o,carrier,units.size)
                    else -> false
                }
            if(valid && now<order.readyAt)continue
            val completed=valid && (order.action!=TacticalGroundAction.DEPLOY ||
                next.outfits.values.filter {it.country==o.country}.sumOf {other->
                    other.activeGround?.values.orEmpty().count {it.base==order.base}
                }+units.size<=GroundBattleRules.BOX_CAPACITY_PER_FACTION)
            val ids=units.map {it.id}.toSet()
            val roster=ActiveGroundUnits(o.activeGround.values.map {unit ->
                if(unit.id !in ids)unit else if(!completed)unit.copy(order=null)
                else if(order.action==TacticalGroundAction.DEPLOY)unit.copy(carrier=0,base=order.base,origin=order.carrier,order=null)
                else unit.copy(carrier=order.carrier,base=0,origin=0,order=null)
            })
            next=next.copy(outfits=next.outfits+(o.id to o.copy(activeGround=roster)))
            val recipients=session.participants.mapNotNull {(a,m)->players[a]?.takeIf {
                m.online && m.connection!=null && it.generation==session.generation && it.grid==order.grid && it.cpPaused && it.baseId==0
            }?.let {it.characterId to checkNotNull(m.connection)}}.toMap()
            events+=TacticalGroundResult(session.generation,order,ids.toList(),now,recipients,!completed)
        }
        return next
    }
    fun cancel(stocks:PlanetStocks,carriers:List<Int>):Pair<PlanetStocks,Set<TacticalGroundAction>> {
        val stopped=linkedSetOf<TacticalGroundAction>()
        val outfits=stocks.outfits.mapValues {(_,o)->
            if(o.activeGround?.values?.any {it.order?.carrier in carriers}!=true)o
            else o.copy(activeGround=ActiveGroundUnits(o.activeGround.values.map {unit->
                val order=unit.order
                if(order!=null && order.carrier in carriers) {stopped+=order.action;unit.copy(order=null)} else unit
            }))}
        return (if(stopped.isEmpty())stocks else stocks.copy(outfits=outfits)) to stopped
    }
}
