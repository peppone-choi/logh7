package org.logh7.engine

import kotlin.math.*

/** Manual: offline AI accepts tactical missions (update04). Steering, allocation and five-real-second
 * decisions below are replacement policy. Uses the normal motion, reload and damage models. Author: Peppone Choi. */
internal object TacticalOfflineAi {
    fun eligible(session:SessionState,account:String,p:Navigator,stocks:PlanetStocks)=session.ended==null &&
        session.participants[account]?.online==false && p.generation==session.generation && p.cpPaused &&
        p.baseId==0 && p.mode==5 && p.hull!=null && p.hull.woundedUntil<=session.gameMillis/1000 &&
        !BattleDeath.deceased(stocks,p.characterId) && stocks.tacticalCommands.tacticalAi(p.characterId)

    private fun currentMission(p:Navigator,stocks:PlanetStocks)=stocks.tacticalCommands.missions[p.characterId]
        ?.takeIf {!it.completed && (it.grid==null || it.grid==p.grid)}

    fun defenseBase(p:Navigator,stocks:PlanetStocks,g:Galaxy):GalaxyBase? {
        if(currentMission(p,stocks)?.defaultDefense==false || TacticalAuthority.camp(p,stocks).insurgent)return null
        return g.bases.asSequence().filter {it.grid==p.grid}.filter {base->
            val owner=stocks.bases[base.id]
            owner!=null && owner.country==p.country && owner.camp==0 &&
                TacticalCommandOperations.baseCommander(base,owner,stocks,g)==p.characterId
        }.minByOrNull {it.id}
    }

