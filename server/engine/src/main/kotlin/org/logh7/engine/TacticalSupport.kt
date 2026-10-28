package org.logh7.engine

import kotlin.math.hypot

enum class TacticalSupportAction { REPAIR, SUPPLY }
/** Provisional fixed radius shared by native commandability and server validation. */
object TacticalSupportRules { const val COMMAND_RANGE = 20f }
data class TacticalSupport(val action: TacticalSupportAction, val unit: Int, val target: Int)
data class TacticalSupportOrder(val action: TacticalSupportAction, val character: Int, val target: Int,
    val grid: Int, val acceptedAt: Long, val readyAt: Long) {
    init { require(character > 0 && validCombatTarget(target) && grid in 0..4999 &&
        acceptedAt in 0..0xffffffffL && readyAt in acceptedAt..0xffffffffL) }
}
/** Committed balances and repair state; delivery follows the durable world transaction. Author: 최병호. */
data class TacticalSupportResult(val generation: Long, val grid: Int, val action: TacticalSupportAction,
    val unit: Int, val target: Int, val supplies: Int, val targetSupplies: Int, val damaged: Int,
    val recipients: Map<Int,String>, val cancelled:Boolean=false, val character:Int=0) {
    init {require(generation>0 && grid in 0..4999 && unit in 1..999999 && validCombatTarget(target) &&
        supplies>=0 && targetSupplies>=0 && damaged in 0..300 && (!cancelled || character>0) &&
        recipients.keys.all {it>0} && recipients.values.all {it.isNotBlank()})}
}

/** Replacement policy: same-power, adjacent formation units (20 map units), five real seconds.
 * The support ship must belong to the authenticated fleet commander at acceptance and execution. */
internal object TacticalSupportOperations {
    private const val RANGE = 20.0
    private data class Ship(val outfit: OutfitStock, val unit: ActiveShipUnit)
    private fun ship(id: Int, stocks: PlanetStocks): Ship? = stocks.outfits.values.mapNotNull {o ->
        o.activeShips?.values?.singleOrNull {it.id==id}?.let {Ship(o,it)} }.singleOrNull()
    private fun pose(s: Ship, grid: Int, now: Long, players: Collection<Navigator>, stocks: PlanetStocks, galaxy: Galaxy) =
        s.unit.tactical?.motion?.takeIf {it.grid==grid}?.pose(now) ?:
            TacticalDeployment.initial(s.unit.id,if(s.outfit.power==2)Power.EMPIRE else Power.ALLIANCE,grid,players,stocks,galaxy)
    private fun targetPoint(id: Int, player: Navigator, now: Long, players: Map<String,Navigator>, stocks: PlanetStocks, galaxy: Galaxy): TacticalPoint? {
        if(id>1_000_000) {
            val target=players.values.singleOrNull {it.unitId==id && it.grid==player.grid && it.power==player.power &&
                it.baseId==0 && it.mode==5 && it.cpPaused && it.hull!=null} ?: return null
            return (target.motion?.pose(now) ?: TacticalDeployment.initial(id,target.power,target.grid,players.values,stocks,galaxy)).position
        }
        val target=ship(id,stocks) ?: return null
        if(target.unit.boats==0 || target.outfit.power!=(if(player.power==Power.EMPIRE)2 else 3) ||
            target.outfit.positionIn(galaxy)!=OutfitPosition(player.grid))return null
        return pose(target,player.grid,now,players.values,stocks,galaxy).position
    }
    private fun eligible(source: Ship, player: Navigator, order: TacticalSupportOrder, now: Long,
        players: Map<String,Navigator>, stocks: PlanetStocks, galaxy: Galaxy): Boolean {
        if(!player.cpPaused || player.baseId!=0 || player.mode!=5 || player.grid!=order.grid ||
            player.hull?.woundedUntil?.let {now<it}==true || source.unit.boats==0 || source.unit.id==order.target ||
            source.outfit.commanderId!=player.characterId || source.outfit.power!=(if(player.power==Power.EMPIRE)2 else 3) ||
            source.outfit.positionIn(galaxy)!=OutfitPosition(order.grid) || source.unit.tactical?.retreat!=null)return false
        val spec=UnitSpecifications.ships.single {it.kind==source.unit.stack.kind}
        if(spec.type!=(if(order.action==TacticalSupportAction.REPAIR)11 else 12))return false
        val to=targetPoint(order.target,player,now,players,stocks,galaxy) ?: return false
        val from=pose(source,order.grid,now,players.values,stocks,galaxy).position
        val commander=(player.motion?.pose(now) ?: TacticalDeployment.initial(player.unitId,player.power,player.grid,
            players.values,stocks,galaxy)).position
        if(hypot((commander.x-from.x).toDouble(),(commander.y-from.y).toDouble())>TacticalSupportRules.COMMAND_RANGE)return false
        return hypot((to.x-from.x).toDouble(),(to.y-from.y).toDouble())<=RANGE
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
        val preview=navigation.apply(request.copy(tacticalStop=null),session,stocks,persist=false)
        val player=preview.player ?: return preview
        fun reject(reason:NavigationRejection)=NavigationResult(null,player,reason)
        if(committer==null)return reject(NavigationRejection.DISABLED)
        if(!player.cpPaused || player.baseId!=0 || player.mode!=5)return reject(NavigationRejection.TACTICAL)
        val command=checkNotNull(request.tacticalStop)
        if(command.bases.isNotEmpty())return reject(NavigationRejection.UNIT)
        val sources=command.units.map {ship(it,stocks) ?: return reject(NavigationRejection.UNIT)}
        if(sources.any {it.outfit.commanderId!=player.characterId ||
            it.outfit.power!=(if(player.power==Power.EMPIRE)2 else 3) ||
            it.outfit.positionIn(galaxy)!=OutfitPosition(player.grid)})return reject(NavigationRejection.CARD)
        var next=stocks
        for(source in sources) {
            val current=ship(source.unit.id,next)!!
            next=replace(next,current,current.unit.copy(support=null))
        }
        val players=navigation.snapshot(session.generation)
        val prepared=production.prepareReplacement(stocks,next)
        committer.saveWorld(players+(request.account to player),prepared)
        navigation.publishCommitted(players,players+(request.account to player),session.generation)
        production.publishCommitted(stocks,prepared)
        val notifications=sources.mapNotNull {source->source.unit.support?.let {
            cancelled(session,source,it,players).let {event->event.copy(recipients=event.recipients.filterValues {it!=request.connection})}
        }}
        return NavigationResult(player,preview.previous,supportNotifications=notifications)
    }

