package org.logh7.engine

import kotlin.math.atan2
import kotlin.math.hypot

/** Autonomous uncommanded ship units, not offline delegation or named original characters. Author: Peppone Choi. */
data class NpcTacticalPolicy(val enabled:Boolean=true,val sight:Float=500f,val engagementDistance:Float=2f,val strategic:Boolean=false,
    val chooseFireTarget:((NpcFireDecision)->Int?)?=null,
    val behavior:(generation:Long,outfit:Int)->NpcCombatBehavior={_,_->NpcCombatBehavior.STANDARD}) {
    init { require(sight in 8f..1000f && engagementDistance in 1f..3f) }
}
/** An optional advisor can only choose from targets the actor can hit right now. */
data class NpcFireDecision(val generation:Long,val unit:Int,val grid:Int,val weapon:TacticalWeapon,
    val targets:List<Target>) {
    data class Target(val id:Int,val effectiveness:Int)
}
data class NpcTacticalState(val motion:TacticalMotion,val nextDecisionAt:Long,val nextFireAt:Long,
    val sequence:Long=0,val target:Int=0,val retreat:TacticalRetreat?=null) {
    init { require(nextDecisionAt in motion.acceptedAt..0xffffffffL && nextFireAt in 0..0xffffffffL &&
        sequence>=0 && (target==0 || validCombatTarget(target)) && (retreat==null || retreat.destination!=motion.grid)) }
}
/** Committed autonomous movement/exit and audience. Character zero is server-controlled wire metadata. */
data class TacticalMovement(val generation:Long,val unit:Int,val grid:Int,val seconds:Long,
    val motion:TacticalMotion?,val recipients:Map<Int,String>,val destination:Int?=null,
    val character:Int=0,val stopped:Boolean=false,val waypoint:Int?=null,
    val formation:Map<Int,TacticalMotion> = emptyMap(),val turned:Boolean=false) {
    init { require(character>=0 && (waypoint==null || waypoint in -1..31) && (!stopped || character>0 && motion?.velocity==0f) && generation>0 &&
        (unit in 1..999999 || (stopped || waypoint!=null || formation.isNotEmpty()) && unit>=1000000) && grid in 0..4999 && seconds in 0..0xffffffffL &&
        (formation.isEmpty() || character>0 && formation.size in 1..32 && unit in formation && formation.values.all {it.grid==grid && it.rotation!=null}) &&
        (!turned || waypoint==-1 && motion!=null && formation.isEmpty()) &&
        recipients.keys.all {it>0} && recipients.values.all {it.isNotBlank()} &&
        ((motion!=null && destination==null && motion.grid==grid) || (motion==null && destination in 0..4999))) }
}

/** Actor only. Decisions and shots are committed with the same inventory/navigation after-images.
 * Sight, movement normalization, cadence, casualty scaling and withdrawal threshold are designed policy.
 */
internal object NpcTactics {
    private data class Contact(val id:Int,val pose:TacticalPose)
    private fun audience(session:SessionState,grid:Int,players:Map<String,Navigator>)=players.mapNotNull { (a,p)->
        val member=session.participants[a]
        if(p.grid!=grid || p.baseId!=0 || !p.cpPaused || member?.online!=true || member.country!=p.country)null
        else member.connection?.let {p.characterId to it}
    }.toMap()
    private fun initial(unit:ActiveShipUnit,outfit:OutfitStock,grid:Int,now:Long,players:Map<String,Navigator>,
        stocks:PlanetStocks,galaxy:Galaxy):TacticalPose=unit.tactical?.takeIf {it.motion.grid==grid}?.motion?.pose(now,unit.posture,galaxy)
        ?: TacticalDeployment.initial(unit.id,grid,players.values,stocks,galaxy)

    private fun contacts(outfit:OutfitStock,grid:Int,now:Long,players:Map<String,Navigator>,stocks:PlanetStocks,
        galaxy:Galaxy):List<Contact> = players.values.filter {it.grid==grid && it.baseId==0 && it.cpPaused &&
        TacticalAuthority.camp(it,stocks)!=TacticalAuthority.camp(outfit,stocks) && it.hull!=null}.map {p -> Contact(p.unitId,p.motion?.pose(now,p.posture,galaxy) ?:
            TacticalDeployment.initial(p.unitId,grid,players.values,stocks,galaxy))} +
        stocks.outfits.values.filter {TacticalAuthority.camp(it,stocks)!=TacticalAuthority.camp(outfit,stocks) && it.positionIn(galaxy)==OutfitPosition(grid)}.flatMap {o ->
            o.activeShips?.values.orEmpty().filter {it.boats>0 && it.posture?.takeIf {s->s.grid==grid}?.mode!=TacticalShipMode.STATIONED}.map {u -> Contact(u.id,initial(u,o,grid,now,players,stocks,galaxy))}}

