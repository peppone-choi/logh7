package org.logh7.engine

import kotlin.math.atan2
import kotlin.math.hypot

/** Autonomous uncommanded ship units, not offline delegation or named original characters. Author: 최병호. */
data class NpcTacticalPolicy(val enabled:Boolean=true,val sight:Float=500f,val engagementDistance:Float=2f) {
    init { require(sight in 8f..1000f && engagementDistance in 1f..3f) }
}
data class NpcTacticalState(val motion:TacticalMotion,val nextDecisionAt:Long,val nextFireAt:Long,
    val sequence:Long=0,val target:Int=0,val retreat:TacticalRetreat?=null) {
    init { require(nextDecisionAt in motion.acceptedAt..0xffffffffL && nextFireAt in 0..0xffffffffL &&
        sequence>=0 && (target==0 || validCombatTarget(target)) && (retreat==null || retreat.destination!=motion.grid)) }
}
/** Committed autonomous movement/exit and audience. Character zero is server-controlled wire metadata. */
data class TacticalMovement(val generation:Long,val unit:Int,val grid:Int,val seconds:Long,
    val motion:TacticalMotion?,val recipients:Map<Int,String>,val destination:Int?=null,
    val character:Int=0,val stopped:Boolean=false) {
    init { require(character>=0 && (!stopped || character>0 && motion?.velocity==0f) && generation>0 && unit in 1..999999 && grid in 0..4999 && seconds in 0..0xffffffffL &&
        recipients.keys.all {it>0} && recipients.values.all {it.isNotBlank()} &&
        ((motion!=null && destination==null && motion.grid==grid) || (motion==null && destination in 0..4999))) }
}

/** Actor only. Decisions and shots are committed with the same inventory/navigation after-images.
 * Sight, movement normalization, cadence, casualty scaling and withdrawal threshold are designed policy.
 */
internal object NpcTactics {
    private data class Contact(val id:Int,val pose:TacticalPose)
    private fun power(outfit:OutfitStock)=if(outfit.power==2)Power.EMPIRE else Power.ALLIANCE
    private fun audience(session:SessionState,grid:Int,players:Map<String,Navigator>)=players.mapNotNull { (a,p)->
        val member=session.participants[a]
        if(p.grid!=grid || p.baseId!=0 || !p.cpPaused || member?.online!=true || member.power!=p.power)null
        else member.connection?.let {p.characterId to it}
    }.toMap()
    private fun initial(unit:ActiveShipUnit,outfit:OutfitStock,grid:Int,now:Long,players:Map<String,Navigator>,
        stocks:PlanetStocks,galaxy:Galaxy):TacticalPose=unit.tactical?.takeIf {it.motion.grid==grid}?.motion?.pose(now)
        ?: TacticalDeployment.initial(unit.id,power(outfit),grid,players.values,stocks,galaxy)

    private fun contacts(outfit:OutfitStock,grid:Int,now:Long,players:Map<String,Navigator>,stocks:PlanetStocks,
        galaxy:Galaxy):List<Contact> = players.values.filter {it.grid==grid && it.baseId==0 && it.cpPaused &&
        it.power!=power(outfit) && it.hull!=null}.map {p -> Contact(p.unitId,p.motion?.pose(now) ?:
            TacticalDeployment.initial(p.unitId,p.power,grid,players.values,stocks,galaxy))} +
        stocks.outfits.values.filter {it.power!=outfit.power && it.positionIn(galaxy)==OutfitPosition(grid)}.flatMap {o ->
            o.activeShips?.values.orEmpty().filter {it.boats>0}.map {u -> Contact(u.id,initial(u,o,grid,now,players,stocks,galaxy))}}

