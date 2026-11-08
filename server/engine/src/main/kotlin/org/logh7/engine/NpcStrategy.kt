package org.logh7.engine

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max

/** Replacement strategic policy for uncommanded fleets; not offline character delegation. */
data class NpcStrategicState(val nextDecisionAt:Long,val destination:Int?=null,val arrivesAt:Long?=null) {
    init {
        require(nextDecisionAt in 0..0xffffffffL && (destination==null)==(arrivesAt==null))
        require(destination==null || destination in 0..4999 && arrivesAt!! in 0..nextDecisionAt)
    }
}
/**
 * A committed strategic grid change of uncommanded NPC fleets, and of an offline captain's reserved warp
 * (outfit 0 and no units for a flagship without a deployed fleet; the flagship has no ship-unit ID).
 */
data class NpcStrategicMovement(val generation:Long,val outfit:Int,val source:Int,val destination:Int,
    val seconds:Long,val units:List<Int>,val recipients:Map<Int,String>) {
    init {require(generation>0 && outfit>=0 && source in 0..4999 && destination in 0..4999 && source!=destination &&
        seconds in 0..0xffffffffL && units.size in 0..300 && (outfit>0 || units.isEmpty()) &&
        units.distinct().size==units.size && units.all {it in 1..999999} &&
        recipients.keys.all {it>0} && recipients.values.all {it.isNotBlank()})}
}

internal object NpcStrategy {
    private fun distance(a:Int,b:Int)=max(abs(a%100-b%100),abs(a/100-b/100))
    private fun eligible(outfit:OutfitStock)=outfit.kind in setOf(OutfitKind.FLEET,OutfitKind.PATROL) &&
        outfit.members().isEmpty() && outfit.activeGround?.values.orEmpty().none {it.order!=null} && outfit.activeShips?.values?.any {it.boats>0}==true &&
        outfit.activeShips.values.none {it.support!=null || it.attack!=null || it.shot!=null || it.posture?.order!=null || it.tactical?.retreat!=null}
    private fun allowed(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,target:Int):Boolean {
        val count=GridForces.mobileUnits(outfit)
        if(count !in 1..300)return false
        return GridEntryRules.evaluate(GridForces.state(galaxy,target,forces),GridEntry(Camp(outfit.country,false),count.toInt(),false))==GridEntryVerdict.ALLOWED
    }
    private fun neighbors(grid:Int):List<Int> {
        val x=grid%100;val y=grid/100
        return (-1..1).flatMap {dy->(-1..1).mapNotNull {dx->
            if(dx==0 && dy==0 || x+dx !in 0..99 || y+dy !in 0..49)null else (y+dy)*100+x+dx
        }}
    }
    private fun step(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,origin:Int,goal:Int,preferred:Int?=null):Int? {
        val camp=Camp(outfit.country,false);val strength=GridForces.mobileUnits(outfit)
        return neighbors(origin).filter {distance(it,goal)<distance(origin,goal) && hostile(forces,it,camp)<=strength*2 && allowed(galaxy,forces,outfit,it)}
            .minWithOrNull(compareBy<Int> {if(it==preferred)0 else 1}.thenBy {distance(it,goal)}.thenBy {it})
    }
    /** Hostile mobile units on [grid] against [camp]. */
    private fun hostile(forces:Map<Int,List<GridForces.Force>>,grid:Int,camp:Camp)=
        forces[grid].orEmpty().filter {it.camp!=camp}.sumOf {it.units}
    private data class DepotRoute(val base:GalaxyBase,val next:Int,val jumps:Int)
    private data class RouteVisit(val grid:Int,val first:Int,val jumps:Int,val estimatedTotal:Int)

