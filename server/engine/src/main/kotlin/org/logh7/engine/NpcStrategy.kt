package org.logh7.engine

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max

/** Replacement strategic policy for uncommanded fleets; not offline character delegation. */
data class NpcStrategicState(val nextDecisionAt:Long,val destination:Int?=null,val arrivesAt:Long?=null,
    val awaitingDockSupply:Boolean=false) {
    init {
        require(nextDecisionAt in 0..0xffffffffL && (destination==null)==(arrivesAt==null))
        require(destination==null || destination in 0..4999 && arrivesAt!! in 0..nextDecisionAt)
    }
}
/**
 * A committed strategic warp or local sortie of an uncommanded fleet, or an offline captain's reserved warp
 * (outfit 0 and no units for a flagship without a deployed fleet; the flagship has no ship-unit ID).
 */
data class NpcStrategicMovement(val generation:Long,val outfit:Int,val source:Int,val destination:Int,
    val seconds:Long,val units:List<Int>,val recipients:Map<Int,String>) {
    init {require(generation>0 && outfit>=0 && source in 0..4999 && destination in 0..4999 &&
        (source!=destination || outfit>0 && units.isNotEmpty()) &&
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
    private fun canDefendLocally(galaxy: Galaxy, forces: Map<Int, List<GridForces.Force>>, outfit: OutfitStock,
        grid: Int): Boolean {
        val camp = Camp(outfit.country, false)
        val count = GridForces.mobileUnits(outfit)
        val local = forces[grid].orEmpty()
        if (count !in 1..GridEntryRules.MAX_UNITS_PER_CAMP.toLong() ||
            local.none { it.camp != camp && it.deployedShip } || hostile(forces, grid, camp) > count * 2) return false
        val state = GridForces.state(galaxy, grid, forces)
        // The docked outfit is already included in the camp total.
        val withoutOutfit = state.copy(presences = state.presences.map {
            if (it.camp == camp) it.copy(units = it.units - count.toInt()) else it
        })
        return GridEntryRules.evaluate(withoutOutfit, GridEntry(camp, count.toInt(), false)) == GridEntryVerdict.ALLOWED
    }

    private fun neighbors(grid:Int):List<Int> {
        val x=grid%100;val y=grid/100
        return (-1..1).flatMap {dy->(-1..1).mapNotNull {dx->
            if(dx==0 && dy==0 || x+dx !in 0..99 || y+dy !in 0..49)null else (y+dy)*100+x+dx
        }}
    }
    private fun steps(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,origin:Int,goal:Int,preferred:Int?):List<Int> {
        val camp=Camp(outfit.country,false);val strength=GridForces.mobileUnits(outfit)
        return neighbors(origin).filter {distance(it,goal)<distance(origin,goal) && hostile(forces,it,camp)<=strength*2 && allowed(galaxy,forces,outfit,it)}
            .sortedWith(compareBy<Int> {if(it==preferred)0 else 1}.thenBy {distance(it,goal)}.thenBy {it})
    }
    /** Hostile mobile units on [grid] against [camp]. */
    private fun hostile(forces:Map<Int,List<GridForces.Force>>,grid:Int,camp:Camp)=
        forces[grid].orEmpty().filter {it.camp!=camp}.sumOf {it.units}
    private data class DepotRoute(val base:GalaxyBase,val next:Int,val jumps:Int)
    private data class RouteVisit(val grid:Int,val first:Int,val jumps:Int,val estimatedTotal:Int)

    private fun home(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,stocks:PlanetStocks,
        spentJumps:Int=0,relief:Boolean=false,firstStep:Int?=null):DepotRoute? {
        val position=outfit.positionIn(galaxy)
        val origin=position.grid
        val tacticalBerths=if(position.baseId==0)outfit.activeShips?.values.orEmpty().mapNotNull {unit->
            unit.posture?.takeIf {unit.boats>0 && it.grid==origin && it.mode.requiresBase}?.base
        } else emptyList()
        fun stocked(base:GalaxyBase)=stocks.bases.getValue(base.id).supplies>0
        // Local docking must not move a ship already berthed at a different base in this grid.
        val depots=galaxy.bases.filter {b->stocks.bases[b.id]?.let {it.country==outfit.country && it.camp==0}==true &&
            (b.grid!=origin || tacticalBerths.all {it==b.id}) &&
            !TacticalContact.contested(forces[b.grid].orEmpty())}
            .sortedWith(compareBy<GalaxyBase> {stocks.bases.getValue(it.id).supplies}
                .thenByDescending {it.id}).associateBy {it.grid}
        if(depots.isEmpty())return null
        val local=depots[origin]?.let {DepotRoute(it,origin,0)}
        // Orbiting fleets can dock locally. An already docked fleet seeking relief needs a remote depot.
        if(local!=null && stocked(local.base) && (!relief || position.baseId==0))return local
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
        if(candidates.isNotEmpty() && beatable(origin) && !short && (position.baseId!=0 || home!=null)) {
            if(origin in candidates)return null
            // Revalidate a reserved route first, then try other enemies in distance order.
            val goals=candidates.sortedWith(compareBy<Int> {
                if(preferred!=null && distance(preferred,it)<distance(origin,it))0 else 1
            }.thenBy {distance(origin,it)}.thenBy {it})
            val firstHops=goals.asSequence().flatMap {steps(galaxy,forces,outfit,origin,it,preferred)}.distinct()
            for(grid in firstHops) {
                val next=OutfitPosition(grid)
                if(home==null)return next
                val projected=outfit.copy(position=next)
                val projectedStocks=stocks.copy(outfits=stocks.outfits+(outfit.id to projected))
                val projectedForces=GridForces.collect(galaxy,stocks.generation,players,projectedStocks,eliminated)
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
        if(canDepart(stocks,outfit,galaxy) || TacticalContact.contested(forces[position.grid].orEmpty()))return null
        return home(galaxy,forces,outfit,stocks,relief=true,firstStep=destination)?.takeIf {it.jumps>0}
    }

    private fun supplyGoal(unit:ActiveShipUnit)=minOf(OutfitSupplies.unitCapacity(unit),unit.boats*10)

    private fun needsDockSupply(outfit:OutfitStock):Boolean {
        val balances=OutfitSupplies.distribution(outfit)
        return outfit.activeShips!!.values.any {it.boats>0 && balances.getValue(it.id)<supplyGoal(it)}
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
            val amount=minOf(available,max(0,supplyGoal(unit)-balances.getValue(unit.id)))
            balances[unit.id]=balances.getValue(unit.id)+amount;available-=amount
        }
        if(departing)for(unit in live)balances[unit.id]=balances.getValue(unit.id)-unit.boats
        val inventory=if(depot==null)stocks else stocks.copy(bases=stocks.bases+(depot.baseId to depot.copy(supplies=available)))
        return inventory to OutfitSupplies.withBalances(outfit,balances)
    }
    fun advance(session:SessionState,galaxy:Galaxy,players:MutableMap<String,Navigator>,original:PlanetStocks,
        movements:MutableList<NpcStrategicMovement>,
        warehouseBlocked: (PlanetStocks, Int) -> Boolean = { _, _ -> false },
        startingClaims:List<OriginalBerthClaim> = emptyList()):PlanetStocks {
        if(session.ended!=null)return original
        val now=session.gameMillis/1000
        if(now>0xffffffffL-21600)return original
        var stocks=original
        val claimsByFleet=startingClaims.groupBy {it.berth()?.first?.fleetId}
        var forceCache:Map<Int,List<GridForces.Force>>?=null
        fun forces():Map<Int,List<GridForces.Force>> = forceCache ?: GridForces.collect(galaxy,stocks.generation,players.values,stocks,session.eliminated.keys).also {forceCache=it}
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
        val logisticsOffice = LogisticsOfficePolicy(galaxy)
        fun replenishCrew(source: PlanetStocks, outfit: OutfitStock, warehouse: OutfitWarehouse,
            mayAllocate: () -> Boolean): Pair<PlanetStocks, OutfitStock>? {
            var allocated = source
            val required = ReplacementBoats.crewRequired(outfit)
            var crew = Supplement.transferCrew(outfit, warehouse, required)
            if (crew.shortfall > 0) {
                if (!mayAllocate()) return null
                val base = source.bases.getValue(outfit.baseId)
                val baseReserve = warehouse.copy(units = UnitInventory(troops = base.units.troops), spareShips = SpareShipBoats())
                val allocation = Supplement.transferCrew(outfit, baseReserve, required - crew.moved.values.sumOf { it.toLong() })
                if (allocation.shortfall > 0) return null
                val staged = source.copy(warehouses = source.warehouses + (warehouse.id to warehouse))
                allocated = StockAssignment.applyTransfer(staged, outfit, UnitTransfer(troops = allocation.moved), 0)
                crew = Supplement.transferCrew(outfit, allocated.warehouses.getValue(warehouse.id), required)
            }
            val units = UnitInventory(outfit.units.ships, crew.onboard)
            val reserve = warehouse.copy(units = UnitInventory(warehouse.units.ships, crew.reserve))
            return allocated.copy(warehouses = allocated.warehouses + (reserve.id to reserve)) to outfit.copy(units = units)
        }
        fun reformAtHome(outfit: OutfitStock, lost: List<ActiveShipUnit>): PlanetStocks? {
            val warehouse = stocks.warehouses.getValue(outfit.warehouseId)
            val base = stocks.bases.getValue(outfit.baseId)
            val mayAllocate by lazy { !logisticsOffice.staffed(stocks, outfit.country, outfit.kind) }
            val empty = outfit.copy(activeShips = ActiveShipUnits(emptyList()),
                units = UnitInventory(troops = outfit.units.troops), supplies = 0)
            val opening = stocks.copy(outfits = stocks.outfits + (outfit.id to empty))
            val available = warehouse.units.ships.keys + warehouse.spareShips.values.keys
            for (kind in lost.sortedBy { it.id }.map { it.stack.kind }.distinct()) {
                try {
                    val reserve = available.filter { it.kind == kind }.minByOrNull { it.level }
                    val stack = reserve ?: base.units.ships.keys.filter { it.kind == kind }.minByOrNull { it.level } ?: continue
                    val transfer = UnitTransfer(ships = mapOf(stack to 1))
                    val staged = if (reserve != null) opening else {
                        if (!mayAllocate) continue
                        StockAssignment.applyTransfer(opening, empty, transfer, 0)
                    }
                    val commissioned = ReplacementBoats.reorganize(staged, outfit.id, transfer)
                    val replacement = commissioned.outfits.getValue(outfit.id)
                    if (!canDepart(commissioned, replacement, galaxy)) continue
                    val (supplied, crewed) = replenishCrew(commissioned, replacement,
                        commissioned.warehouses.getValue(warehouse.id)) { mayAllocate } ?: continue
                    return supplied.copy(outfits = supplied.outfits + (outfit.id to crewed))
                } catch (_: IllegalArgumentException) {
                    // Invalid capacity or an exhausted unit ID leaves the formation untouched.
                    continue
                }
            }
            return null
        }
        fun replaceBoatsAtHome(outfit: OutfitStock): OutfitStock {
            val berth = outfit.positionIn(galaxy)
            if (outfit.guard != null || berth.baseId != outfit.baseId || depot(stocks, outfit, galaxy) == null ||
                TacticalContact.contested(forces()[berth.grid].orEmpty())) return outfit
            if (warehouseBlocked(stocks, outfit.id)) return outfit
            val base = stocks.bases.getValue(berth.baseId)
            val warehouse = stocks.warehouses.getValue(outfit.warehouseId)
            val ships = checkNotNull(outfit.activeShips)
            val mayAllocate by lazy { !logisticsOffice.staffed(stocks, outfit.country, outfit.kind) }
            for (unit in ships.values) {
                val fullSize = UnitSpecifications.ships.single { it.kind == unit.stack.kind }.number
                if (unit.boats == 0 || unit.boats == fullSize) continue
                try {
                    var allocated = stocks
                    val restored = try {
                        ReplacementBoats.restoreUnit(warehouse, unit)
                    } catch (insufficient: IllegalArgumentException) {
                        if (!mayAllocate) continue
                        val reserve = base.units.ships.keys.filter { it.kind == unit.stack.kind }.minByOrNull { it.level }
                            ?: continue
                        allocated = StockAssignment.applyTransfer(stocks, outfit, UnitTransfer(ships = mapOf(reserve to 1)), 0)
                        ReplacementBoats.restoreUnit(allocated.warehouses.getValue(warehouse.id), unit)
                    }
                    val (remaining, ship) = restored
                    val replacement = outfit.copy(activeShips = ActiveShipUnits(ships.values.map { if (it.id == unit.id) ship else it }))
                    if (!canDepart(stocks, replacement, galaxy)) continue
                    val (supplied, crewed) = replenishCrew(allocated, replacement, remaining) { mayAllocate } ?: continue
                    stocks = supplied
                    return crewed
                } catch (_: IllegalArgumentException) {
                    // Keep every source intact when the combined allocation cannot fit.
                    continue
                }
            }
            return outfit
        }
        fun replenishAtBerth(outfit:OutfitStock):OutfitStock {
            val repaired=repairAtDepot(outfit)
            val (inventory,replenished)=checkNotNull(supplied(stocks,repaired,galaxy,departing=false))
            stocks=inventory
            val awaitingSupply=outfit.guard==null && depot(stocks,replenished,galaxy)!=null && needsDockSupply(replenished)
            return replenished.copy(strategy=NpcStrategicState(now+21600, awaitingDockSupply=awaitingSupply))
        }
        fun dockLocally(outfit:OutfitStock,destination:OutfitPosition) {
            val replenished=replenishAtBerth(outfit.copy(position=destination))
            val docked=replenished.copy(
                activeShips=ActiveShipUnits(replenished.activeShips!!.values.map {it.copy(tactical=null,motionCommander=0,posture=null)}))
            stocks=stocks.copy(outfits=stocks.outfits+(outfit.id to docked))
            forceCache=null
        }
        fun publishDeployment(moved: OutfitStock, source: Int, destination: Int) {
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
            movements+=NpcStrategicMovement(session.generation,moved.id,source,destination,now,
                moved.activeShips!!.values.filter {it.boats>0}.map {it.id},audience)
        }
        for(id in original.outfits.keys.sorted()) {
            var outfit=stocks.outfits.getValue(id)
            val position=outfit.positionIn(galaxy)
            val owner=stocks.bases[position.baseId]
            val lost = outfit.activeShips?.values?.takeIf { it.isNotEmpty() && it.all { ship -> ship.boats == 0 } }
            val home = stocks.bases.getValue(outfit.baseId)
            if (lost != null && outfit.country !in session.eliminated && outfit.guard == null &&
                outfit.kind in setOf(OutfitKind.FLEET, OutfitKind.PATROL) && TacticalAuthority.uncommanded(outfit, stocks) &&
                outfit.activeGround?.values.isNullOrEmpty() && stocks.strategic.transports.none { it.carrier == id } &&
                home.country == outfit.country && home.camp == 0) {
                val homePosition = OutfitPosition(galaxy.bases.single { it.id == home.baseId }.grid, home.baseId)
                val atHome = outfit.copy(position = homePosition)
                if (now >= (outfit.strategy?.nextDecisionAt ?: 0) &&
                    claimsByFleet[id].orEmpty().none { it.vacantFleet(atHome, stocks, galaxy, players) != null } &&
                    !warehouseBlocked(stocks, id) && !TacticalContact.contested(forces()[homePosition.grid].orEmpty())) {
                    val rebuilt = reformAtHome(atHome, lost)
                    if (rebuilt != null) {
                        stocks = rebuilt
                        outfit = stocks.outfits.getValue(id)
                        forceCache = null
                    }
                    stocks = stocks.copy(outfits = stocks.outfits + (id to outfit.copy(strategy = NpcStrategicState(now + 21600))))
                }
                continue
            }
            val permitted=outfit.country !in session.eliminated && eligible(outfit,stocks) &&
                (position.baseId==0 || owner?.let {it.country==outfit.country && it.camp==0}==true)
            if(!permitted) {
                if(outfit.strategy!=null)stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(strategy=null)))
                continue
            }
            if(claimsByFleet[id].orEmpty().any {it.vacantFleet(outfit,stocks,galaxy,players)!=null})continue
            val hasTacticalBerth=position.baseId==0 && outfit.activeShips!!.values.any {unit->
                unit.boats>0 && unit.posture?.let {it.grid==position.grid && it.mode.requiresBase}==true
            }
            val pending=outfit.strategy
            var awaitingSupply = position.baseId != 0 && outfit.guard == null &&
                pending?.awaitingDockSupply == true && needsDockSupply(outfit)
            if(pending?.destination!=null && now>=pending.arrivesAt!!) {
                val destination=pending.destination
                val normal=target(galaxy,forces(),outfit,stocks,players.values,session.eliminated.keys,destination)
                val relief=reliefRoute(galaxy,forces(),outfit,stocks,destination)
                val planned=relief?.let {OutfitPosition(it.next,if(it.jumps==1)it.base.id else 0)} ?: normal
                if(planned?.grid==position.grid) {
                    dockLocally(outfit,planned)
                    continue
                }
                if(hasTacticalBerth) {
                    stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(strategy=NpcStrategicState(now+21600))))
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
                    val strategy=if(next==null)NpcStrategicState(now+21600, awaitingDockSupply=awaitingSupply)
                        else NpcStrategicState(now+21600,next,now+3600,awaitingSupply)
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
                    stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(
                        strategy=NpcStrategicState(now+21600, awaitingDockSupply=awaitingSupply))))
                    continue
                }
                stocks=provision.first;outfit=provision.second
                var moved=outfit.copy(position=planned,strategy=NpcStrategicState(now+21600),
                    activeShips=ActiveShipUnits(outfit.activeShips!!.values.map {it.copy(tactical=null,motionCommander=0,
                        posture=null,attack=null,shot=null)}))
                if(planned.baseId!=0)moved=replenishAtBerth(moved)
                stocks=stocks.copy(outfits=stocks.outfits+(id to moved))
                forceCache=null // Later arrivals must see this move when checking the 300-unit cap.
                publishDeployment(moved, position.grid, destination)
            } else if(pending?.destination==null && (pending==null || now>=pending.nextDecisionAt)) {
                if (position.baseId != 0 && outfit.guard == null &&
                    canDefendLocally(galaxy, forces(), outfit, position.grid) &&
                    canDepart(stocks, outfit, galaxy) && !warehouseBlocked(stocks, id)) {
                    val (inventory, provisioned) = checkNotNull(supplied(stocks, outfit, galaxy, departing = false))
                    val deployed = provisioned.copy(position = OutfitPosition(position.grid),
                        strategy = NpcStrategicState(now + 21600),
                        activeShips = ActiveShipUnits(provisioned.activeShips!!.values.map {
                            it.copy(tactical = null, motionCommander = 0, posture = null)
                        }))
                    stocks = inventory.copy(outfits = inventory.outfits + (id to deployed))
                    forceCache = null
                    publishDeployment(deployed, position.grid, position.grid)
                    continue
                }
                if (position.baseId != 0) outfit = replaceBoatsAtHome(repairAtDepot(outfit))
                val destination=target(galaxy,forces(),outfit,stocks,players.values,session.eliminated.keys)
                val relief=reliefRoute(galaxy,forces(),outfit,stocks)
                if(relief==null && destination?.grid==position.grid) {
                    dockLocally(outfit,destination)
                } else {
                    val grid=if(hasTacticalBerth)null else relief?.next ?: destination?.grid?.takeIf {canDepart(stocks,outfit,galaxy)}
                    if (grid == null && awaitingSupply && (depot(stocks, outfit, galaxy)?.supplies ?: 0) > 0 &&
                        !TacticalContact.contested(forces()[position.grid].orEmpty()) && !warehouseBlocked(stocks, id)) {
                        val (inventory, replenished) = checkNotNull(supplied(stocks, outfit, galaxy, departing = false))
                        stocks = inventory
                        outfit = replenished
                        awaitingSupply = needsDockSupply(outfit)
                    }
                    val next=if(grid==null)NpcStrategicState(now+21600, awaitingDockSupply=awaitingSupply)
                        else NpcStrategicState(now+21600,grid,now+3600,awaitingSupply)
                    stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(strategy=next)))
                }
            }
        }
        return stocks
    }
}
