package org.logh7.engine

import kotlin.math.hypot

enum class TacticalSupportAction { REPAIR, SUPPLY }
/** Shared provisional maximum; current commandability uses durable CRC growth. */
object TacticalSupportRules {
    const val COMMAND_RANGE = TacticalCommandRange.MAXIMUM
    // 004c76e0/004f1180: six full-circle extents of 1; boundary itself is excluded.
    const val SUPPORT_RANGE = 1.0
}
data class TacticalSupport(val action: TacticalSupportAction, val unit: Int, val target: Int)
data class TacticalSupportOrder(val action: TacticalSupportAction, val character: Int, val target: Int,
    val grid: Int, val acceptedAt: Long, val readyAt: Long) {
    init { require(character > 0 && validCombatTarget(target) && grid in 0..4999 &&
        acceptedAt in 0..0xffffffffL && readyAt in acceptedAt..0xffffffffL) }
}
/** Committed balances and repair state; delivery follows the durable world transaction. Author: Peppone Choi. */
data class TacticalSupportResult(val generation: Long, val grid: Int, val action: TacticalSupportAction,
    val unit: Int, val target: Int, val supplies: Int, val targetSupplies: Int, val damaged: Int,
    val recipients: Map<Int,String>, val cancelled:Boolean=false, val character:Int=0) {
    init {require(generation>0 && grid in 0..4999 && unit in 1..999999 && validCombatTarget(target) &&
        supplies>=0 && targetSupplies>=0 && damaged in 0..300 && (!cancelled || character>0) &&
        recipients.keys.all {it>0} && recipients.values.all {it.isNotBlank()})}
}

/** Replacement policy: same-power units within the native support radius, five real seconds.
 * The support ship must belong to the authenticated fleet commander at acceptance and execution. */
