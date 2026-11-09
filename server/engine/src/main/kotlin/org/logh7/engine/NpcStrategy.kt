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
    private fun eligible(outfit:OutfitStock,stocks:PlanetStocks)=outfit.kind in setOf(OutfitKind.FLEET,OutfitKind.PATROL) &&
        TacticalAuthority.uncommanded(outfit,stocks) && outfit.activeGround?.values.orEmpty().none {it.order!=null} &&
        outfit.activeShips?.values?.any {it.boats>0}==true &&
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

    private fun home(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,stocks:PlanetStocks,
        spentJumps:Int=0,relief:Boolean=false,firstStep:Int?=null):DepotRoute? {
        val origin=outfit.positionIn(galaxy).grid
        fun stocked(base:GalaxyBase)=stocks.bases.getValue(base.id).supplies>0
        val depots=galaxy.bases.filter {b->stocks.bases[b.id]?.let {it.country==outfit.country && it.camp==0}==true &&
            !TacticalContact.contested(forces[b.grid].orEmpty())}
            .sortedWith(compareBy<GalaxyBase> {if(relief)stocks.bases.getValue(it.id).supplies else if(stocked(it))1 else 0}
                .thenByDescending {it.id}).associateBy {it.grid}
        if(depots.isEmpty())return null
        val local=depots[origin]?.let {DepotRoute(it,origin,0)}
        if(local!=null && stocked(local.base))return local
        val strength=GridForces.mobileUnits(outfit)
        if(strength !in 1..GridEntryRules.MAX_UNITS_PER_CAMP.toLong())return local
        val balances=OutfitSupplies.distribution(outfit)
        val live=outfit.activeShips!!.values.filter {it.boats>0}
        val jumpCost=live.sumOf {it.boats}
        val affordable=if(relief)minOf(live.minOf {OutfitSupplies.unitCapacity(it)/it.boats}.toLong(),
            (depots.values.maxOf {stocks.bases.getValue(it.id).supplies}.toLong()+live.sumOf {balances.getValue(it.id)})/jumpCost)
        else (live.minOf {balances.getValue(it.id)/it.boats}-spentJumps).toLong()
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
            // A prospective advance cannot use a return path beyond its remaining fuel.
            if(current.estimatedTotal>affordable && (relief || fallback!=null || spentJumps>0))return fallback
            depots[current.grid]?.let {
                val route=DepotRoute(it,current.first,current.jumps)
                if(relief) {
                    if(current.jumps>0 && stocks.bases.getValue(it.id).supplies>=jumpShortfall(live,balances,current.jumps))return route
                } else {
                    if(stocked(it) && current.jumps<=affordable)return route
                    if(fallback==null)fallback=route
                }
            }
            val jumps=current.jumps+1
            for(grid in neighbors(current.grid)) {
                if(current.jumps==0 && firstStep!=null && grid!=firstStep)continue
                if(jumps>=(best[grid] ?: Int.MAX_VALUE) || grid in blocked)continue
                if(hostile(forces,grid,camp)>(if(relief)0 else strength*2) ||
                    relief && forces[grid].orEmpty().any {it.camp!=camp && it.base} ||
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
        if(!eligible(outfit,stocks) || outfit.positionIn(galaxy).baseId!=0)null else home(galaxy,forces,outfit,stocks)?.jumps
    /**
     * Next adjacent grid: towards the nearest enemy it can take on (at most twice its own mobile units), or, when
     * every enemy outmatches it, a stronger enemy shares its grid or its fuel runs short, back towards a reachable own depot
     * (`evidence:guess`, 2026-11-04 replacement policy).
     */
    private fun target(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,stocks:PlanetStocks,
        players:Collection<Navigator>,eliminated:Set<CountryId>,preferred:Int?=null):OutfitPosition? {
        val position=outfit.positionIn(galaxy)
        val origin=position.grid
        val camp=Camp(outfit.country,false)
        val own=GridForces.mobileUnits(outfit)
        fun beatable(grid:Int)=hostile(forces,grid,camp)<=own*2
        val enemies=forces.entries.filter {(_,rows)->
            rows.any {it.camp!=camp && it.camp.country !in eliminated && (it.units>0 || it.base)}
        }.map {it.key}
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
        if(goal!=null && beatable(origin) && !short) {
            if(goal==origin)return null
            val next=step(galaxy,forces,outfit,origin,goal,preferred)?.let(::OutfitPosition)
            if(next!=null) {
                if(home==null)return next
                val projected=outfit.copy(position=next)
                val projectedStocks=stocks.copy(outfits=stocks.outfits+(outfit.id to projected))
                val projectedForces=GridForces.collect(galaxy,stocks.generation,players,projectedStocks)
                val returnRoute=home(galaxy,projectedForces,projected,projectedStocks,spentJumps=1)
                if(returnRoute!=null && jumpShortfall(live,balances,1+returnRoute.jumps)==0L)return next
            }
        }
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

    /** A safe depot can dispatch enough cargo for the shortest safe return route. */
    private fun reliefRoute(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,
        stocks:PlanetStocks,destination:Int?=null):DepotRoute? {
        val position=outfit.positionIn(galaxy)
        if(position.baseId!=0 || canDepart(stocks,outfit,galaxy) || TacticalContact.contested(forces[position.grid].orEmpty()))return null
        return home(galaxy,forces,outfit,stocks,relief=true,firstStep=destination)?.takeIf {it.jumps>0}
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
        movements:MutableList<NpcStrategicMovement>,
        warehouseBlocked: (PlanetStocks, Int) -> Boolean = { _, _ -> false }):PlanetStocks {
        if(session.ended!=null)return original
        val now=session.gameMillis/1000
        if(now>0xffffffffL-21600)return original
        var stocks=original
        var forceCache:Map<Int,List<GridForces.Force>>?=null
        fun forces():Map<Int,List<GridForces.Force>> = forceCache ?: GridForces.collect(galaxy,stocks.generation,players.values,stocks).also {forceCache=it}
        fun repairAtDepot(outfit:OutfitStock):OutfitStock {
            val ships=outfit.activeShips!!
            if(outfit.guard!=null || ships.values.none {it.damaged>0})return outfit
            val depot=depot(stocks,outfit,galaxy) ?: return outfit
            if(depot.supplies==0 || TacticalContact.contested(forces()[outfit.positionIn(galaxy).grid].orEmpty()))return outfit
            // Keep enough cargo in the ships and depot to launch and return before spending on repairs.
            val reserved=jumpShortfall(ships.values,OutfitSupplies.distribution(outfit),2)
            var available=(depot.supplies-reserved).coerceAtLeast(0).toInt()
            if(available==0)return outfit
            val before=available
            val repaired=ships.values.map {unit->
                if(unit.damaged==0)return@map unit
                val cost=TacticalSupportOperations.repairCost(unit.stack.kind).coerceAtLeast(1)
                val count=minOf(unit.damaged,available/cost)
                available-=count*cost
                unit.copy(damaged=unit.damaged-count)
            }
            if(available==before)return outfit
            stocks=stocks.copy(bases=stocks.bases+(depot.baseId to depot.copy(supplies=depot.supplies-(before-available))))
            return outfit.copy(activeShips=ActiveShipUnits(repaired))
        }
        fun replaceBoatsAtHome(outfit: OutfitStock): OutfitStock {
            val berth = outfit.positionIn(galaxy)
            if (outfit.guard != null || berth.baseId != outfit.baseId || depot(stocks, outfit, galaxy) == null ||
                TacticalContact.contested(forces()[berth.grid].orEmpty())) return outfit
            val warehouse = stocks.warehouses.getValue(outfit.warehouseId)
            if (warehouse.units.ships.isEmpty() && warehouse.spareShips.values.isEmpty() ||
                warehouseBlocked(stocks, outfit.id)) return outfit
            val ships = checkNotNull(outfit.activeShips)
            val crewKinds = UnitSpecifications.crews.filter { it.power == outfit.shipStyle.nativePower && it.type == 3 }
                .map { it.kind }.toSet()
            val onboardCrew = outfit.units.troops.filterKeys { it.kind in crewKinds }.values.sumOf { it.toLong() }
            for (unit in ships.values) {
                if (unit.boats == 0) continue
                val (remaining, restored) = try {
                    ReplacementBoats.restoreUnit(warehouse, unit)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                if (restored == unit) continue
                val replacement = outfit.copy(activeShips = ActiveShipUnits(ships.values.map { if (it.id == unit.id) restored else it }))
                if (ReplacementBoats.crewRequired(replacement) > onboardCrew || !canDepart(stocks, replacement, galaxy)) continue
                stocks = stocks.copy(warehouses = stocks.warehouses + (warehouse.id to remaining))
                return replacement
            }
            return outfit
        }
        fun dockLocally(outfit:OutfitStock,destination:OutfitPosition) {
            val repaired=repairAtDepot(outfit.copy(position=destination))
            val (inventory,replenished)=checkNotNull(supplied(stocks,repaired,galaxy,departing=false))
            val docked=replenished.copy(strategy=NpcStrategicState(now+21600),
                activeShips=ActiveShipUnits(replenished.activeShips!!.values.map {it.copy(tactical=null,motionCommander=0,posture=null)}))
            stocks=inventory.copy(outfits=inventory.outfits+(outfit.id to docked))
            forceCache=null
        }
        for(id in original.outfits.keys.sorted()) {
            var outfit=stocks.outfits.getValue(id)
            val position=outfit.positionIn(galaxy)
            val owner=stocks.bases[position.baseId]
            val permitted=outfit.country !in session.eliminated && eligible(outfit,stocks) &&
                (position.baseId==0 || owner?.let {it.country==outfit.country && it.camp==0}==true)
            if(!permitted) {
                if(outfit.strategy!=null)stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(strategy=null)))
                continue
            }
            val pending=outfit.strategy
            if(pending?.destination!=null && now>=pending.arrivesAt!!) {
                val destination=pending.destination
                val normal=target(galaxy,forces(),outfit,stocks,players.values,session.eliminated.keys,destination)
                val relief=reliefRoute(galaxy,forces(),outfit,stocks,destination)
                val planned=relief?.let {OutfitPosition(it.next,if(it.jumps==1)it.base.id else 0)} ?: normal
                if(planned?.grid==position.grid) {
                    dockLocally(outfit,planned)
                    continue
                }
                // Re-evaluate source hostility as well as destination capacity after earlier arrivals.
                if(distance(position.grid,destination)!=1 || planned?.grid!=destination || !allowed(galaxy,forces(),outfit,destination) ||
                    position.baseId==0 && relief==null && !canDepart(stocks,outfit,galaxy)) {
                    val contestedDepot=TacticalContact.contested(forces()[destination].orEmpty())
                    val unavailableDepot=galaxy.bases.any {base->base.grid==destination &&
                        stocks.bases[base.id]?.let {contestedDepot || it.country!=outfit.country || it.camp!=0 || it.supplies==0}==true}
                    val reroute=if(unavailableDepot && position.baseId==0 && planned!=null)
                        home(galaxy,forces(),outfit,stocks)?.takeIf {it.next==planned.grid && it.next!=destination}
                    else null
                    val next=if(canDepart(stocks,outfit,galaxy))reroute?.next
                        else reliefRoute(galaxy,forces(),outfit,stocks)?.next
                    val strategy=if(next==null)NpcStrategicState(now+21600)
                        else NpcStrategicState(now+21600,next,now+3600)
                    stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(strategy=strategy)))
                    continue
                }
                if(position.baseId!=0)outfit=repairAtDepot(outfit)
                val provision=if(relief==null)supplied(stocks,outfit,galaxy) else {
                    val depot=stocks.bases.getValue(relief.base.id)
                    val ships=outfit.activeShips!!.values
                    val balances=OutfitSupplies.distribution(outfit)
                    val cost=jumpShortfall(ships,balances,relief.jumps)
                    val cargo=ships.associate {it.id to max(balances.getValue(it.id),it.boats*relief.jumps)-it.boats}
                    stocks.copy(bases=stocks.bases+(depot.baseId to depot.copy(supplies=depot.supplies-cost.toInt()))) to
                        OutfitSupplies.withBalances(outfit,cargo)
                }
                if(provision==null) {
                    stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(strategy=NpcStrategicState(now+21600))))
                    continue
                }
                stocks=provision.first;outfit=provision.second
                var moved=outfit.copy(position=planned,strategy=NpcStrategicState(now+21600),
                    activeShips=ActiveShipUnits(outfit.activeShips!!.values.map {it.copy(tactical=null,motionCommander=0,
                        posture=null,attack=null,shot=null)}))
                if(planned.baseId!=0) {
                    moved=repairAtDepot(moved)
                    val replenished=checkNotNull(supplied(stocks,moved,galaxy,departing=false))
                    stocks=replenished.first;moved=replenished.second
                }
                stocks=stocks.copy(outfits=stocks.outfits+(id to moved))
                forceCache=null // Later arrivals must see this move when checking the 300-unit cap.
                for((account,player) in players.toMap())if(player.grid==destination && !player.cpPaused &&
                    player.generation==session.generation && player.country !in session.eliminated &&
                    session.participants[account]?.country !in session.eliminated && !BattleDeath.deceased(stocks,player.characterId) &&
                    (player.hull?.woundedUntil ?: 0)<=now && TacticalContact.active(galaxy,player,players.values,stocks,session.eliminated.keys)) {
                    players[account]=player.copy(cpPaused=true,cpRecoveryAt=now,
                        commandRange=TacticalCommandRange(now,command=50),motion=null,retreat=null,charge=null,
                        attack=null,fire=player.fire?.copy(pending=null))
                }
                val audience=session.participants.mapNotNull {(account,member)->players[account]?.takeIf {
                    member.online && it.generation==session.generation}?.let {it.characterId to checkNotNull(member.connection)}}.toMap()
                movements+=NpcStrategicMovement(session.generation,id,position.grid,destination,now,
                    moved.activeShips!!.values.filter {it.boats>0}.map {it.id},audience)
            } else if(pending?.destination==null && (pending==null || now>=pending.nextDecisionAt)) {
                if (position.baseId != 0) outfit = replaceBoatsAtHome(repairAtDepot(outfit))
                val destination=target(galaxy,forces(),outfit,stocks,players.values,session.eliminated.keys)
                val relief=reliefRoute(galaxy,forces(),outfit,stocks)
                if(relief==null && destination?.grid==position.grid) {
                    dockLocally(outfit,destination)
                } else {
                    val grid=relief?.next ?: destination?.grid?.takeIf {canDepart(stocks,outfit,galaxy)}
                    val next=if(grid==null)NpcStrategicState(now+21600) else NpcStrategicState(now+21600,grid,now+3600)
                    stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(strategy=next)))
                }
            }
        }
        return stocks
    }
}
