package org.logh7.engine

import kotlin.math.*

/** Manual: offline AI accepts tactical missions (update04). Steering, allocation and five-real-second
 * decisions below are replacement policy. Uses the normal motion, reload and damage models. Author: 최병호. */
internal object TacticalOfflineAi {
    fun eligible(session:SessionState,account:String,p:Navigator,stocks:PlanetStocks)=session.ended==null &&
        session.participants[account]?.online==false && p.generation==session.generation && p.cpPaused &&
        p.baseId==0 && p.mode==5 && p.hull!=null && p.hull.woundedUntil<=session.gameMillis/1000 &&
        stocks.tacticalCommands.tacticalAi(p.characterId)

    fun advance(session:SessionState,g:Galaxy,players:MutableMap<String,Navigator>,initial:PlanetStocks,scale:Long,
        events:MutableList<TacticalCommandResult>):PlanetStocks {
        var stocks=initial
        val now=session.gameMillis/1000
        if(now>0xffffffffL-maxOf(155*scale,TacticalMotion.WAIT_SECONDS))return stocks
        for(account in players.keys.sorted()) {
            val p=players.getValue(account)
            if(!eligible(session,account,p,stocks) || now<(stocks.tacticalCommands.aiNextAt[p.characterId] ?: 0))continue
            stocks=stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(aiNextAt=stocks.tacticalCommands.aiNextAt+(p.characterId to now+5*scale)))
            if(p.retreat!=null || p.fire?.pending!=null || p.posture?.let {it.mode.requiresBase || it.order!=null}==true ||
                stocks.tacticalCommands.orders.any {p.unitId in it.command.units})continue
            val pose=p.motion?.pose(now,p.posture,g,stocks) ?: TacticalDeployment.initial(p.unitId,p.power,p.grid,players.values,stocks,g)
            val mission=stocks.tacticalCommands.missions[p.characterId]?.takeUnless {it.completed}
            val kind=if(stocks.tacticalCommands.confused(p.unitId))5 else mission?.mission ?: 0
            val ids=(players.values.map {it.unitId}+stocks.outfits.values.flatMap {it.activeShips?.values.orEmpty().map {u->u.id}}).distinct()
            val enemies=ids.mapNotNull {id->TacticalCombat.target(id,p.grid,p.power,stocks,g,players)?.let {
                id to TacticalFleetAttack.pose(it,now,players,stocks,g)}}
            val enemy=enemies.singleOrNull {kind in 3..4 && it.first==mission?.target}
                ?: if(kind==0)enemies.minWithOrNull(compareBy<Pair<Int,TacticalPose>> {
                    distance(pose.position,it.second.position)}.thenBy {it.first}) else null
            val goal=when(kind) {
                1,2->g.bases.singleOrNull {it.id==mission?.target && it.grid==p.grid}?.let {
                    TacticalCommandOperations.basePoint(it,now,stocks)}
                5->{val length=distance(TacticalPoint(0f,0f),pose.position)
                    if(length<1)TacticalPoint(0f,260f) else TacticalPoint(pose.position.x/length*260f,pose.position.y/length*260f)}
                else->enemy?.second?.position
            }
            val hull=checkNotNull(p.hull)
            val energy=allocation(hull.kind,kind==5)
            val nextPose=if(goal==null)pose else TacticalPose(pose.position,atan2(goal.x-pose.position.x,goal.y-pose.position.y))
            val stop=if(kind==4)10f else if(kind in 1..2)3f else if(kind==5)0f else 2f
            val motion=path(p.grid,now,nextPose,goal,stop,energy.values[0]/100f)
            val retreat=if(kind==5 && RetreatRules.outside(pose))RetreatRules.destination(g,p,players.values,stocks,pose)?.let {
                TacticalRetreat(it,now,5*scale,150*scale)} else null
            val attack=if(kind in setOf(4,5) || enemies.isEmpty())null else p.attack ?: TacticalAttackOrder(2,now,now,now+5*scale)
            val next=p.copy(energy=energy,motion=motion,retreat=retreat,attack=attack,
                hull=hull.settle(now,p.energy ?: TacticalEnergy.ZERO),
                charge=p.charge?.settle(now,p.energy ?: TacticalEnergy.ZERO) ?: TacticalCharge(now,clockScale=scale))
            players[account]=next
            val motions=linkedMapOf(p.unitId to motion)
            var commanded=false
            // Every regular ship remains subject to ownership, CRC, confusion and pending-job guards.
            for((outfitId,original) in stocks.outfits.toSortedMap()) {
                if(original.power!=(if(p.power==Power.EMPIRE)2 else 3) || original.positionIn(g)!=OutfitPosition(p.grid))continue
                val updated=original.activeShips?.values?.map {unit ->
                    if(TacticalAuthority.commander(original,unit,stocks)!=p.characterId || unit.boats<=unit.damaged ||
                        stocks.tacticalCommands.confused(unit.id) || unit.support!=null || unit.shot!=null ||
                        unit.tactical?.retreat!=null || unit.posture?.let {it.mode.requiresBase || it.order!=null}==true ||
                        stocks.tacticalCommands.orders.any {unit.id in it.command.units})return@map unit
                    val at=unit.tactical?.motion?.takeIf {it.grid==p.grid}?.pose(now,unit.posture,g,stocks)
                        ?: TacticalDeployment.initial(unit.id,p.power,p.grid,players.values,stocks,g)
                    if(distance(pose.position,at.position)>(p.commandRange?.radius(now) ?: 0f))return@map unit
                    val spec=UnitSpecifications.ships.single {it.kind==unit.stack.kind}
                    val velocity=(spec.number("最高速度（km/1G秒）:最高速度（km/1G秒）")/20000f).coerceIn(.1f,1f)
                    val lateral=((unit.id%5)-2)*1.5f
                    val destination=goal?.let {TacticalPoint(it.x+lateral,it.y)}
                    val moving=path(p.grid,now,at,destination,stop,velocity)
                    val old=unit.tactical
                    val withdraw=if(kind==5 && retreat!=null)retreat.copy(membershipChecked=false) else null
                    val state=(old ?: NpcTacticalState(moving,now,now)).copy(motion=moving,nextDecisionAt=now+5*scale,retreat=withdraw)
                    val firing=if(attack==null)null else unit.attack ?: TacticalUnitAttack(p.characterId,TacticalAttackOrder(2,now,now,now+5*scale))
                    motions[unit.id]=moving;commanded=true
                    unit.copy(tactical=state,motionCommander=p.characterId,attack=firing)
                } ?: continue
                stocks=stocks.copy(outfits=stocks.outfits+(outfitId to original.copy(activeShips=ActiveShipUnits(updated))))
            }
            if(commanded)players[account]=next.copy(commandRange=next.commandRange?.reset(now))
            val audience=session.participants.mapNotNull {(a,m)->players[a]?.takeIf {
                m.online && m.connection!=null && it.grid==p.grid && (it.cpPaused || it.baseId>0)}?.let {it.characterId to checkNotNull(m.connection)}}.toMap()
            events+=TacticalCommandResult(session.generation,TacticalQueuedCommand(p.characterId,p.grid,now,now,
                TacticalCommand(0x421)),now,audience,motions=motions,stateOnly=true)
        }
        return stocks
    }
    private fun distance(a:TacticalPoint,b:TacticalPoint)=hypot(a.x-b.x,a.y-b.y)
    private fun path(grid:Int,now:Long,pose:TacticalPose,goal:TacticalPoint?,stop:Float,velocity:Float):TacticalMotion {
        val length=goal?.let {distance(pose.position,it)} ?: 0f
        val end=if(goal==null || length<=stop)pose.position else TacticalPoint(
            goal.x+(pose.position.x-goal.x)*stop/length,goal.y+(pose.position.y-goal.y)*stop/length)
        val direction=if(end==pose.position)pose.direction else atan2(end.x-pose.position.x,end.y-pose.position.y)
        return TacticalMotion(grid,now,pose,if(end==pose.position)0f else velocity,direction,listOf(end),waitSeconds=0)
    }
    private fun allocation(kind:Int,withdraw:Boolean):TacticalEnergy {
        var available=checkNotNull(Flagships.find(kind)).number("出力:出力")
        val values=MutableList(11){0}
        fun give(index:Int,amount:Int) {val assigned=minOf(available,amount);values[index]=assigned;available-=assigned}
        if(withdraw)give(1,TacticalEnergy.WARP_MAX)
        give(0,100)
        if(!withdraw){give(3,100);give(4,100)}
        for(face in 5..10)give(face,50)
        return TacticalEnergy(values)
    }
}