    fun automaticTargets(session:SessionState,account:String,p:Navigator,stocks:PlanetStocks,ids:List<Int>):List<Int> {
        val mission=currentMission(p,stocks)
        val preferred=mission?.takeIf {it.mission==3}?.target
        if(eligible(session,account,p,stocks)) {
            if(stocks.tacticalCommands.confused(p.unitId))return emptyList()
            when(mission?.mission) {
                3 -> return listOf(mission.target)
                4,5 -> return emptyList()
            }
        }
        return ids.distinct().sortedWith(compareBy<Int> {if(it==preferred)0 else 1}.thenBy {it})
    }

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
            val pose=p.motion?.pose(now,p.posture,g,stocks) ?: TacticalDeployment.initial(p.unitId,p.grid,players.values,stocks,g)
            val mission=currentMission(p,stocks)?.takeUnless {it.defaultDefense}
            val camp=TacticalAuthority.camp(p,stocks)
            val defended=defenseBase(p,stocks,g)
            val kind=if(stocks.tacticalCommands.confused(p.unitId))5 else mission?.mission ?: if(defended!=null)1 else 0
            val ids=(players.values.map {it.unitId}+stocks.outfits.values.flatMap {it.activeShips?.values.orEmpty().map {u->u.id}}+PirateActions.ids(stocks)).distinct()
            val enemies=ids.mapNotNull {id->TacticalCombat.target(id,p.grid,camp,stocks,g,players)?.let {
                id to TacticalFleetAttack.pose(it,now,players,stocks,g)}}
                .filterNot {TacticalTerrain.sharedSensorBlocked(g,p.grid,camp,pose.position,it.second.position,now,players.values,stocks)}
            val enemy=enemies.singleOrNull {kind in 3..4 && it.first==mission?.target}
                ?: if(kind==0)enemies.minWithOrNull(compareBy<Pair<Int,TacticalPose>> {
                    distance(pose.position,it.second.position)}.thenBy {it.first}) else null
            val missionBase=g.bases.singleOrNull {it.id==mission?.target && it.grid==p.grid}
            // An occupation mission may outlive its target's capture until this tick is settled.
            if(kind==2 && (missionBase==null || stocks.bases[missionBase.id]?.let(TacticalAuthority::camp)==camp))continue
            val occupied=missionBase?.takeIf {kind==2}
            val goal=when(kind) {
                1,2->(defended ?: missionBase)?.let {TacticalCommandOperations.basePoint(it,now,stocks)}
                5->{val length=distance(TacticalPoint(0f,0f),pose.position)
                    if(length<1)TacticalPoint(0f,260f) else TacticalPoint(pose.position.x/length*260f,pose.position.y/length*260f)}
                else->enemy?.second?.position
            }
            val hull=checkNotNull(p.hull)
            val energy=allocation(hull.kind,kind==5)
            val nextPose=if(goal==null)pose else TacticalPose(pose.position,atan2(goal.x-pose.position.x,goal.y-pose.position.y))
            val stop=if(kind==4)(TacticalMissionRules.RECONNAISSANCE_RANGE_TACTICAL/2).toFloat() else if(kind in 1..2)3f else if(kind==5)0f else 2f
            val motion=path(g,p.grid,now,nextPose,goal,stop,energy.values[0]/100f)
            val retreat=if(kind==5 && RetreatRules.outside(pose))RetreatRules.destination(g,p,players.values,stocks,pose)?.let {
                TacticalRetreat(it,now,5*scale,150*scale)} else null
            val attack=if(kind in setOf(4,5) || enemies.isEmpty() || kind==3 && enemy==null)null
                else p.attack ?: TacticalAttackOrder(2,now,now,now+5*scale)
            val next=p.copy(energy=energy,motion=motion,retreat=retreat,attack=attack,
                hull=hull.settle(now,p.energy ?: TacticalEnergy.ZERO),
                charge=p.charge?.settle(now,p.energy ?: TacticalEnergy.ZERO) ?: TacticalCharge(now,clockScale=scale))
            players[account]=next
            val motions=linkedMapOf(p.unitId to motion)
            var commanded=false
            val alliedShips=if(kind in 0..1) stocks.outfits.values.asSequence()
                .filter {TacticalAuthority.camp(it,stocks)==camp && it.positionIn(g)==OutfitPosition(p.grid)}
                .flatMap {it.activeShips?.values.orEmpty().asSequence()}
                .map {it.id}.sorted().toList() else emptyList()
            // Every regular ship remains subject to ownership, CRC, confusion and pending-job guards.
            for((outfitId,original) in stocks.outfits.toSortedMap()) {
                if(TacticalAuthority.camp(original,stocks)!=camp || original.positionIn(g)!=OutfitPosition(p.grid))continue
                val commandable=original.activeShips?.values.orEmpty().filter {unit->
                    TacticalAuthority.commander(original,unit,stocks)==p.characterId && unit.boats>unit.damaged &&
                        !stocks.tacticalCommands.confused(unit.id) && unit.support==null && unit.shot==null &&
                        unit.tactical?.retreat==null && unit.posture?.order==null &&
                        stocks.tacticalCommands.orders.none {unit.id in it.command.units} &&
                        original.activeGround?.values.orEmpty().none {it.order?.carrier==unit.id}
                }.filter {unit->
                    val at=unit.tactical?.motion?.takeIf {it.grid==p.grid}?.pose(now,unit.posture,g,stocks)
                        ?: TacticalDeployment.initial(unit.id,p.grid,players.values,stocks,g)
                    distance(pose.position,at.position)<=(p.commandRange?.radius(now) ?: 0f)
                }.map {it.id}.toSet()
                if(occupied!=null) {
                    val landed=occupation(original,occupied,p,now,scale,stocks,g,players.values,commandable)
                    if(landed!==original) {stocks=stocks.copy(outfits=stocks.outfits+(outfitId to landed));commanded=true;continue}
                }
                val updated=original.activeShips?.values?.map {unit ->
                    if(unit.id !in commandable || unit.posture?.mode?.requiresBase==true)return@map unit
                    if(kind in 0..1) {
                        val support=TacticalSupportOperations.offlineOrder(original,unit,p,now,scale,players,stocks,g,alliedShips)
                        if(support!=null) {
                            commanded=true
                            return@map unit.copy(support=support,attack=null,shot=null)
                        }
                    }
                    val at=unit.tactical?.motion?.takeIf {it.grid==p.grid}?.pose(now,unit.posture,g,stocks)
                        ?: TacticalDeployment.initial(unit.id,p.grid,players.values,stocks,g)
                    val spec=UnitSpecifications.ships.single {it.kind==unit.stack.kind}
                    val velocity=(spec.number("最高速度（km/1G秒）:最高速度（km/1G秒）")/20000f).coerceIn(.1f,1f)
                    val lateral=((unit.id%5)-2)*1.5f
                    val destination=goal?.let {TacticalPoint(it.x+lateral,it.y)}
                    val carrying=occupied!=null && original.activeGround?.values.orEmpty().any {it.carrier==unit.id && it.order==null}
                    val moving=path(g,p.grid,now,at,if(carrying)TacticalCommandOperations.basePoint(occupied!!,now,stocks) else destination,
                        if(carrying)0.2f else stop,velocity)
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

    /** U-01 占領 for one outfit: a troop carrier within docking reach of [base] is ordered to anchor there (the player's
     * 10-second 0x041c wait); an anchored carrier's troops get the player's 25-second landing order. Armored troops on
     * special planets/fortresses and the 30-unit box stay aboard, as for a player's 040f. Returns [outfit] when unchanged. */
    private fun occupation(outfit:OutfitStock,base:GalaxyBase,p:Navigator,now:Long,scale:Long,stocks:PlanetStocks,g:Galaxy,
        players:Collection<Navigator>,commandable:Set<Int>):OutfitStock {
        val ships=outfit.activeShips ?: return outfit
        val ground=outfit.activeGround ?: return outfit
        val point=TacticalBasePosition.at(base,now,stocks)
        val reach=maxOf(1.0,base.diameter.toDouble(),if(base.kind==0)5.5 else 0.5)
        var units=ships.values;var troops=ground.values;var changed=false
        var box=TacticalGroundOperations.landingSlotsUsed(base,TacticalAuthority.camp(outfit,stocks),players,stocks,g)
        for(unit in ships.values) {
            val aboard=troops.filter {it.carrier==unit.id && it.order==null && GroundBattleRules.admitsCrewType(base,
                UnitSpecifications.crews.single {c->c.kind==it.stack.kind}.type)}
            if(aboard.isEmpty() || unit.id !in commandable)continue
            val posture=unit.posture?.takeIf {it.grid==p.grid}
            if(posture?.order!=null)continue
            if(posture?.mode==TacticalShipMode.ANCHORED && posture.base==base.id) {
                val landing=aboard.take(maxOf(0,GroundBattleRules.BOX_CAPACITY_PER_FACTION-box))
                if(landing.isEmpty())continue
                val order=TacticalGroundOrder(TacticalGroundAction.DEPLOY,p.characterId,p.grid,unit.id,base.id,now,now+25*scale)
                val ids=landing.map {it.id}.toSet()
                troops=troops.map {if(it.id in ids)it.copy(order=order) else it};box+=landing.size;changed=true
                continue
            }
            val at=unit.tactical?.motion?.takeIf {it.grid==p.grid}?.pose(now,unit.posture,g,stocks)
                ?: TacticalDeployment.initial(unit.id,p.grid,players,stocks,g)
            if(hypot((at.position.x-point.x).toDouble(),(at.position.y-point.y).toDouble())>reach)continue
            val current=posture ?: TacticalPosture(p.grid)
            val anchor=current.copy(order=TacticalPostureOrder(p.characterId,TacticalShipMode.ANCHORED,base.id,now,now+10*scale))
            units=units.map {if(it.id==unit.id)it.copy(posture=anchor,attack=null,shot=null) else it};changed=true
        }
        return if(!changed)outfit else outfit.copy(activeShips=ActiveShipUnits(units),activeGround=ActiveGroundUnits(troops))
    }
    private fun path(g:Galaxy,grid:Int,now:Long,pose:TacticalPose,goal:TacticalPoint?,stop:Float,velocity:Float):TacticalMotion {
        val length=goal?.let {distance(pose.position,it)} ?: 0f
        val end=if(goal==null || length<=stop)pose.position else TacticalPoint(
            goal.x+(pose.position.x-goal.x)*stop/length,goal.y+(pose.position.y-goal.y)*stop/length)
        val waypoints=TacticalTerrain.route(g,grid,pose.position,end)
        val moving=waypoints.size>1 || waypoints.single()!=pose.position
        val approach=waypoints.getOrElse(waypoints.lastIndex-1) {pose.position}
        val destination=waypoints.last()
        val direction=if(moving)atan2(destination.x-approach.x,destination.y-approach.y) else pose.direction
        return TacticalMotion(grid,now,pose,if(moving)velocity else 0f,direction,waypoints,waitSeconds=0)
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