    private fun replace(stocks:PlanetStocks,outfit:OutfitStock,unit:ActiveShipUnit):PlanetStocks {
        val ships=ActiveShipUnits(outfit.activeShips!!.values.map {if(it.id==unit.id)unit else it})
        return stocks.copy(outfits=stocks.outfits+(outfit.id to outfit.copy(activeShips=ships)))
    }
    private fun direction(from:TacticalPoint,to:TacticalPoint)=atan2(to.x-from.x,to.y-from.y)
    private fun stationary(grid:Int,now:Long,pose:TacticalPose)=TacticalMotion(grid,now,pose,0f,pose.direction,listOf(pose.position))
    private fun exit(galaxy:Galaxy,outfit:OutfitStock,grid:Int,players:Map<String,Navigator>,stocks:PlanetStocks):Int? {
        val forces=GridForces.collect(galaxy,stocks.generation,players.values,stocks)
        val home=galaxy.bases.single {it.id==outfit.baseId}.grid
        val count=GridForces.mobileUnits(outfit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
        return (-1..1).flatMap {dy->(-1..1).mapNotNull {dx->
            val x=grid%100+dx;val y=grid/100+dy
            if(dx==0 && dy==0 || x !in 0..99 || y !in 0..49)null else y*100+x
        }}.filter {g -> GridEntryRules.evaluate(GridForces.state(galaxy,g,forces),GridEntry(Camp(outfit.country,false),count,false))==GridEntryVerdict.ALLOWED}
            .sortedWith(compareBy<Int> {g -> if(forces[g].orEmpty().any {it.camp!=Camp(outfit.country,false)})1 else 0}
                .thenBy {g -> kotlin.math.abs(g%100-home%100)+kotlin.math.abs(g/100-home/100)}.thenBy {it}).firstOrNull()
    }

    fun advance(session:SessionState,galaxy:Galaxy,players:MutableMap<String,Navigator>,original:PlanetStocks,
        policy:NpcTacticalPolicy,scale:Long,impacts:MutableList<TacticalImpact>,movements:MutableList<TacticalMovement>):PlanetStocks {
        var stocks=original
        val now=session.gameMillis/1000
        if(now>0xffffffffL-155*scale)return stocks
        var forceCache:Map<Int,List<GridForces.Force>>?=null
        fun forces()=forceCache ?: GridForces.collect(galaxy,stocks.generation,players.values,stocks).also {forceCache=it}
        for(outfitId in original.outfits.keys.sorted()) {
            var outfit=stocks.outfits.getValue(outfitId)
            val position=outfit.positionIn(galaxy)
            // A player taking command must never compete with autonomous orders.
            if(position.baseId!=0 || !TacticalAuthority.uncommanded(outfit,stocks)) {
                for(unit in outfit.activeShips?.values.orEmpty()) {
                    val state=unit.tactical ?: continue
                    if(position.baseId==0 && state.motion.grid==position.grid && unit.motionCommander!=0 &&
                        unit.motionCommander==TacticalAuthority.commander(outfit,unit,stocks))continue
                    val next=if(position.baseId!=0 || state.motion.grid!=position.grid)unit.copy(tactical=null,motionCommander=0,attack=null,shot=null) else {
                        if(state.motion.velocity==0f && state.target==0 && state.retreat==null && unit.motionCommander==0)continue
                        val motion=stationary(position.grid,now,state.motion.pose(now,unit.posture,galaxy))
                        movements+=TacticalMovement(session.generation,unit.id,position.grid,now,motion,audience(session,position.grid,players))
                        unit.copy(tactical=state.copy(motion=motion,nextDecisionAt=now+scale,target=0,retreat=null),motionCommander=0)
                    }
                    stocks=replace(stocks,stocks.outfits.getValue(outfitId),next)
                }
                continue
            }
            if(outfit.activeShips?.values?.any {it.motionCommander!=0}==true) {
                outfit=outfit.copy(activeShips=ActiveShipUnits(outfit.activeShips.values.map {it.copy(motionCommander=0)}))
                stocks=stocks.copy(outfits=stocks.outfits+(outfitId to outfit))
            }
            val live=outfit.activeShips?.values.orEmpty().filter {it.boats>0 && it.posture?.let {s->s.grid==position.grid && (s.mode.requiresBase || s.order!=null)}!=true}
            if(live.isEmpty())continue
            val behavior=policy.behavior(session.generation,outfitId)
            val enemies=contacts(outfit,position.grid,now,players,stocks,galaxy)
            val detectsEnemies=enemies.isNotEmpty() && live.any { unit ->
                val observer=initial(unit,outfit,position.grid,now,players,stocks,galaxy).position
                enemies.any { !TacticalTerrain.sensorBlocked(galaxy,position.grid,observer,it.pose.position) }
            }
            val returnJumps=if(policy.strategic && detectsEnemies)NpcStrategy.returnDistance(galaxy,forces(),outfit,stocks) else null
            val cargo=OutfitSupplies.distribution(outfit)
            val retreat=live.mapNotNull {it.tactical?.takeIf {s->s.motion.grid==position.grid}?.retreat}.minByOrNull {it.acceptedAt}
            val lossThreshold=if(behavior==NpcCombatBehavior.CAUTIOUS)2 else 4
            val critical=live.sumOf {it.boats.toLong()}*lossThreshold <= outfit.activeShips!!.values.sumOf {
                UnitSpecifications.ships.single {s->s.kind==it.stack.kind}.number.toLong()} ||
                live.all {stocks.tacticalCommands.confused(it.id)} ||
                (returnJumps!=null && returnJumps>0 && live.any {cargo.getValue(it.id)<it.boats}) ||
                live.all {u->TacticalWeapon.entries.all {TacticalArmaments.mount(UnitSpecifications.ships.single {it.kind==u.stack.kind},it).power==0}}
            val withdrawal=retreat ?: if(critical && detectsEnemies) exit(galaxy,outfit,position.grid,players,stocks)?.let {
                TacticalRetreat(it,now,5*scale,150*scale)} else null
            for(id in live.map {it.id}) {
                outfit=stocks.outfits.getValue(outfitId)
                var unit=outfit.activeShips!!.values.single {it.id==id}
                if(unit.boats==0)continue
                val before=unit.tactical?.takeIf {it.motion.grid==position.grid}
                val origin=initial(unit,outfit,position.grid,now,players,stocks,galaxy)
                val spec=UnitSpecifications.ships.single {it.kind==unit.stack.kind}
                var state=before
                if(state==null || now>=state.nextDecisionAt || state.retreat!=withdrawal) {
                    val target=enemies.map {it to hypot(it.pose.position.x.toDouble()-origin.position.x,it.pose.position.y.toDouble()-origin.position.y)}
                        .filter {it.second<=policy.sight && !TacticalTerrain.sensorBlocked(galaxy,position.grid,origin.position,it.first.pose.position)}
                        .minWithOrNull(compareBy<Pair<Contact,Double>> {it.second}.thenBy {it.first.id})
                    val goal=if(withdrawal!=null) {
                        val dx=(withdrawal.destination%100-position.grid%100).toFloat();val dy=(withdrawal.destination/100-position.grid/100).toFloat()
                        val length=hypot(dx,dy);TacticalPoint(dx/length*280,dy/length*280)
                    } else target?.takeIf {it.second>policy.engagementDistance}?.let {(c,d)->
                        val ratio=(d-policy.engagementDistance)/d
                        TacticalPoint((origin.position.x+(c.pose.position.x-origin.position.x)*ratio).toFloat(),
                            (origin.position.y+(c.pose.position.y-origin.position.y)*ratio).toFloat())}
                    val facing=if(withdrawal!=null)direction(origin.position,checkNotNull(goal)) else target?.let {direction(origin.position,it.first.pose.position)} ?: origin.direction
                    val pose=origin.copy(direction=facing)
                    val motion=if(goal==null)stationary(position.grid,now,pose) else TacticalMotion(position.grid,now,origin,
                        (spec.number("最高速度（km/1G秒）:最高速度（km/1G秒）")/20000f).coerceIn(0.1f,1f),facing,listOf(goal),
                        turnRate=TacticalMotion.NATIVE_TURN_RATE)
                    // No idle write loop: retain an already stationary guard or firing pose.
                    if(before==null && target==null && withdrawal==null)continue
                    val changed=before==null || before.target!=(target?.first?.id ?: 0) || before.retreat!=withdrawal ||
                        before.motion.velocity!=motion.velocity || before.motion.waypoints!=motion.waypoints ||
                        kotlin.math.abs(before.motion.direction-facing)>0.01f
                    if(changed) {
                        state=NpcTacticalState(motion,now+scale,before?.nextFireAt ?: now+5*scale,before?.sequence ?: 0,
                            target?.first?.id ?: 0,withdrawal)
                        unit=unit.copy(tactical=state);stocks=replace(stocks,outfit,unit)
                        movements+=TacticalMovement(session.generation,id,position.grid,now,motion,audience(session,position.grid,players))
                    }
                }
                var firing=unit.tactical ?: continue
                if(stocks.tacticalCommands.confused(id) || firing.retreat!=null || now<firing.nextFireAt || firing.sequence>Long.MAX_VALUE-3)continue
                for(weapon in TacticalWeapon.entries) {
                    outfit=stocks.outfits.getValue(outfitId);unit=outfit.activeShips!!.values.single {it.id==id}
                    if(unit.boats<=unit.damaged)break
                    val pose=initial(unit,outfit,position.grid,now,players,stocks,galaxy)
                    val mount=GuardCombat.mount(TacticalArmaments.mount(spec,weapon),weapon,outfit,unit.id)
                    val cost=TacticalFleetShoot.cost(unit,mount,weapon)
                    if(mount.power==0 || !TacticalReload.canFire(unit,weapon,now,scale) || OutfitSupplies.distribution(outfit).getValue(id)<cost)continue
                    if(cost>0 && returnJumps!=null &&
                        OutfitSupplies.distribution(outfit).getValue(id)-cost<returnJumps*unit.boats)continue
                    val precision=behavior==NpcCombatBehavior.PRECISION
                    val nearby=contacts(outfit,position.grid,now,players,stocks,galaxy)
                    val inArc=(if(precision)nearby else nearby.sortedBy {it.id}).asSequence()
                        .filterNot { TacticalTerrain.sensorBlocked(galaxy,position.grid,pose.position,it.pose.position) }
                        .mapNotNull { contact ->
                            val effect=TacticalArmaments.effectiveness(mount,pose,contact.pose.position)
                            if(effect==0)null else contact to effect
                        }
                    val ranked=if(precision)inArc.sortedWith(compareByDescending<Pair<Contact,Int>> {it.second}.thenBy {it.first.id}) else inArc
                    val candidates=ranked.filterNot { (contact,_) -> TacticalLineOfFire.blocked(id,position.grid,
                        TacticalAuthority.camp(outfit,stocks),pose.position,contact.pose.position,now,players.values,stocks,galaxy) }
                        .map { (contact,effect) -> NpcFireDecision.Target(contact.id,effect) }
                        .take(if(policy.chooseFireTarget==null)1 else 4).toList()
                    val first=candidates.firstOrNull() ?: continue
                    val eligible=if(precision)candidates.takeWhile {it.effectiveness==first.effectiveness} else candidates
                    val advised=policy.chooseFireTarget?.takeIf {eligible.size>1}?.invoke(
                        NpcFireDecision(session.generation,id,position.grid,weapon,eligible))
                    val victim=eligible.firstOrNull {it.id==advised} ?: first
                    val resolved=TacticalCombat.strike(session,galaxy,players,stocks,id,position.grid,firing.sequence+1,now,victim.id,
                        weapon,mount.copy(power=((mount.power.toLong()*(unit.boats-unit.damaged)+spec.number-1)/spec.number).toInt()),victim.effectiveness,pose,
                        checkNotNull(TacticalCombat.target(victim.id,position.grid,TacticalAuthority.camp(outfit,stocks),stocks,galaxy,players)))
                    stocks=resolved.first;impacts+=resolved.second.copy(npcOutfit=outfitId);forceCache=null
                    outfit=stocks.outfits.getValue(outfitId)
                    firing=firing.copy(nextFireAt=now+5*scale,sequence=firing.sequence+1)
                    // Debit this firing unit, not whichever unit bulk ID-order storage fills last.
                    // Beam-only fire leaves a legacy allocation untouched until a cargo change occurs.
                    if(cost>0)outfit=OutfitSupplies.change(outfit,id,-cost)
                    unit=outfit.activeShips!!.values.single {it.id==id}.copy(tactical=firing,
                        reload=(unit.reload ?: TacticalReload()).fired(weapon,now,scale))
                    stocks=replace(stocks,outfit,unit)
                }
            }
            outfit=stocks.outfits.getValue(outfitId)
            val current=outfit.activeShips!!.values.filter {it.boats>0}
            if(withdrawal!=null && now>=withdrawal.completesAt && current.all {u->
                    u.tactical?.let {it.retreat==withdrawal && RetreatRules.outside(it.motion.pose(now,u.posture,galaxy))}==true}) {
                val forces=GridForces.collect(galaxy,stocks.generation,players.values,stocks)
                val count=GridForces.mobileUnits(outfit).toInt().coerceAtLeast(1)
                if(GridEntryRules.evaluate(GridForces.state(galaxy,withdrawal.destination,forces),GridEntry(Camp(outfit.country,false),count,false))==GridEntryVerdict.ALLOWED) {
                    stocks=stocks.copy(outfits=stocks.outfits+(outfit.id to outfit.copy(position=OutfitPosition(withdrawal.destination),strategy=null,
                        activeShips=ActiveShipUnits(outfit.activeShips.values.map {it.copy(tactical=null,motionCommander=0,attack=null,shot=null)}))))
                    forceCache=null
                    current.forEach {u->movements+=TacticalMovement(session.generation,u.id,position.grid,now,null,
                        audience(session,position.grid,players),withdrawal.destination)}
                }
            }
        }
        return stocks
    }
}