    private fun replace(stocks:PlanetStocks,outfit:OutfitStock,unit:ActiveShipUnit):PlanetStocks {
        val ships=ActiveShipUnits(outfit.activeShips!!.values.map {if(it.id==unit.id)unit else it})
        return stocks.copy(outfits=stocks.outfits+(outfit.id to outfit.copy(activeShips=ships)))
    }
    private fun direction(from:TacticalPoint,to:TacticalPoint)=atan2(to.x-from.x,to.y-from.y)
    private fun stationary(grid:Int,now:Long,pose:TacticalPose)=TacticalMotion(grid,now,pose,0f,pose.direction,listOf(pose.position))
    private fun exit(galaxy:Galaxy,outfit:OutfitStock,grid:Int,players:Map<String,Navigator>,stocks:PlanetStocks):Int? {
        val forces=GridForces.collect(galaxy,stocks.generation,players.values,stocks)
        val home=galaxy.bases.single {it.id==outfit.baseId}.grid
        val count=GridForces.units(outfit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
        return (-1..1).flatMap {dy->(-1..1).mapNotNull {dx->
            val x=grid%100+dx;val y=grid/100+dy
            if(dx==0 && dy==0 || x !in 0..99 || y !in 0..49)null else y*100+x
        }}.filter {g -> GridEntryRules.evaluate(GridForces.state(galaxy,g,forces),GridEntry(Camp(power(outfit),false),count,false))==GridEntryVerdict.ALLOWED}
            .sortedWith(compareBy<Int> {g -> if(forces[g].orEmpty().any {it.camp.power!=power(outfit)})1 else 0}
                .thenBy {g -> kotlin.math.abs(g%100-home%100)+kotlin.math.abs(g/100-home/100)}.thenBy {it}).firstOrNull()
    }

    fun advance(session:SessionState,galaxy:Galaxy,players:MutableMap<String,Navigator>,original:PlanetStocks,
        policy:NpcTacticalPolicy,scale:Long,impacts:MutableList<TacticalImpact>,movements:MutableList<TacticalMovement>):PlanetStocks {
        var stocks=original
        val now=session.gameMillis/1000
        if(now>0xffffffffL-155*scale)return stocks
        for(outfitId in original.outfits.keys.sorted()) {
            var outfit=stocks.outfits.getValue(outfitId)
            val position=outfit.positionIn(galaxy)
            // A player taking command must never compete with autonomous orders.
            if(position.baseId!=0 || outfit.members().isNotEmpty()) {
                for(unit in outfit.activeShips?.values.orEmpty()) {
                    val state=unit.tactical ?: continue
                    if(position.baseId==0 && state.motion.grid==position.grid && unit.motionCommander!=0 &&
                        unit.motionCommander==outfit.commanderId)continue
                    val next=if(position.baseId!=0 || state.motion.grid!=position.grid)unit.copy(tactical=null,motionCommander=0,attack=null) else {
                        if(state.motion.velocity==0f && state.target==0 && state.retreat==null && unit.motionCommander==0)continue
                        val motion=stationary(position.grid,now,state.motion.pose(now))
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
            val live=outfit.activeShips?.values.orEmpty().filter {it.boats>0}
            if(live.isEmpty())continue
            val enemies=contacts(outfit,position.grid,now,players,stocks,galaxy)
            val retreat=live.mapNotNull {it.tactical?.takeIf {s->s.motion.grid==position.grid}?.retreat}.minByOrNull {it.acceptedAt}
            val critical=live.sumOf {it.boats.toLong()}*4 <= outfit.activeShips!!.values.sumOf {
                UnitSpecifications.ships.single {s->s.kind==it.stack.kind}.number.toLong()} ||
                live.all {u->TacticalWeapon.entries.all {TacticalArmaments.mount(UnitSpecifications.ships.single {it.kind==u.stack.kind},it).power==0}}
            val withdrawal=retreat ?: if(critical && enemies.isNotEmpty()) exit(galaxy,outfit,position.grid,players,stocks)?.let {
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
                        .filter {it.second<=policy.sight}.minWithOrNull(compareBy<Pair<Contact,Double>> {it.second}.thenBy {it.first.id})
                    val goal=if(withdrawal!=null) {
                        val dx=(withdrawal.destination%100-position.grid%100).toFloat();val dy=(withdrawal.destination/100-position.grid/100).toFloat()
                        val length=hypot(dx,dy);TacticalPoint(dx/length*280,dy/length*280)
                    } else target?.takeIf {it.second>policy.engagementDistance}?.let {(c,d)->
                        val ratio=(d-policy.engagementDistance)/d
                        TacticalPoint((origin.position.x+(c.pose.position.x-origin.position.x)*ratio).toFloat(),
                            (origin.position.y+(c.pose.position.y-origin.position.y)*ratio).toFloat())}
                    val facing=if(withdrawal!=null)direction(origin.position,checkNotNull(goal)) else target?.let {direction(origin.position,it.first.pose.position)} ?: origin.direction
                    val pose=origin.copy(direction=facing)
                    val motion=if(goal==null)stationary(position.grid,now,pose) else TacticalMotion(position.grid,now,pose,
                        (spec.number("最高速度（km/1G秒）:最高速度（km/1G秒）")/20000f).coerceIn(0.1f,1f),facing,listOf(goal))
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
                if(firing.retreat!=null || now<firing.nextFireAt || firing.sequence>Long.MAX_VALUE-3)continue
                for(weapon in TacticalWeapon.entries) {
                    outfit=stocks.outfits.getValue(outfitId);unit=outfit.activeShips!!.values.single {it.id==id}
                    if(unit.boats==0)break
                    val pose=initial(unit,outfit,position.grid,now,players,stocks,galaxy)
                    val mount=TacticalArmaments.mount(spec,weapon)
                    val cost=if(weapon==TacticalWeapon.BEAM)0 else maxOf(1,mount.consumption)*unit.boats
                    if(mount.power==0 || OutfitSupplies.distribution(outfit).getValue(id)<cost)continue
                    val victim=contacts(outfit,position.grid,now,players,stocks,galaxy).sortedBy {it.id}.firstOrNull {
                        TacticalArmaments.effectiveness(mount,pose,it.pose.position)>0} ?: continue
                    val effect=TacticalArmaments.effectiveness(mount,pose,victim.pose.position)
                    val resolved=TacticalCombat.strike(session,galaxy,players,stocks,id,position.grid,firing.sequence+1,now,victim.id,
                        weapon,mount.copy(power=((mount.power.toLong()*unit.boats+spec.number-1)/spec.number).toInt()),effect,pose,
                        checkNotNull(TacticalCombat.target(victim.id,position.grid,power(outfit),stocks,galaxy,players)))
                    stocks=resolved.first;impacts+=resolved.second
                    outfit=stocks.outfits.getValue(outfitId)
                    firing=firing.copy(nextFireAt=now+5*scale,sequence=firing.sequence+1)
                    // Debit this firing unit, not whichever unit bulk ID-order storage fills last.
                    // Beam-only fire leaves a legacy allocation untouched until a cargo change occurs.
                    if(cost>0)outfit=OutfitSupplies.change(outfit,id,-cost)
                    unit=outfit.activeShips!!.values.single {it.id==id}.copy(tactical=firing)
                    stocks=replace(stocks,outfit,unit)
                }
            }
            outfit=stocks.outfits.getValue(outfitId)
            val current=outfit.activeShips!!.values.filter {it.boats>0}
            if(withdrawal!=null && now>=withdrawal.completesAt && current.all {u->
                    u.tactical?.let {it.retreat==withdrawal && RetreatRules.outside(it.motion.pose(now))}==true}) {
                val forces=GridForces.collect(galaxy,stocks.generation,players.values,stocks)
                val count=GridForces.units(outfit).toInt().coerceAtLeast(1)
                if(GridEntryRules.evaluate(GridForces.state(galaxy,withdrawal.destination,forces),GridEntry(Camp(power(outfit),false),count,false))==GridEntryVerdict.ALLOWED) {
                    stocks=stocks.copy(outfits=stocks.outfits+(outfit.id to outfit.copy(position=OutfitPosition(withdrawal.destination),
                        activeShips=ActiveShipUnits(outfit.activeShips.values.map {it.copy(tactical=null,motionCommander=0,attack=null)}))))
                    current.forEach {u->movements+=TacticalMovement(session.generation,u.id,position.grid,now,null,
                        audience(session,position.grid,players),withdrawal.destination)}
                }
            }
        }
        return stocks
    }
}
