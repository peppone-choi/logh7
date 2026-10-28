package org.logh7.engine

import kotlin.math.hypot

data class TacticalPostureChange(val units:List<Int>,val mode:TacticalShipMode,val base:Int,val sortie:Boolean=false) {
    init {require(units.size in 1..32 && units.all {it>0} && units.distinct().size==units.size &&
        base in 0..65535 && (if(sortie)mode==TacticalShipMode.ANCHORED && base==0 else (base>0)==mode.requiresBase))}
}
/** Committed change; only these connections may receive this generation's notification. Author: 최병호. */
data class TacticalPostureResult(val generation:Long,val grid:Int,val seconds:Long,val character:Int,
    val mode:TacticalShipMode,val base:Int,val units:Map<Int,TacticalPose>,val recipients:Map<Int,String>,
    val spot:Int=0,val spotOwner:Int=0,val cancelled:Boolean=false,val sortie:Boolean=false) {
    init {require(generation>0 && grid in 0..4999 && seconds in 0..0xffffffffL && character>0 && base in 0..65535 &&
        (base>0)==mode.requiresBase && units.size in 1..32 && units.keys.all {it>0} && spot>=0 && spotOwner>=0 &&
        recipients.keys.all {it>0} && recipients.values.all {it.isNotBlank()} &&
        (!sortie || mode==TacticalShipMode.ANCHORED && units.size==1))}
}

internal object TacticalPostureOperations {
    private data class Ship(val outfit:OutfitStock,val unit:ActiveShipUnit)
    private fun ship(id:Int,stocks:PlanetStocks)=stocks.outfits.values.mapNotNull {o->
        o.activeShips?.values?.singleOrNull {it.id==id}?.let {Ship(o,it)}}.singleOrNull()
    private fun replace(stocks:PlanetStocks,s:Ship,unit:ActiveShipUnit)=stocks.copy(outfits=stocks.outfits+
        (s.outfit.id to s.outfit.copy(activeShips=ActiveShipUnits(s.outfit.activeShips!!.values.map {if(it.id==unit.id)unit else it}))))
    private fun basePoint(base:GalaxyBase):TacticalPoint {
        val angle=Math.toRadians(base.angle.toDouble())
        return TacticalPoint((kotlin.math.cos(angle)*base.radius*1000).toFloat(),(kotlin.math.sin(angle)*base.radius*1000).toFloat())
    }
    private fun target(mode:TacticalShipMode,base:Int,grid:Int,power:Power,point:TacticalPoint,galaxy:Galaxy,stocks:PlanetStocks):Boolean {
        if(!mode.requiresBase)return base==0
        val b=galaxy.bases.singleOrNull {it.id==base && it.grid==grid} ?: return false
        val stock=stocks.bases[base] ?: return false
        if(mode==TacticalShipMode.STATIONED && (stock.camp!=0 || stock.power!=if(power==Power.EMPIRE)2 else 3))return false
        val at=basePoint(b)
        // Replacement policy: local proximity using the base's displayed diameter, minimum one unit.
        return hypot((point.x-at.x).toDouble(),(point.y-at.y).toDouble())<=maxOf(1.0,b.diameter.toDouble())
    }
    private fun pose(s:Ship,grid:Int,now:Long,players:Collection<Navigator>,stocks:PlanetStocks,galaxy:Galaxy)=
        s.unit.tactical?.motion?.takeIf {it.grid==grid}?.pose(now) ?:
            TacticalDeployment.initial(s.unit.id,if(s.outfit.power==2)Power.EMPIRE else Power.ALLIANCE,grid,players,stocks,galaxy)
    private fun pose(p:Navigator,now:Long,players:Collection<Navigator>,stocks:PlanetStocks,galaxy:Galaxy)=
        p.motion?.pose(now) ?: TacticalDeployment.initial(p.unitId,p.power,p.grid,players,stocks,galaxy)
    private fun owned(s:Ship,p:Navigator,galaxy:Galaxy)=s.unit.boats>0 && s.outfit.commanderId==p.characterId &&
        s.outfit.power==(if(p.power==Power.EMPIRE)2 else 3) && s.outfit.positionIn(galaxy)==OutfitPosition(p.grid)
    private fun audience(session:SessionState,grid:Int,players:Map<String,Navigator>)=session.participants.mapNotNull {(a,m)->
        players[a]?.takeIf {m.online && m.connection!=null && m.power==it.power && it.generation==session.generation &&
            it.grid==grid && it.cpPaused && it.baseId==0}
            ?.let {it.characterId to checkNotNull(m.connection)}}.toMap()

