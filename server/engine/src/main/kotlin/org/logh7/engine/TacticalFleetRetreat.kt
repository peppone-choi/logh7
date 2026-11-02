package org.logh7.engine

import kotlin.math.hypot

/** Regular-unit retreat uses the same 5 + 150 real-second schedule as the flagship.
 * A selected subset becomes a detached outfit with stable unit IDs; landed troops stay behind.
 * Detachment identities are replacement policy, not recovered original organisation data. Author: Peppone Choi. */
internal object TacticalFleetRetreat {
    fun accept(r:NavigationRequest,session:SessionState,g:Galaxy,n:Navigation,production:PlanetProduction,s:PlanetStocks,
        committer:WorldStateCommitter?,scale:Long):NavigationResult {
        val preview=n.apply(r.copy(tacticalRetreat=null),session,s,persist=false)
        var p=preview.player ?: return preview
        fun reject(reason:NavigationRejection)=NavigationResult(null,p,reason)
        if(committer==null)return reject(NavigationRejection.DISABLED)
        if(!p.cpPaused || p.baseId!=0 || p.mode!=5)return reject(NavigationRejection.TACTICAL)
        if(r.captainCard!=if(p.power==Power.EMPIRE)59 else 195)return reject(NavigationRejection.CARD)
        val now=session.gameMillis/1000;val units=checkNotNull(r.tacticalRetreat)
        if(now>0xffffffffL-155*scale)return reject(NavigationRejection.CLOCK)
        val players=n.snapshot(session.generation)
        val own=p.motion?.pose(now,p.posture,g,s) ?: TacticalDeployment.initial(p.unitId,p.power,p.grid,players.values,s,g)
        val regular=units.filter {it!=p.unitId}.map {id->
            val pair=s.outfits.values.mapNotNull {o->o.activeShips?.values?.singleOrNull {it.id==id}?.let {o to it}}.singleOrNull()
                ?: return reject(NavigationRejection.UNIT)
            val(o,u)=pair
            if(TacticalAuthority.commander(o,u,s)!=p.characterId || o.power!=(if(p.power==Power.EMPIRE)2 else 3) ||
                o.positionIn(g)!=OutfitPosition(p.grid))return reject(NavigationRejection.CARD)
            if(u.boats==0 || u.support!=null || u.posture?.order!=null || u.tactical?.retreat!=null)return reject(NavigationRejection.BUSY)
            val at=u.tactical?.motion?.pose(now,u.posture,g,s) ?: TacticalDeployment.initial(id,p.power,p.grid,players.values,s,g)
            if(hypot((at.position.x-own.position.x).toDouble(),(at.position.y-own.position.y).toDouble())>(p.commandRange?.radius(now) ?: 0f))
                return reject(NavigationRejection.RANGE)
            if(!RetreatRules.outside(at))return reject(NavigationRejection.INSIDE_RADAR)
            Triple(o,u,at)
        }
        if(p.unitId in units) {
            val personal=n.apply(r.copy(tacticalRetreat=listOf(p.unitId)),session,s,persist=false)
            p=personal.player ?: return personal
        }
        var next=s
        val destination=p.retreat?.destination ?: RetreatRules.destination(g,p,players.values,s,regular.first().third)
            ?: return reject(NavigationRejection.GRID)
        for((o,u,at) in regular) {
            val current=next.outfits.getValue(o.id)
            val m=u.tactical?.motion ?: TacticalMotion(p.grid,now,at,0f,at.direction,listOf(at.position))
            val state=u.tactical ?: NpcTacticalState(m,now,now)
            val changed=u.copy(tactical=state.copy(retreat=TacticalRetreat(destination,now,5*scale,150*scale)),motionCommander=p.characterId)
            next=next.copy(outfits=next.outfits+(o.id to current.copy(activeShips=ActiveShipUnits(current.activeShips!!.values.map {if(it.id==u.id)changed else it}))))
        }
        if(regular.isNotEmpty())p=p.copy(commandRange=p.commandRange?.reset(now))
        val prepared=production.prepareReplacement(s,next);val after=players+(r.account to p)
        committer.saveWorld(after,prepared);n.publishCommitted(players,after,session.generation);production.publishCommitted(s,prepared)
        return NavigationResult(p,preview.previous,commandReadyAt=now+5*scale)
    }
    fun advance(session:SessionState,g:Galaxy,players:MutableMap<String,Navigator>,initial:PlanetStocks,
        events:MutableList<TacticalMovement>):PlanetStocks {
        var stocks=initial;val now=session.gameMillis/1000
        for(outfitId in initial.outfits.keys.sorted()) {
            var outfit=stocks.outfits[outfitId] ?: continue;val grid=outfit.positionIn(g).grid
            val checked=outfit.activeShips?.values.orEmpty().map {u->
                val state=u.tactical ?: return@map u;val retreat=state.retreat ?: return@map u
                if(u.motionCommander==0 || retreat.membershipChecked || now<retreat.startsAt)return@map u
                val commander=players.values.singleOrNull {it.characterId==u.motionCommander && it.grid==grid && it.baseId==0 && it.cpPaused}
                val from=commander?.let {it.motion?.pose(retreat.startsAt,it.posture,g,stocks) ?:
                    TacticalDeployment.initial(it.unitId,it.power,grid,players.values,stocks,g)}
                val at=state.motion.pose(retreat.startsAt,u.posture,g,stocks)
                val admitted=from!=null && hypot((from.position.x-at.position.x).toDouble(),(from.position.y-at.position.y).toDouble())<=TacticalCommandRange.MAXIMUM
                u.copy(tactical=state.copy(retreat=if(admitted)retreat.copy(membershipChecked=true) else null))
            }
            if(checked!=outfit.activeShips?.values.orEmpty()) {
                outfit=outfit.copy(activeShips=ActiveShipUnits(checked));stocks=stocks.copy(outfits=stocks.outfits+(outfitId to outfit))
            }
            val due=outfit.activeShips?.values.orEmpty().filter {u->u.boats>0 && u.motionCommander>0 && u.tactical?.retreat?.let {it.membershipChecked && now>=it.completesAt}==true &&
                RetreatRules.outside(u.tactical.motion.pose(now,u.posture,g,stocks))}
            for(group in due.groupBy {it.tactical!!.retreat!!.destination}.values) {
                val current=stocks.outfits.getValue(outfitId);val target=group.first().tactical!!.retreat!!.destination
                val power=if(current.power==2)Power.EMPIRE else Power.ALLIANCE
                val forces=GridForces.collect(g,session.generation,players.values,stocks)
                if(GridEntryRules.evaluate(GridForces.state(g,target,forces),GridEntry(Camp(power,false),group.size,false))!=GridEntryVerdict.ALLOWED)continue
                val ids=group.map {it.id}.toSet();val original=current.activeShips!!.values
                val balances=OutfitSupplies.distribution(current)
                val moving=ActiveShipUnits(original.filter {it.id in ids}.map {it.copy(tactical=null,motionCommander=0,attack=null,shot=null,support=null,posture=null)})
                val remaining=ActiveShipUnits(original.filter {it.id !in ids})
                val aboard=current.activeGround?.values.orEmpty().filter {it.carrier in ids}
                val ashore=current.activeGround?.values.orEmpty().filter {it.carrier !in ids}
                val movedGround=ActiveGroundUnits(aboard)
                val leftGround=ActiveGroundUnits(ashore)
                val crew=current.units.troops.filterKeys {!GroundUnits.isGround(it.kind)}
                val movedCrew=crew.mapValues {(_,count)->(count.toLong()*moving.values.size/original.size).toInt()}.filterValues {it>0}
                val leftCrew=crew.mapValues {(stack,count)->count-(movedCrew[stack] ?: 0)}.filterValues {it>0}
                val movedSupplies=ids.sumOf {balances.getValue(it).toLong()}.toInt()
                // Keep the original outfit identity when nothing remains at the source.
                if(remaining.values.isEmpty() && ashore.isEmpty())stocks=stocks.copy(outfits=stocks.outfits+(outfitId to current.copy(
                    position=OutfitPosition(target),activeShips=moving,activeGround=if(current.activeGround==null)null else movedGround)))
                else {
                    val newId=(stocks.outfits.keys.maxOrNull() ?: 0)+1
                    val shared=current.kind==OutfitKind.PATROL
                    val warehouseId=if(shared)current.warehouseId else (stocks.warehouses.keys.maxOrNull() ?: 0)+1
                    if(stocks.outfits.size>=1000 || stocks.warehouses.size>=1000 || newId<=0 || warehouseId<=0)continue
                    val detachment=OutfitStock(newId,current.power,current.kind,current.baseId,warehouseId,
                        units=UnitInventory(moving.quantities(),movedGround.quantities()+movedCrew),supplies=movedSupplies,activeShips=moving,
                        position=OutfitPosition(target),activeGround=if(current.activeGround==null)null else movedGround)
                    val left=current.copy(activeShips=remaining,activeGround=if(current.activeGround==null)null else leftGround,
                        units=UnitInventory(remaining.quantities(),leftGround.quantities()+leftCrew),supplies=current.supplies-movedSupplies)
                    stocks=stocks.copy(outfits=stocks.outfits+(outfitId to left)+(newId to detachment),warehouses=if(shared)stocks.warehouses else stocks.warehouses+
                        (warehouseId to OutfitWarehouse(warehouseId,current.power,current.kind,current.baseId,newId)),
                        tacticalCommands=stocks.tacticalCommands.copy(authority=stocks.tacticalCommands.authority+
                            group.associate {it.id to TacticalAuthority.commander(current,it,stocks)}))
                }
                val audience=session.participants.mapNotNull {(account,member)->players[account]?.takeIf {
                    member.online && member.connection!=null && it.grid==grid && it.cpPaused}?.let {it.characterId to member.connection!!}}.toMap()
                for(u in group)events+=TacticalMovement(session.generation,u.id,grid,now,null,audience,target)
                stocks=TacticalCommandOperations.cancel(stocks,ids.toList())
            }
        }
        return stocks
    }
}
