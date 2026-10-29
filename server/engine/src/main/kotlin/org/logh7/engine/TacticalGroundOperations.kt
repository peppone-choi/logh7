package org.logh7.engine

import kotlin.math.hypot

enum class TacticalGroundAction { DEPLOY, WITHDRAW }
data class TacticalGroundCommand(val action:TacticalGroundAction,val units:List<Int>,val skill:Int=0) {
    init {require(units.size in 1..32 && units.all {it in 1..999999} && units.distinct().size==units.size && skill in 0..255)}
}
/** Manual waiting five seconds plus twenty seconds operation, separate from automatic ground exchanges. Author: 최병호. */
data class TacticalGroundOrder(val action:TacticalGroundAction,val character:Int,val grid:Int,val carrier:Int,val base:Int,
    val acceptedAt:Long,val readyAt:Long) {
    init {require(character>0 && grid in 0..4999 && carrier in 1..999999 && base in 1..65535 &&
        acceptedAt in 0..0xffffffffL && readyAt in acceptedAt..0xffffffffL)}
}
data class TacticalGroundResult(val generation:Long,val order:TacticalGroundOrder,val troops:List<Int>,val seconds:Long,
    val recipients:Map<Int,String>,val cancelled:Boolean=false)

internal object TacticalGroundOperations {
    private fun owned(o:OutfitStock,id:Int,p:Navigator,galaxy:Galaxy,stocks:PlanetStocks)=o.activeShips?.values?.singleOrNull {it.id==id}?.let {TacticalAuthority.commander(o,it,stocks)==p.characterId}==true &&
        o.power==(if(p.power==Power.EMPIRE)2 else 3) && o.positionIn(galaxy)==OutfitPosition(p.grid)
    private fun berth(o:OutfitStock,carrier:Int,grid:Int)=o.activeShips?.values?.singleOrNull {it.id==carrier && it.boats>0}
        ?.posture?.takeIf {it.grid==grid && it.mode.requiresBase && it.order==null}
    private fun ground(o:OutfitStock,carrier:Int,base:Int,action:TacticalGroundAction)=o.activeGround?.values.orEmpty().filter {
        if(action==TacticalGroundAction.DEPLOY)it.carrier==carrier else it.origin==carrier && it.base==base
    }
    fun guard(request:NavigationRequest,session:SessionState,navigation:Navigation,stocks:PlanetStocks):NavigationResult? {
        if(request.tacticalStop!=null || request.tacticalGround!=null)return null
        val ids=request.tacticalPosture?.units ?: request.tacticalMove?.units ?: request.tacticalRetreat ?:
            request.tacticalShoot?.units ?: request.tacticalAttack?.units ?: request.tacticalSupport?.let {listOf(it.unit)} ?: return null
        val p=navigation.snapshot(session.generation)[request.account] ?: return null
        val member=session.participants[request.account] ?: return null
        if(p.characterId!=request.characterId || p.power!=request.power || request.generation!=session.generation ||
            !member.online || member.connection!=request.connection)return null
        return if(stocks.outfits.values.any {o->o.activeGround?.values?.any {it.order?.carrier in ids}==true})
            NavigationResult(null,p,NavigationRejection.BUSY) else null
    }
    fun accept(request:NavigationRequest,session:SessionState,galaxy:Galaxy,navigation:Navigation,production:PlanetProduction,
        stocks:PlanetStocks,committer:WorldStateCommitter?,scale:Long):NavigationResult {
        val preview=navigation.apply(request.copy(tacticalGround=null),session,stocks,persist=false)
        val p=preview.player ?: return preview
        fun reject(reason:NavigationRejection)=NavigationResult(null,p,reason)
        if(committer==null)return reject(NavigationRejection.DISABLED)
        if(!p.cpPaused || p.baseId!=0 || p.mode!=5)return reject(NavigationRejection.TACTICAL)
        val now=session.gameMillis/1000;val command=checkNotNull(request.tacticalGround)
        if(p.hull?.woundedUntil?.let {it>now}==true)return reject(NavigationRejection.WOUNDED)
        if(command.skill!=0)return reject(NavigationRejection.ACCESS) // Skill semantics are not reconstructed.
        if(now>0xffffffffL-25*scale)return reject(NavigationRejection.CLOCK)
        val players=navigation.snapshot(session.generation)
        val centre=(p.motion?.pose(now,p.posture,galaxy,stocks) ?: TacticalDeployment.initial(p.unitId,p.power,p.grid,players.values,stocks,galaxy)).position
        val radius=checkNotNull(p.commandRange).radius(now)
        var next=stocks
        for(id in command.units) {
            val o=next.outfits.values.singleOrNull {it.activeShips?.values?.any {s->s.id==id}==true} ?: return reject(NavigationRejection.UNIT)
            if(!owned(o,id,p,galaxy,next))return reject(NavigationRejection.CARD)
            val ship=o.activeShips!!.values.single {it.id==id}
            val state=berth(o,id,p.grid) ?: return reject(NavigationRejection.BASE)
            val base=galaxy.bases.singleOrNull {it.id==state.base && it.grid==p.grid} ?: return reject(NavigationRejection.BASE)
            if(ship.support!=null || ship.tactical?.retreat!=null || o.activeGround?.values?.any {it.order?.carrier==id}==true)
                return reject(NavigationRejection.BUSY)
            val point=if(state.mode==TacticalShipMode.STATIONED)TacticalBasePosition.at(base,now,stocks)
                else ship.tactical?.motion?.pose(now,state,galaxy,stocks)?.position ?: TacticalDeployment.initial(id,p.power,p.grid,players.values,stocks,galaxy).position
            if(hypot((point.x-centre.x).toDouble(),(point.y-centre.y).toDouble())>radius)return reject(NavigationRejection.RANGE)
            val troops=ground(o,id,state.base,command.action)
            if(troops.isEmpty())return reject(NavigationRejection.UNIT)
            if(troops.any {it.order!=null})return reject(NavigationRejection.BUSY)
            if(command.action==TacticalGroundAction.DEPLOY) {
                // Fortress restriction is known. Special-planet classification remains separate unfinished work.
                if(base.kind==1 && troops.any {u->UnitSpecifications.crews.single {it.kind==u.stack.kind}.type==2})return reject(NavigationRejection.BASE)
                val occupied=next.outfits.values.filter {it.power==o.power}.sumOf {other->
                    other.activeGround?.values.orEmpty().count {it.base==base.id || it.order?.let {a->a.action==TacticalGroundAction.DEPLOY && a.base==base.id}==true}}
                if(occupied+troops.size>GroundBattleRules.BOX_CAPACITY_PER_FACTION)return reject(NavigationRejection.GRID)
            } else if(o.activeGround!!.values.count {it.carrier==id}+troops.size>10)return reject(NavigationRejection.UNIT)
            val order=TacticalGroundOrder(command.action,p.characterId,p.grid,id,state.base,now,now+25*scale)
            val selected=troops.map {it.id}.toSet()
            val changed=o.copy(activeGround=ActiveGroundUnits(o.activeGround!!.values.map {if(it.id in selected)it.copy(order=order) else it}))
            next=next.copy(outfits=next.outfits+(o.id to changed))
        }
        val player=p.copy(commandRange=checkNotNull(p.commandRange).reset(now))
        val prepared=production.prepareReplacement(stocks,next)
        committer.saveWorld(players+(request.account to player),prepared)
        navigation.publishCommitted(players,players+(request.account to player),session.generation);production.publishCommitted(stocks,prepared)
        return NavigationResult(player,preview.previous,groundReadyAt=now+25*scale)
    }
    fun advance(session:SessionState,galaxy:Galaxy,players:Map<String,Navigator>,stocks:PlanetStocks,
        events:MutableList<TacticalGroundResult>):PlanetStocks {
        var next=stocks;val now=session.gameMillis/1000
        val pending=stocks.outfits.values.flatMap {o->o.activeGround?.values.orEmpty().mapNotNull {it.order}}.distinct()
        for(order in pending) {
            val o=next.outfits.values.singleOrNull {it.activeGround?.values?.any {u->u.order==order}==true} ?: continue
            val p=players.values.singleOrNull {it.characterId==order.character}
            val state=berth(o,order.carrier,order.grid)
            val reserved=next.outfits.values.filter {it.power==o.power}.sumOf {other->other.activeGround?.values.orEmpty().count {
                it.base==order.base || it.order?.let {a->a.action==TacticalGroundAction.DEPLOY && a.base==order.base}==true}}
            val valid=(order.action!=TacticalGroundAction.DEPLOY || reserved<=GroundBattleRules.BOX_CAPACITY_PER_FACTION) && session.ended==null && p!=null && p.cpPaused && p.baseId==0 && p.grid==order.grid &&
                owned(o,order.carrier,p,galaxy,next) && state?.base==order.base && galaxy.bases.any {it.id==order.base && it.grid==order.grid}
            if(valid && now<order.readyAt)continue
            val units=o.activeGround!!.values.filter {it.order==order};val ids=units.map {it.id}.toSet()
            val roster=ActiveGroundUnits(o.activeGround.values.map {unit ->
                if(unit.id !in ids)unit else if(!valid)unit.copy(order=null)
                else if(order.action==TacticalGroundAction.DEPLOY)unit.copy(carrier=0,base=order.base,origin=order.carrier,order=null)
                else unit.copy(carrier=order.carrier,base=0,origin=0,order=null)
            })
            next=next.copy(outfits=next.outfits+(o.id to o.copy(activeGround=roster)))
            val recipients=session.participants.mapNotNull {(a,m)->players[a]?.takeIf {
                m.online && m.connection!=null && it.generation==session.generation && it.grid==order.grid && it.cpPaused && it.baseId==0
            }?.let {it.characterId to checkNotNull(m.connection)}}.toMap()
            events+=TacticalGroundResult(session.generation,order,ids.toList(),now,recipients,!valid)
        }
        return next
    }
    fun cancel(stocks:PlanetStocks,carriers:List<Int>):PlanetStocks {
        val outfits=stocks.outfits.mapValues {(_,o)->
            if(o.activeGround?.values?.any {it.order?.carrier in carriers}!=true)o
            else o.copy(activeGround=ActiveGroundUnits(o.activeGround.values.map {if(it.order?.carrier in carriers)it.copy(order=null) else it}))}
        return if(outfits==stocks.outfits)stocks else stocks.copy(outfits=outfits)
    }
}