internal object TacticalSupportOperations {
    private data class Ship(val outfit: OutfitStock, val unit: ActiveShipUnit)
    private fun ship(id: Int, stocks: PlanetStocks): Ship? = stocks.outfits.values.mapNotNull {o ->
        o.activeShips?.values?.singleOrNull {it.id==id}?.let {Ship(o,it)} }.singleOrNull()
    private fun pose(s: Ship, grid: Int, now: Long, players: Collection<Navigator>, stocks: PlanetStocks, galaxy: Galaxy) =
        s.unit.tactical?.motion?.takeIf {it.grid==grid}?.pose(now,s.unit.posture,galaxy,stocks) ?:
            TacticalDeployment.initial(s.unit.id,grid,players,stocks,galaxy)
    private fun targetPoint(order: TacticalSupportOrder, player: Navigator, now: Long, players: Map<String,Navigator>, stocks: PlanetStocks, galaxy: Galaxy): TacticalPoint? {
        val id=order.target
        if(id>1_000_000) {
            val target=players.values.singleOrNull {it.unitId==id && it.grid==player.grid && TacticalAuthority.camp(it,stocks)==TacticalAuthority.camp(player,stocks) &&
                it.baseId==0 && it.mode==5 && it.cpPaused && it.hull!=null &&
                !BattleDeath.deceased(stocks,it.characterId)} ?: return null
            return (target.motion?.pose(now,target.posture,galaxy,stocks) ?: TacticalDeployment.initial(id,target.grid,players.values,stocks,galaxy)).position
        }
        val target=ship(id,stocks) ?: return null
        if(target.unit.boats==0 || (order.action==TacticalSupportAction.REPAIR && target.outfit.guard!=null) ||
            TacticalAuthority.camp(target.outfit,stocks)!=TacticalAuthority.camp(player,stocks) ||
            target.outfit.positionIn(galaxy)!=OutfitPosition(player.grid))return null
        return pose(target,player.grid,now,players.values,stocks,galaxy).position
    }
    private fun movingOrder(unit:ActiveShipUnit,grid:Int):Boolean =
        unit.posture?.takeIf {it.grid==grid}?.order!=null ||
            unit.tactical?.takeIf {it.motion.grid==grid}?.retreat!=null
    private fun eligible(source: Ship, player: Navigator, order: TacticalSupportOrder, now: Long,
        players: Map<String,Navigator>, stocks: PlanetStocks, galaxy: Galaxy,commandRadius:Float?=null): Boolean {
        val to=eligibleTarget(source,player,order,now,players,stocks,galaxy,commandRadius) ?: return false
        val from=pose(source,order.grid,now,players.values,stocks,galaxy).position
        return hypot((to.x-from.x).toDouble(),(to.y-from.y).toDouble())<TacticalSupportRules.SUPPORT_RANGE
    }
    private fun eligibleTarget(source:Ship,player:Navigator,order:TacticalSupportOrder,now:Long,
        players:Map<String,Navigator>,stocks:PlanetStocks,galaxy:Galaxy,commandRadius:Float?=null):TacticalPoint? {
        if(!player.cpPaused || player.baseId!=0 || player.mode!=5 || player.grid!=order.grid ||
            player.hull?.woundedUntil?.let {now<it}==true || source.unit.boats<=source.unit.damaged || source.unit.id==order.target ||
            stocks.tacticalCommands.confused(source.unit.id) || movingOrder(source.unit,order.grid) ||
            TacticalAuthority.commander(source.outfit,source.unit,stocks)!=player.characterId || TacticalAuthority.camp(source.outfit,stocks)!=TacticalAuthority.camp(player,stocks) ||
            source.outfit.positionIn(galaxy)!=OutfitPosition(order.grid))return null
        val spec=UnitSpecifications.ships.single {it.kind==source.unit.stack.kind}
        if(spec.type!=(if(order.action==TacticalSupportAction.REPAIR)11 else 12))return null
        if(commandRadius!=null) {
            val from=pose(source,order.grid,now,players.values,stocks,galaxy).position
            val commander=(player.motion?.pose(now,player.posture,galaxy,stocks) ?: TacticalDeployment.initial(player.unitId,player.grid,
                players.values,stocks,galaxy)).position
            if(hypot((commander.x-from.x).toDouble(),(commander.y-from.y).toDouble())>commandRadius)return null
        }
        return targetPoint(order,player,now,players,stocks,galaxy)
    }
    private fun replace(stocks: PlanetStocks, source: Ship, unit: ActiveShipUnit): PlanetStocks =
        stocks.copy(outfits=stocks.outfits+(source.outfit.id to source.outfit.copy(activeShips=ActiveShipUnits(
            source.outfit.activeShips!!.values.map {if(it.id==unit.id)unit else it}))))
    private fun audience(session:SessionState,grid:Int,players:Map<String,Navigator>) =
        session.participants.filter {(_,m)->m.online}.mapNotNull {(a,m)->players[a]?.takeIf {
            it.grid==grid && it.baseId==0 && it.cpPaused}?.let {it.characterId to checkNotNull(m.connection)}}.toMap()
    private fun cancelled(session:SessionState,source:Ship,order:TacticalSupportOrder,players:Map<String,Navigator>) =
        TacticalSupportResult(session.generation,order.grid,order.action,source.unit.id,order.target,
            OutfitSupplies.distribution(source.outfit).getValue(source.unit.id),0,0,audience(session,order.grid,players),true,order.character)