    fun accept(request: NavigationRequest, session: SessionState, galaxy: Galaxy, navigation: Navigation,
        production: PlanetProduction, stocks: PlanetStocks, committer: WorldStateCommitter?, scale: Long): NavigationResult {
        val preview=navigation.apply(request.copy(tacticalSupport=null),session,stocks,persist=false)
        val player=preview.player ?: return preview
        fun reject(reason:NavigationRejection)=NavigationResult(null,player,reason)
        if(committer==null)return reject(NavigationRejection.DISABLED)
        val command=checkNotNull(request.tacticalSupport)
        if(!validCombatTarget(command.target))return reject(NavigationRejection.TARGET)
        val source=ship(command.unit,stocks) ?: return reject(NavigationRejection.UNIT)
        if(source.unit.support!=null)return reject(NavigationRejection.BUSY)
        val now=session.gameMillis/1000
        if(now>0xffffffffL-5*scale)return reject(NavigationRejection.CLOCK)
        val order=TacticalSupportOrder(command.action,player.characterId,command.target,player.grid,now,now+5*scale)
        val players=navigation.snapshot(session.generation)
        if(!eligible(source,player,order,now,players,stocks,galaxy))return reject(NavigationRejection.TARGET)
        if(OutfitSupplies.distribution(source.outfit).getValue(command.unit)==0)return reject(NavigationRejection.SUPPLY)
        if(!needed(order,players,stocks))return reject(NavigationRejection.TARGET)
        if(order.action==TacticalSupportAction.REPAIR) {
            val kind=players.values.singleOrNull {it.unitId==order.target}?.hull?.kind ?: ship(order.target,stocks)!!.unit.stack.kind
            val cost=UnitSpecifications.ships.single {it.kind==kind}.number("修理消費物資(１隻):修理消費物資(１隻)")
            if(cost<=0 || OutfitSupplies.distribution(source.outfit).getValue(command.unit)<cost)return reject(NavigationRejection.SUPPLY)
        }
        val next=replace(stocks,source,source.unit.copy(support=order))
        val prepared=production.prepareReplacement(stocks,next)
        committer.saveWorld(players+(request.account to player),prepared)
        navigation.publishCommitted(players,players+(request.account to player),session.generation)
        production.publishCommitted(stocks,prepared)
        return NavigationResult(player,preview.previous,supportReadyAt=order.readyAt)
    }
    private fun needed(order: TacticalSupportOrder, players: Map<String,Navigator>, stocks: PlanetStocks): Boolean {
        val personal=players.values.singleOrNull {it.unitId==order.target}
        if(personal!=null)return if(order.action==TacticalSupportAction.SUPPLY)personal.supplies<10000 else personal.hull!!.hp<100
        val target=ship(order.target,stocks) ?: return false
        return if(order.action==TacticalSupportAction.SUPPLY)
            OutfitSupplies.distribution(target.outfit).getValue(order.target)<OutfitSupplies.unitCapacity(target.unit.stack.kind)
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
            if(session.ended!=null || player==null || !eligible(source,player,order,now,players,stocks,galaxy)) {
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
                    minOf(available,OutfitSupplies.unitCapacity(target.unit.stack.kind)-targetSupplies)
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
            val remaining=OutfitSupplies.distribution(ship(id,stocks)!!.outfit).getValue(id)
            results+=TacticalSupportResult(session.generation,order.grid,order.action,id,order.target,remaining,targetSupplies,damaged,
                audience(session,order.grid,players))
        }
        return stocks
    }
}