    private fun home(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,stocks:PlanetStocks):DepotRoute? {
        val origin=outfit.positionIn(galaxy).grid
        fun stocked(base:GalaxyBase)=stocks.bases.getValue(base.id).supplies>0
        val depots=galaxy.bases.filter {b->stocks.bases[b.id]?.let {it.country==outfit.country && it.camp==0}==true &&
            !TacticalContact.contested(forces[b.grid].orEmpty())}
            .sortedWith(compareBy<GalaxyBase> {stocked(it)}.thenByDescending {it.id}).associateBy {it.grid}
        if(depots.isEmpty())return null
        val local=depots[origin]?.let {DepotRoute(it,origin,0)}
        if(local!=null && stocked(local.base))return local
        val strength=GridForces.mobileUnits(outfit)
        if(strength !in 1..GridEntryRules.MAX_UNITS_PER_CAMP.toLong())return local
        val balances=OutfitSupplies.distribution(outfit)
        val affordable=outfit.activeShips!!.values.filter {it.boats>0}.minOf {balances.getValue(it.id)/it.boats}
        val camp=Camp(outfit.country,false)
        val entry=GridEntry(camp,strength.toInt(),false)
        fun remaining(grid:Int)=depots.keys.minOf {distance(grid,it)}

        // Every jump costs one, so straight-line grid distance is a lower bound even around blocked cells.
        val queue=PriorityQueue(compareBy<RouteVisit> {it.estimatedTotal}.thenByDescending {it.jumps}.thenBy {it.grid})
        val best=mutableMapOf(origin to 0)
        val blocked=mutableSetOf<Int>()
        queue.add(RouteVisit(origin,origin,0,remaining(origin)))
        var fallback=local
        while(queue.isNotEmpty()) {
            val current=queue.remove()
            if(best[current.grid]!=current.jumps)continue
            if(fallback!=null && current.estimatedTotal>affordable)return fallback
            depots[current.grid]?.let {
                val route=DepotRoute(it,current.first,current.jumps)
                if(stocked(it) && current.jumps<=affordable)return route
                if(fallback==null)fallback=route
            }
            val jumps=current.jumps+1
            for(grid in neighbors(current.grid)) {
                if(jumps>=(best[grid] ?: Int.MAX_VALUE) || grid in blocked)continue
                if(hostile(forces,grid,camp)>strength*2 ||
                    GridEntryRules.evaluate(GridForces.state(galaxy,grid,forces),entry)!=GridEntryVerdict.ALLOWED) {
                    blocked.add(grid)
                    continue
                }
                best[grid]=jumps
                val first=if(current.jumps==0)grid else current.first
                queue.add(RouteVisit(grid,first,jumps,jumps+remaining(grid)))
            }
        }
        return fallback
    }
    /** Reserve real cargo for every remaining jump to a reachable safe depot. */
    fun returnDistance(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,stocks:PlanetStocks):Int? =
        if(!eligible(outfit) || outfit.positionIn(galaxy).baseId!=0)null else home(galaxy,forces,outfit,stocks)?.jumps
    /**
     * Next adjacent grid: towards the nearest enemy it can take on (at most twice its own mobile units), or, when
     * every enemy outmatches it, a stronger enemy shares its grid or its fuel runs short, back towards a reachable own depot
     * (`evidence:guess`, 2026-11-04 replacement policy).
     */
    private fun target(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,stocks:PlanetStocks,preferred:Int?=null):OutfitPosition? {
        val position=outfit.positionIn(galaxy)
        val origin=position.grid
        val camp=Camp(outfit.country,false)
        val own=GridForces.mobileUnits(outfit)
        fun beatable(grid:Int)=hostile(forces,grid,camp)<=own*2
        val enemies=forces.entries.filter {(_,rows)->rows.any {it.camp!=camp && (it.units>0 || it.base)}}.map {it.key}
        val candidates=enemies.filter(::beatable)
        // Keep a reserved route towards a still-valid enemy when an unrelated enemy appears.
        val continuing=if(origin in candidates)listOf(origin) else if(preferred==null)candidates else
            candidates.filter {distance(preferred,it)<distance(origin,it)}.ifEmpty {candidates}
        val goal=continuing.minWithOrNull(compareBy<Int> {distance(origin,it)}.thenBy {it})
        val home=if(position.baseId==0)home(galaxy,forces,outfit,stocks) else null
        // Turn back before the next ammunition payment could consume the return trip.
        val live=outfit.activeShips?.values.orEmpty().filter {it.boats>0}
        val balances=OutfitSupplies.distribution(outfit)
        val short=position.baseId==0 && live.any {unit->
            val spec=UnitSpecifications.ships.single {it.kind==unit.stack.kind}
            val shot=TacticalWeapon.entries.maxOf {weapon->
                val mount=GuardCombat.mount(TacticalArmaments.mount(spec,weapon),weapon,outfit,unit.id)
                if(mount.power==0)0 else TacticalFleetShoot.cost(unit,mount,weapon)}
            val required=maxOf(unit.boats*2,(home?.let {it.jumps*unit.boats+shot} ?: 0))
            balances.getValue(unit.id)<required
        }
        if(goal!=null && beatable(origin) && !short)return if(goal==origin)null else step(galaxy,forces,outfit,origin,goal,preferred)?.let(::OutfitPosition)
        if(home==null)return null
        return OutfitPosition(home.next,if(home.next==home.base.grid)home.base.id else 0)
    }
    private fun depot(stocks:PlanetStocks,outfit:OutfitStock,galaxy:Galaxy)=
        stocks.bases[outfit.positionIn(galaxy).baseId]?.takeIf {it.country==outfit.country && it.camp==0}