    fun stop(request:NavigationRequest,session:SessionState,galaxy:Galaxy,navigation:Navigation,
        production:PlanetProduction,stocks:PlanetStocks,committer:WorldStateCommitter?):NavigationResult {
        val identity=navigation.apply(request.copy(tacticalStop=null),session,stocks,persist=false)
        val own=identity.player ?: return identity
        val selected=checkNotNull(request.tacticalStop)
        val preview=if(own.unitId in selected.units) navigation.apply(request.copy(
            tacticalStop=TacticalStop(listOf(own.unitId))),session,stocks,persist=false) else identity
        var player=preview.player ?: return preview
        fun reject(reason:NavigationRejection)=NavigationResult(null,player,reason)
        if(committer==null)return reject(NavigationRejection.DISABLED)
        val command=checkNotNull(request.tacticalStop)
        if(command.units.isNotEmpty() && (!player.cpPaused || player.baseId!=0 || player.mode!=5))return reject(NavigationRejection.TACTICAL)
        val sources=command.units.filter {it!=player.unitId}.map {ship(it,stocks) ?: return reject(NavigationRejection.UNIT)}
        if(sources.any {TacticalAuthority.commander(it.outfit,it.unit,stocks)!=player.characterId ||
            TacticalAuthority.camp(it.outfit,stocks)!=TacticalAuthority.camp(player,stocks) ||
            it.outfit.positionIn(galaxy)!=OutfitPosition(player.grid)})return reject(NavigationRejection.CARD)
        var next=stocks
        val now=session.gameMillis/1000
        if(now>0xffffffffL-TacticalMotion.WAIT_SECONDS)return reject(NavigationRejection.CLOCK)
        val originalPlayers=navigation.snapshot(session.generation)
        val players=(originalPlayers+(request.account to player)).toMutableMap()
        val bases=TacticalCommandOperations.stopBases(next,command.bases,player,now,session,players,galaxy)
            ?: return reject(NavigationRejection.CARD)
        next=bases.first
        player=players.getValue(request.account)
        val motions=linkedMapOf<Int,TacticalMotion>()
        val commander=if(sources.isEmpty())TacticalPoint(0f,0f) else (player.motion?.pose(now,player.posture,galaxy,stocks) ?: TacticalDeployment.initial(player.unitId,player.grid,
            navigation.snapshot(session.generation).values,stocks,galaxy)).position
        val radius=player.commandRange?.radius(now) ?: 0f
        for(source in sources) {
            val current=ship(source.unit.id,next)!!
            val location=pose(current,player.grid,now,navigation.snapshot(session.generation).values,stocks,galaxy)
            if(hypot((location.position.x-commander.x).toDouble(),(location.position.y-commander.y).toDouble())>radius)
                return reject(NavigationRejection.RANGE)
            val motion=TacticalMotion(player.grid,now,location,0f,location.direction,listOf(location.position))
            val state=current.unit.tactical?.copy(motion=motion,nextDecisionAt=now,nextFireAt=now,target=0,retreat=null)
                ?: NpcTacticalState(motion,now,now)
            motions[current.unit.id]=motion
            next=replace(next,current,current.unit.copy(support=null,tactical=state,motionCommander=0,attack=null,shot=null,posture=current.unit.posture?.copy(order=null)))
        }
        val (afterGround,groundStopped)=TacticalGroundOperations.cancel(next,command.units)
        next=TacticalCommandOperations.cancel(afterGround,command.units)
        if(player.unitId in command.units)motions[player.unitId]=checkNotNull(player.motion)
        if(sources.isNotEmpty())player=player.copy(commandRange=checkNotNull(player.commandRange).reset(now))
        val prepared=production.prepareReplacement(stocks,next)
        committer.saveWorld(players+(request.account to player),prepared)
        navigation.publishCommitted(originalPlayers,players+(request.account to player),session.generation)
        production.publishCommitted(stocks,prepared)
        val notifications=sources.mapNotNull {source->source.unit.support?.let {
            cancelled(session,source,it,players).let {event->event.copy(recipients=event.recipients.filterValues {it!=request.connection})}
        }}
        val commandNotifications=bases.second+stocks.tacticalCommands.orders.filter {it.command.units.any {id->id in command.units}}.map {
            TacticalCommandResult(session.generation,it,now,audience(session,player.grid,players),true)}
        return NavigationResult(player,preview.previous,supportNotifications=notifications,tacticalMotions=motions,
            commandNotifications=commandNotifications,groundStopped=groundStopped)
    }

    fun accept(request: NavigationRequest, session: SessionState, galaxy: Galaxy, navigation: Navigation,
        production: PlanetProduction, stocks: PlanetStocks, committer: WorldStateCommitter?, scale: Long): NavigationResult {
        val preview=navigation.apply(request.copy(tacticalSupport=null),session,stocks,persist=false)
        var player=preview.player ?: return preview
        fun reject(reason:NavigationRejection)=NavigationResult(null,player,reason)
        if(committer==null)return reject(NavigationRejection.DISABLED)
        if(BattleDeath.deceased(stocks,player.characterId))return reject(NavigationRejection.IDENTITY)
        val command=checkNotNull(request.tacticalSupport)
        if(!validCombatTarget(command.target))return reject(NavigationRejection.TARGET)
        val source=ship(command.unit,stocks) ?: return reject(NavigationRejection.UNIT)
        if(source.unit.support!=null || movingOrder(source.unit,player.grid))return reject(NavigationRejection.BUSY)
        val now=session.gameMillis/1000
        if(now>0xffffffffL-5*scale)return reject(NavigationRejection.CLOCK)
        val order=TacticalSupportOrder(command.action,player.characterId,command.target,player.grid,now,now+5*scale)
        val players=navigation.snapshot(session.generation)
        if(!eligible(source,player,order,now,players,stocks,galaxy,player.commandRange?.radius(now)))return reject(NavigationRejection.TARGET)
        if(OutfitSupplies.distribution(source.outfit).getValue(command.unit)==0)return reject(NavigationRejection.SUPPLY)
        if(!needed(source,order,players,stocks))return reject(NavigationRejection.TARGET)
        if(order.action==TacticalSupportAction.REPAIR) {
            val kind=players.values.singleOrNull {it.unitId==order.target}?.hull?.kind ?: ship(order.target,stocks)!!.unit.stack.kind
            val cost=repairCost(kind)
            if(cost<=0 || OutfitSupplies.distribution(source.outfit).getValue(command.unit)<cost)return reject(NavigationRejection.SUPPLY)
        }
        val next=replace(stocks,source,source.unit.copy(support=order,attack=null,shot=null))
        player=player.copy(commandRange=checkNotNull(player.commandRange).reset(now))
        val prepared=production.prepareReplacement(stocks,next)
        committer.saveWorld(players+(request.account to player),prepared)
        navigation.publishCommitted(players,players+(request.account to player),session.generation)
        production.publishCommitted(stocks,prepared)
        return NavigationResult(player,preview.previous,supportReadyAt=order.readyAt)
    }
    private fun repairCost(kind:Int)=UnitSpecifications.ships.single {it.kind==kind}
        .number("修理消費物資(１隻):修理消費物資(１隻)")