    /** Common gate for existing command paths, so an accepted change cannot race another selected-unit order. */
    fun guard(request:NavigationRequest,session:SessionState,navigation:Navigation,stocks:PlanetStocks):NavigationResult? {
        if(request.tacticalPosture!=null || request.tacticalStop!=null)return null
        val ids=request.tacticalMove?.units ?: request.tacticalRetreat ?: request.tacticalShoot?.units ?:
            request.tacticalAttack?.units ?: request.tacticalSupport?.let {listOf(it.unit)} ?: return null
        val p=navigation.snapshot(session.generation)[request.account] ?: return null
        val member=session.participants[request.account] ?: return null
        if(p.characterId!=request.characterId || p.power!=request.power || request.generation!=session.generation ||
            !member.online || member.connection!=request.connection)return null
        val states=ids.mapNotNull {if(it==p.unitId)p.posture else ship(it,stocks)?.unit?.posture}.filter {it.grid==p.grid}
        val rejection=if(states.any {it.order!=null})NavigationRejection.BUSY else
            if(states.any {it.mode==TacticalShipMode.STATIONED || request.tacticalMove!=null && it.mode.requiresBase})NavigationRejection.BASE else return null
        return NavigationResult(null,p,rejection)
    }
    fun accept(request:NavigationRequest,session:SessionState,galaxy:Galaxy,navigation:Navigation,production:PlanetProduction,
        stocks:PlanetStocks,committer:WorldStateCommitter?,scale:Long):NavigationResult {
        val preview=navigation.apply(request.copy(tacticalPosture=null),session,stocks,persist=false)
        var player=preview.player ?: return preview
        fun reject(why:NavigationRejection)=NavigationResult(null,player,why)
        if(committer==null)return reject(NavigationRejection.DISABLED)
        if(!player.cpPaused || player.baseId!=0 || player.mode!=5)return reject(NavigationRejection.TACTICAL)
        if(player.hull?.woundedUntil?.let {it>session.gameMillis/1000}==true)return reject(NavigationRejection.WOUNDED)
        val command=checkNotNull(request.tacticalPosture);val now=session.gameMillis/1000
        val duration=if(command.sortie)5L+20L*command.units.size else 10L
        if(now>0xffffffffL-duration*scale)return reject(NavigationRejection.CLOCK)
        val ready=now+duration*scale
        val players=navigation.snapshot(session.generation)
        val centre=pose(player,now,players.values,stocks,galaxy).position
        val radius=checkNotNull(player.commandRange).radius(now)
        var next=stocks
        // The manual randomizes ship types. All entries here are ships; a seeded shuffle remains stable after saving.
        val selected=if(command.sortie)command.units.shuffled(kotlin.random.Random(now xor session.generation)) else command.units
        for((index,id) in selected.withIndex()) {
            val source=if(id==player.unitId)null else ship(id,next) ?: return reject(NavigationRejection.UNIT)
            // A docked personal flagship uses strategy departure; this path serves commissioned ship units.
            if(command.sortie && source==null)return reject(NavigationRejection.UNIT)
            if(source!=null && !owned(source,player,galaxy))return reject(NavigationRejection.CARD)
            val current=(if(source==null)player.posture else source.unit.posture)?.takeIf {it.grid==player.grid}
                ?: TacticalPosture(player.grid)
            if(current.order!=null || (current.mode==TacticalShipMode.STATIONED)!=command.sortie ||
                if(source==null)player.retreat!=null else source.unit.support!=null || source.unit.tactical?.retreat!=null)
                return reject(NavigationRejection.BUSY)
            val targetBase=if(command.sortie)current.base else command.base
            if(current.mode==command.mode && current.base==targetBase)return reject(NavigationRejection.SAME_GRID)
            val at=if(source==null)pose(player,now,players.values,stocks,galaxy) else pose(source,player.grid,now,players.values,stocks,galaxy)
            if(source!=null && !command.sortie && hypot((at.position.x-centre.x).toDouble(),(at.position.y-centre.y).toDouble())>radius)
                return reject(NavigationRejection.RANGE)
            if(!target(command.mode,targetBase,player.grid,player.power,at.position,galaxy,stocks))return reject(NavigationRejection.BASE)
            val due=if(command.sortie)now+(5L+20L*(index+1))*scale else ready
            val changed=current.copy(order=TacticalPostureOrder(player.characterId,command.mode,targetBase,now,due))
            if(source==null)player=player.copy(posture=changed,attack=null,fire=player.fire?.copy(pending=null))
            else next=replace(next,source,source.unit.copy(posture=changed,attack=null,shot=null))
        }
        player=player.copy(commandRange=checkNotNull(player.commandRange).reset(now))
        val prepared=production.prepareReplacement(stocks,next)
        committer.saveWorld(players+(request.account to player),prepared)
        navigation.publishCommitted(players,players+(request.account to player),session.generation)
        production.publishCommitted(stocks,prepared)
        return NavigationResult(player,preview.previous,postureReadyAt=ready)
    }
    fun advance(session:SessionState,galaxy:Galaxy,players:MutableMap<String,Navigator>,original:PlanetStocks,
        results:MutableList<TacticalPostureResult>):PlanetStocks {
        if(players.values.none {it.posture?.order!=null} && original.outfits.values.none {o->o.activeShips?.values?.any {it.posture?.order!=null}==true})return original
        var stocks=original;val now=session.gameMillis/1000
        val commanders=players.toMap()
        val recipients=players.values.map {it.grid}.distinct().associateWith {audience(session,it,players)}
        fun event(id:Int,grid:Int,order:TacticalPostureOrder,at:TacticalPose,cancelled:Boolean)=
            TacticalPostureResult(session.generation,grid,now,order.character,order.mode,order.base,mapOf(id to at),
                recipients[grid].orEmpty(),cancelled=cancelled)
        for((account,p) in players.toMap()) {
            val state=p.posture ?: continue;val order=state.order ?: continue
            val context=session.ended==null && p.cpPaused && p.baseId==0 && p.grid==state.grid && order.character==p.characterId
            if(order.readyAt>now && context)continue
            val at=pose(p,minOf(now,order.readyAt),players.values,stocks,galaxy)
            val valid=context &&
                target(order.mode,order.base,p.grid,p.power,at.position,galaxy,stocks)
            val stopped=TacticalMotion(p.grid,now,at,0f,at.direction,listOf(at.position))
            val changed=if(valid)TacticalPosture(p.grid,order.mode,order.base) else state.copy(order=null)
            var next=p.copy(posture=changed)
            if(valid)next=next.copy(motion=stopped,retreat=null,attack=null,fire=next.fire?.copy(pending=null))
            if(valid && order.mode==TacticalShipMode.STATIONED)next=next.copy(baseId=order.base,mode=4,cpPaused=false,
                cpRecoveryAt=now,motion=null,charge=null,commandRange=null,fire=next.fire?.copy(pending=null),spotId=0,spotOwner=0)
            players[account]=next;results+=event(p.unitId,state.grid,order,at,!valid)
        }
        val due=stocks.outfits.values.flatMap {it.activeShips?.values.orEmpty()}.filter {it.posture?.order!=null}.map {it.id}
        for(id in due) {
            val s=ship(id,stocks) ?: continue;val state=checkNotNull(s.unit.posture);val order=checkNotNull(state.order)
            val p=commanders.values.singleOrNull {it.characterId==order.character}
            val context=session.ended==null && p!=null && p.cpPaused && p.baseId==0 && p.grid==state.grid && owned(s,p,galaxy)
            if(order.readyAt>now && context)continue
            val at=pose(s,state.grid,minOf(now,order.readyAt),players.values,stocks,galaxy)
            val valid=context && p!=null &&
                target(order.mode,order.base,state.grid,p.power,at.position,galaxy,stocks)
            val changed=if(valid)TacticalPosture(state.grid,order.mode,order.base) else state.copy(order=null)
            var unit=s.unit.copy(posture=changed)
            if(valid) {
                val motion=TacticalMotion(state.grid,now,at,0f,at.direction,listOf(at.position))
                unit=unit.copy(tactical=s.unit.tactical?.copy(motion=motion,nextDecisionAt=now,target=0,retreat=null) ?: NpcTacticalState(motion,now,now),
                    motionCommander=order.character,attack=null,shot=null,support=null)
            }
            stocks=replace(stocks,s,unit);results+=event(id,state.grid,order,at,!valid).copy(
                sortie=state.mode==TacticalShipMode.STATIONED && order.mode==TacticalShipMode.ANCHORED)
        }
        return stocks
    }
}