    private fun jumpShortfall(ships:List<ActiveShipUnit>,balances:Map<Int,Int>,jumps:Int)=
        ships.sumOf {max(0,it.boats*jumps-balances.getValue(it.id)).toLong()}

    private fun canDepart(stocks:PlanetStocks,outfit:OutfitStock,galaxy:Galaxy):Boolean {
        val ships=outfit.activeShips!!.values.filter {it.boats>0}
        val source=depot(stocks,outfit,galaxy)
        return jumpShortfall(ships,OutfitSupplies.distribution(outfit),if(source==null)1 else 2)<=(source?.supplies ?: 0)
    }

    private fun supplied(stocks:PlanetStocks,outfit:OutfitStock,galaxy:Galaxy,departing:Boolean=true):Pair<PlanetStocks,OutfitStock>? {
        val live=outfit.activeShips!!.values.filter {it.boats>0}
        val balances=OutfitSupplies.distribution(outfit).toMutableMap()
        val depot=depot(stocks,outfit,galaxy)
        var available=depot?.supplies ?: 0
        // Every ship launching from a depot carries enough for the next jump and a return.
        val jumps=if(!departing)0 else if(depot==null)1 else 2
        if(jumpShortfall(live,balances,jumps)>available)return null
        for(unit in live) {
            val amount=max(0,unit.boats*jumps-balances.getValue(unit.id))
            balances[unit.id]=balances.getValue(unit.id)+amount;available-=amount
        }
        // Carry several future jumps where the real depot and surviving cargo permit it.
        for(unit in live) {
            val goal=minOf(OutfitSupplies.unitCapacity(unit),unit.boats*10)
            val amount=minOf(available,max(0,goal-balances.getValue(unit.id)))
            balances[unit.id]=balances.getValue(unit.id)+amount;available-=amount
        }
        if(departing)for(unit in live)balances[unit.id]=balances.getValue(unit.id)-unit.boats
        val inventory=if(depot==null)stocks else stocks.copy(bases=stocks.bases+(depot.baseId to depot.copy(supplies=available)))
        return inventory to OutfitSupplies.withBalances(outfit,balances)
    }
    fun advance(session:SessionState,galaxy:Galaxy,players:MutableMap<String,Navigator>,original:PlanetStocks,
        movements:MutableList<NpcStrategicMovement>):PlanetStocks {
        if(session.ended!=null)return original
        val now=session.gameMillis/1000
        if(now>0xffffffffL-21600)return original
        var stocks=original
        var forceCache:Map<Int,List<GridForces.Force>>?=null
        fun forces():Map<Int,List<GridForces.Force>> = forceCache ?: GridForces.collect(galaxy,stocks.generation,players.values,stocks).also {forceCache=it}
        fun dockLocally(outfit:OutfitStock,destination:OutfitPosition) {
            val (inventory,replenished)=checkNotNull(supplied(stocks,outfit.copy(position=destination),galaxy,departing=false))
            val docked=replenished.copy(strategy=NpcStrategicState(now+21600),
                activeShips=ActiveShipUnits(replenished.activeShips!!.values.map {it.copy(tactical=null,motionCommander=0,posture=null)}))
            stocks=inventory.copy(outfits=inventory.outfits+(outfit.id to docked))
            forceCache=null
        }
        for(id in original.outfits.keys.sorted()) {
            var outfit=stocks.outfits.getValue(id)
            val position=outfit.positionIn(galaxy)
            val owner=stocks.bases[position.baseId]
            val permitted=eligible(outfit) && (position.baseId==0 || owner?.let {it.country==outfit.country && it.camp==0}==true)
            if(!permitted) {
                if(outfit.strategy!=null)stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(strategy=null)))
                continue
            }
            val pending=outfit.strategy
            if(pending?.destination!=null && now>=pending.arrivesAt!!) {
                val destination=pending.destination
                val planned=target(galaxy,forces(),outfit,stocks,destination)
                if(planned?.grid==position.grid) {
                    dockLocally(outfit,planned)
                    continue
                }
                // Re-evaluate source hostility as well as destination capacity after earlier arrivals.
                if(distance(position.grid,destination)!=1 || planned?.grid!=destination || !allowed(galaxy,forces(),outfit,destination)) {
                    val unavailableDepot=galaxy.bases.any {base->base.grid==destination &&
                        stocks.bases[base.id]?.let {it.country!=outfit.country || it.camp!=0 || it.supplies==0}==true}
                    val reroute=if(unavailableDepot && position.baseId==0 && planned!=null)
                        home(galaxy,forces(),outfit,stocks)?.takeIf {it.next==planned.grid}
                    else null
                    val strategy=if(reroute==null || !canDepart(stocks,outfit,galaxy))NpcStrategicState(now+21600)
                        else NpcStrategicState(now+21600,reroute.next,now+3600)
                    stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(strategy=strategy)))
                    continue
                }
                val provision=supplied(stocks,outfit,galaxy)
                if(provision==null) {
                    stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(strategy=NpcStrategicState(now+21600))))
                    continue
                }
                stocks=provision.first;outfit=provision.second
                var moved=outfit.copy(position=planned,strategy=NpcStrategicState(now+21600),
                    activeShips=ActiveShipUnits(outfit.activeShips!!.values.map {it.copy(tactical=null,motionCommander=0,
                        posture=null,attack=null,shot=null)}))
                if(planned.baseId!=0) {
                    val replenished=checkNotNull(supplied(stocks,moved,galaxy,departing=false))
                    stocks=replenished.first;moved=replenished.second
                }
                stocks=stocks.copy(outfits=stocks.outfits+(id to moved))
                forceCache=null // Later arrivals must see this move when checking the 300-unit cap.
                for((account,player) in players.toMap())if(player.grid==destination && !player.cpPaused &&
                    (player.hull?.woundedUntil ?: 0)<=now && TacticalContact.active(galaxy,player,players.values,stocks)) {
                    players[account]=player.copy(cpPaused=true,cpRecoveryAt=now,
                        commandRange=TacticalCommandRange(now,command=50),motion=null,retreat=null,charge=null,
                        attack=null,fire=player.fire?.copy(pending=null))
                }
                val audience=session.participants.mapNotNull {(account,member)->players[account]?.takeIf {
                    member.online && it.generation==session.generation}?.let {it.characterId to checkNotNull(member.connection)}}.toMap()
                movements+=NpcStrategicMovement(session.generation,id,position.grid,destination,now,
                    moved.activeShips!!.values.filter {it.boats>0}.map {it.id},audience)
            } else if(pending?.destination==null && (pending==null || now>=pending.nextDecisionAt)) {
                val destination=target(galaxy,forces(),outfit,stocks)
                if(destination?.grid==position.grid) {
                    dockLocally(outfit,destination)
                } else {
                    val next=if(destination==null || !canDepart(stocks,outfit,galaxy))NpcStrategicState(now+21600) else NpcStrategicState(now+21600,destination.grid,now+3600)
                    stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(strategy=next)))
                }
            }
        }
        return stocks
    }
}