    data class OfflineSupport(val order:TacticalSupportOrder,val approach:TacticalPoint?=null)

    fun offlineSupport(outfit:OutfitStock,unit:ActiveShipUnit,player:Navigator,now:Long,scale:Long,
        players:Map<String,Navigator>,stocks:PlanetStocks,galaxy:Galaxy,alliedTargets:List<Int>,
        reserved:Set<Pair<TacticalSupportAction,Int>>,commander:TacticalPoint):OfflineSupport? {
        val type=UnitSpecifications.ships.single {it.kind==unit.stack.kind}.type
        val action=when(type) {
            11 -> TacticalSupportAction.REPAIR
            12 -> TacticalSupportAction.SUPPLY
            else -> return null
        }
        val available=OutfitSupplies.distribution(outfit).getValue(unit.id)
        if(available==0)return null
        val source=Ship(outfit,unit)
        val from=pose(source,player.grid,now,players.values,stocks,galaxy).position
        val radius=player.commandRange?.radius(now) ?: 0f
        var approach:OfflineSupport?=null
        for(target in listOf(player.unitId)+alliedTargets) {
            if(target==unit.id || (action to target) in reserved)continue
            val order=TacticalSupportOrder(action,player.characterId,target,player.grid,now,now+5*scale)
            val point=eligibleTarget(source,player,order,now,players,stocks,galaxy) ?: continue
            if(!needed(source,order,players,stocks))continue
            if(action==TacticalSupportAction.REPAIR) {
                val kind=players.values.singleOrNull {it.unitId==target}?.hull?.kind ?: checkNotNull(ship(target,stocks)).unit.stack.kind
                if(repairCost(kind) !in 1..available)continue
            }
            if(hypot((point.x-from.x).toDouble(),(point.y-from.y).toDouble())<TacticalSupportRules.SUPPORT_RANGE)
                return OfflineSupport(order)
            if(approach==null && hypot(point.x-commander.x,point.y-commander.y)<=radius)
                approach=OfflineSupport(order,point)
        }
        return approach
    }

    private fun supplyRoom(source:Ship,target:Ship,targetSupplies:Int,stocks:PlanetStocks):Int {
        val unitRoom=OutfitSupplies.unitCapacity(target.unit)-targetSupplies
        if(source.outfit.id==target.outfit.id || target.outfit.kind!=OutfitKind.TRANSPORT)return unitRoom
        val cargo=TransportActions.carriedCargo(target.outfit,stocks.strategic.transports).sumOf {it.first.toLong()}
        val outfitRoom=OutfitSupplies.capacity(target.outfit)-target.outfit.supplies-cargo
        return minOf(unitRoom.toLong(),outfitRoom).coerceAtLeast(0).toInt()
    }

    private fun needed(source:Ship,order: TacticalSupportOrder, players: Map<String,Navigator>, stocks: PlanetStocks): Boolean {
        val personal=players.values.singleOrNull {it.unitId==order.target}
        if(personal!=null)return if(order.action==TacticalSupportAction.SUPPLY)personal.supplies<10000 else personal.hull!!.hp<100
        val target=ship(order.target,stocks) ?: return false
        return if(order.action==TacticalSupportAction.SUPPLY)
            supplyRoom(source,target,OutfitSupplies.distribution(target.outfit).getValue(order.target),stocks)>0
            else target.unit.damaged>0
    }
    fun advance(session: SessionState, galaxy: Galaxy, players: MutableMap<String,Navigator>, initial: PlanetStocks,
        results: MutableList<TacticalSupportResult>): PlanetStocks {
        var stocks=initial
        val now=session.gameMillis/1000
        for(id in initial.outfits.values.flatMap {it.activeShips?.values.orEmpty()}.filter {it.support!=null}.map {it.id}.sorted()) {
            var source=ship(id,stocks) ?: continue
            val order=source.unit.support ?: continue
            val player=players.values.singleOrNull {it.characterId==order.character}
            if(session.ended!=null || source.outfit.country in session.eliminated ||
                player==null || BattleDeath.deceased(stocks,order.character) ||
                !eligible(source,player,order,now,players,stocks,galaxy)) {
                results+=cancelled(session,source,order,players)
                stocks=replace(stocks,source,source.unit.copy(support=null)); continue
            }
            if(now<order.readyAt)continue
            stocks=replace(stocks,source,source.unit.copy(support=null))
            source=ship(id,stocks)!!
            val available=OutfitSupplies.distribution(source.outfit).getValue(id)
            var targetSupplies=0; var damaged=0
            val entry=players.entries.singleOrNull {it.value.unitId==order.target}
            if(entry!=null) {
                val target=entry.value
                targetSupplies=target.supplies
                val amount=if(order.action==TacticalSupportAction.SUPPLY)minOf(available,10000-target.supplies)
                    else if(target.hull!!.hp<100)UnitSpecifications.ships.single {it.kind==target.hull.kind}
                        .number("修理消費物資(１隻):修理消費物資(１隻)").takeIf {it>0 && it<=available} ?: 0 else 0
                if(amount>0) {
                    val updated=if(order.action==TacticalSupportAction.SUPPLY)target.copy(supplies=target.supplies+amount)
                        else target.copy(hull=target.hull!!.copy(hp=100))
                    players[entry.key]=updated; targetSupplies=updated.supplies
                    stocks=stocks.copy(outfits=stocks.outfits+(source.outfit.id to OutfitSupplies.change(source.outfit,id,-amount)))
                }
                damaged=if(players.getValue(entry.key).hull!!.hp<100)1 else 0
            } else {
                val target=ship(order.target,stocks)!!
                val balances=OutfitSupplies.distribution(target.outfit)
                targetSupplies=balances.getValue(order.target); damaged=target.unit.damaged
                val cost=UnitSpecifications.ships.single {it.kind==target.unit.stack.kind}.number("修理消費物資(１隻):修理消費物資(１隻)")
                val amount=if(order.action==TacticalSupportAction.SUPPLY)
                    minOf(available,supplyRoom(source,target,targetSupplies,stocks))
                    else if(cost>0)minOf(damaged,available/cost)*cost else 0
                if(amount>0) {
                    if(source.outfit.id==target.outfit.id) {
                        val moved=balances+(id to (available-amount))+
                            (order.target to (targetSupplies+if(order.action==TacticalSupportAction.SUPPLY)amount else 0))
                        stocks=stocks.copy(outfits=stocks.outfits+(source.outfit.id to OutfitSupplies.withBalances(source.outfit,moved)))
                    } else {
                        stocks=stocks.copy(outfits=stocks.outfits+(source.outfit.id to OutfitSupplies.change(source.outfit,id,-amount)))
                        if(order.action==TacticalSupportAction.SUPPLY)stocks=stocks.copy(outfits=stocks.outfits+
                            (target.outfit.id to OutfitSupplies.change(target.outfit,order.target,amount)))
                    }
                    if(order.action==TacticalSupportAction.REPAIR) {
                        val current=ship(order.target,stocks)!!
                        damaged-=amount/cost
                        stocks=replace(stocks,current,current.unit.copy(damaged=damaged))
                    }
                }
                targetSupplies=OutfitSupplies.distribution(ship(order.target,stocks)!!.outfit).getValue(order.target)
                // 004c15a0 subtracts this wire counter from the commissioned maximum, not survivors.
                damaged+=UnitSpecifications.ships.single {it.kind==target.unit.stack.kind}.number-target.unit.boats
            }
            val updatedSource=checkNotNull(ship(id,stocks))
            val remaining=OutfitSupplies.distribution(updatedSource.outfit).getValue(id)
            results+=if(remaining==available)cancelled(session,updatedSource,order,players)
                else TacticalSupportResult(session.generation,order.grid,order.action,id,order.target,remaining,targetSupplies,damaged,
                    audience(session,order.grid,players))
        }
        return stocks
    }
}
