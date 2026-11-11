package org.logh7.engine

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max

/** Replacement strategic policy for uncommanded fleets; not offline character delegation. */
data class NpcStrategicState(val nextDecisionAt:Long,val destination:Int?=null,val arrivesAt:Long?=null,
    val awaitingDockSupply:Boolean=false,val expeditionBase:Int?=null) {
    init {
        require(nextDecisionAt in 0..0xffffffffL && (destination==null)==(arrivesAt==null))
        require(destination==null || destination in 0..4999 && arrivesAt!! in 0..nextDecisionAt)
        require(expeditionBase==null || expeditionBase in 1..65535)
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
    private fun eligible(outfit:OutfitStock,stocks:PlanetStocks,allowAutonomousLanding:Boolean=false)=
        outfit.kind in setOf(OutfitKind.FLEET,OutfitKind.PATROL) && TacticalAuthority.uncommanded(outfit,stocks) &&
        outfit.activeGround?.values.orEmpty().none {troop->troop.order?.let {!allowAutonomousLanding || it.character!=0}==true} &&
        outfit.activeShips?.values?.any {it.boats>0}==true &&
        outfit.activeShips.values.none {ship->ship.support!=null || ship.attack!=null || ship.shot!=null ||
            ship.posture?.order?.let {!allowAutonomousLanding || it.character!=0}==true || ship.tactical?.retreat!=null}

    private fun securedExpedition(outfit:OutfitStock,stocks:PlanetStocks):Boolean =
        outfit.strategy?.expeditionBase?.let {stocks.bases[it]}
            ?.let {it.country==outfit.country && it.camp==0}==true

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
    /** Ground garrisons and docked units still count for entry and an assault, but cannot intercept a passing fleet. */
    private fun hostileShips(forces:Map<Int,List<GridForces.Force>>,grid:Int,camp:Camp)=
        forces[grid].orEmpty().filter {it.camp!=camp}.sumOf {it.deployedShips}
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
    private data class Plan(val position:OutfitPosition,val missionJumps:Int=0,val partialDepotLoad:Boolean=false,
        val infantryBerth:Boolean=false,val waitingForSupplies:Boolean=false)

    /** Find a strategic route and budget the entire leg before consuming its first jump. */
    private fun missionRoute(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,
        stocks:PlanetStocks,goal:GalaxyBase,preferred:Int?,returning:Boolean=false,reserveInPort:Boolean=false,
        producesSupplies:(PlanetStock)->Boolean={false}):Plan? {
        val position=outfit.positionIn(galaxy)
        val origin=position.grid
        val camp=Camp(outfit.country,false)
        val strength=GridForces.mobileUnits(outfit)
        if(strength !in 1..GridEntryRules.MAX_UNITS_PER_CAMP.toLong())return null
        val entry=GridEntry(camp,strength.toInt(),false)
        val passable=IntArray(5000)
        fun traversable(grid:Int):Boolean {
            if(grid==origin)return true
            if(passable[grid]==0) {
                val resistance=if(grid==goal.grid)hostile(forces,grid,camp) else hostileShips(forces,grid,camp)
                val allowed=resistance<=strength*2 &&
                    GridEntryRules.evaluate(GridForces.state(galaxy,grid,forces),entry)==GridEntryVerdict.ALLOWED
                passable[grid]=if(allowed)1 else -1
            }
            return passable[grid]==1
        }
        if(!traversable(goal.grid))return null
        fun distances(start:Int,first:IntArray?=null,limit:Int=4999):IntArray {
            val distances=IntArray(5000){-1}
            val queue=ArrayDeque<Int>()
            distances[start]=0
            queue.addLast(start)
            while(queue.isNotEmpty()) {
                val at=queue.removeFirst()
                val jumps=distances[at]+1
                if(jumps>limit)continue
                val neighbors=neighbors(at)
                val next=if(at==origin && preferred in neighbors)listOf(checkNotNull(preferred))+neighbors.filter {it!=preferred}
                    else neighbors
                for(grid in next) {
                    if(distances[grid]>=0 || !traversable(grid))continue
                    distances[grid]=jumps
                    if(first!=null)first[grid]=if(at==start)grid else first[at]
                    queue.addLast(grid)
                }
            }
            return distances
        }
        val toGoal=distances(goal.grid)
        val forward=toGoal[origin]
        if(forward<=0)return null
        val depots=galaxy.bases.filter {base ->
            toGoal[base.grid]>=0 && stocks.bases[base.id]?.let {it.country==outfit.country && it.camp==0}==true &&
                forces[base.grid].orEmpty().none {it.camp!=camp && it.combatPresence}
        }
        val live=outfit.activeShips!!.values.filter {it.boats>0}
        val balances=OutfitSupplies.distribution(outfit)
        val fuel=depot(stocks,outfit,galaxy)?.supplies?.toLong() ?: 0L
        fun canCarry(jumps:Int)=live.all {
            maxOf(balances.getValue(it.id),OutfitSupplies.unitCapacity(it)).toLong()>=it.boats.toLong()*jumps
        }
        fun canPay(jumps:Int,available:Long=fuel)=canCarry(jumps) && jumpShortfall(live,balances,jumps)<=available
        val returnJumps=if(returning)0 else depots.minOfOrNull {toGoal[it.grid]} ?: return null
        val roundTrip=forward+returnJumps
        if(canPay(roundTrip)) {
            val next=neighbors(origin).filter {toGoal[it]==forward-1}
                .minWithOrNull(compareBy<Int> {if(it==preferred)0 else 1}.thenBy {it}) ?: return null
            val arrivalBase=if(returning && next==goal.grid)goal.id else 0
            return Plan(OutfitPosition(next,arrivalBase),roundTrip)
        }

        // A different friendly berth in this system can fund the same route without a warp.
        val localBerths=depots.asSequence().filter {it.grid==origin}
            .map {it to stocks.bases.getValue(it.id).supplies.toLong()}
            .filter {(_,available)->available>0}
            .sortedWith(compareByDescending<Pair<GalaxyBase,Long>> {it.second}.thenBy {it.first.id})
            .toList()
        val alternatives=localBerths.filter {(berth,available)->berth.id!=position.baseId && available>fuel}
        alternatives.firstOrNull {(_,available)->canPay(roundTrip,available)}?.let { (berth,_) ->
            return Plan(OutfitPosition(origin,berth.id),roundTrip)
        }
        val combined=localBerths.sumOf {it.second}
        fun collectLocally(jumps:Int):Plan? = localBerths.firstOrNull()?.first
            ?.let {Plan(OutfitPosition(origin,it.id),jumps,partialDepotLoad=true)}
        fun reserve(jumps:Int):Plan? {
            if(!reserveInPort || position.baseId==0 || !canCarry(jumps))return null
            collectLocally(jumps)?.let {return it.copy(waitingForSupplies=true)}
            // Keep a productive berth when real stock has just been loaded or consumed.
            return if(depots.any {it.grid==origin && producesSupplies(stocks.bases.getValue(it.id))})
                Plan(position,jumps,partialDepotLoad=true,waitingForSupplies=true) else null
        }
        fun pooled(jumps:Int):Plan? = if(canPay(jumps,combined))collectLocally(jumps) else null

        // A staging depot must shorten the route. An underway expedition can also finish
        // a funded leg to a safe port that can replenish its temporarily empty stock.
        fun stocked(base:GalaxyBase)=stocks.bases.getValue(base.id).supplies>0
        val closer=depots.filter {base->toGoal[base.grid]<forward &&
            (stocked(base) || position.baseId==0 && (base.grid==preferred ||
                outfit.strategy?.expeditionBase!=null && producesSupplies(stocks.bases.getValue(base.id))))}
        if(closer.isEmpty())return pooled(roundTrip) ?: reserve(roundTrip)
        val first=IntArray(5000)
        val fromOrigin=distances(origin,first,live.minOf {maxOf(balances.getValue(it.id),OutfitSupplies.unitCapacity(it))/it.boats})
        val stages=closer.filter {fromOrigin[it.grid]>0}
            .sortedWith(compareByDescending<GalaxyBase>(::stocked)
                .thenBy {toGoal[it.grid]}.thenBy {fromOrigin[it.grid]}.thenBy {it.id})
        stages.firstOrNull {canPay(fromOrigin[it.grid])}?.let {stage->
            val next=first[stage.grid]
            return Plan(OutfitPosition(next,if(next==stage.grid)stage.id else 0),fromOrigin[stage.grid])
        }
        for((berth,available) in alternatives) {
            val stage=stages.firstOrNull {canPay(fromOrigin[it.grid],available)} ?: continue
            return Plan(OutfitPosition(origin,berth.id),fromOrigin[stage.grid])
        }
        return pooled(roundTrip) ?:
            stages.firstOrNull {canPay(fromOrigin[it.grid],combined)}?.let {collectLocally(fromOrigin[it.grid])} ?:
            reserve(minOf(roundTrip,stages.minOfOrNull {fromOrigin[it.grid]} ?: roundTrip))
    }

    /** Reserve cargo for the active leg and keep the fallback to a safe depot. */
    fun returnDistance(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,stocks:PlanetStocks,
        eliminated:Set<CountryId> = emptySet()):Int? {
        val position=outfit.positionIn(galaxy)
        val current=outfit.strategy
        // The carrier cannot warp during landing, but escorts still need to protect the fleet's fuel.
        if(position.baseId!=0 || !eligible(outfit,stocks,allowAutonomousLanding=current?.expeditionBase!=null))return null
        val fallback=home(galaxy,forces,outfit,stocks)?.jumps
        if(current==null)return fallback
        if(current.expeditionBase==null)return fallback
        val goal=NpcOccupation.goal(outfit,stocks,galaxy,eliminated)?.takeIf {
            it.id==current.expeditionBase && it.grid!=position.grid && NpcOccupation.hasCarrier(outfit,it,stocks)
        } ?: return fallback
        val leg=missionRoute(galaxy,forces,outfit,stocks,goal,current.destination) ?: return fallback
        return maxOf(fallback ?: 0,leg.missionJumps)
    }
    /**
     * Next adjacent grid: towards the nearest enemy it can take on (at most twice its own mobile units), or, when
     * every enemy outmatches it, a stronger enemy shares its grid or its fuel runs short, back towards a reachable own depot
     * (`evidence:guess`, 2026-11-04 replacement policy).
     */
    private fun target(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,stocks:PlanetStocks,
        players:Collection<Navigator>,eliminated:Set<CountryId>,preferred:Int?=null,mayReserve:Boolean=false,
        producesSupplies:(PlanetStock)->Boolean):Plan? {
        val position=outfit.positionIn(galaxy)
        val origin=position.grid
        val camp=Camp(outfit.country,false)
        val own=GridForces.mobileUnits(outfit)
        fun beatable(grid:Int)=hostile(forces,grid,camp)<=own*2
        val enemies=forces.entries.filter {(_,rows)->
            rows.any {it.camp!=camp && it.camp.country !in eliminated && (it.units>0 || it.base)}
        }.map {it.key}
        val candidates=enemies.filter(::beatable)
        val nationalGoal=NpcOccupation.goal(outfit,stocks,galaxy,eliminated)
        val objective=nationalGoal?.takeIf {NpcOccupation.hasCarrier(outfit,it,stocks)}
        if(objective!=null && hostileShips(forces,origin,camp)<=own*2) {
            if(objective.grid==origin && position.baseId==0)return null
            if(objective.grid in candidates && objective.grid!=origin)
                missionRoute(galaxy,forces,outfit,stocks,objective,preferred,
                    reserveInPort=mayReserve && outfit.strategy?.expeditionBase==objective.id,producesSupplies=producesSupplies)?.let {return it}
        }
        val homeStock=stocks.bases[outfit.baseId]
        val needsTroops=nationalGoal!=null && outfit.activeGround?.values.orEmpty().none {it.base==nationalGoal.id}
        val rearm=(needsTroops || securedExpedition(outfit,stocks)) && objective==null && outfit.guard==null &&
            homeStock?.let {it.country==outfit.country && it.camp==0}==true &&
            outfit.activeShips!!.values.any {it.boats>0 && GroundTransfer.capacity(it.stack.kind)>0}
        if(rearm) {
            val homeBase=galaxy.bases.single {it.id==outfit.baseId}
            val safe=forces[homeBase.grid].orEmpty().none {it.camp!=camp && it.combatPresence}
            if(safe) {
                if(nationalGoal!=null && outfit.strategy?.expeditionBase==nationalGoal.id && origin==homeBase.grid &&
                    !LogisticsOfficePolicy(galaxy).staffed(stocks,outfit.country,outfit.kind)) {
                    val berth=galaxy.bases.asSequence().filter {it.grid==origin && it.id!=position.baseId}
                        .filter {candidate->stocks.bases[candidate.id]?.let {base->
                            base.country==outfit.country && base.camp==0 &&
                                base.units.troops.keys.any {NpcOccupation.canLand(it.kind,outfit,nationalGoal)}
                        }==true}
                        .minByOrNull {it.id}
                    if(berth!=null)return Plan(OutfitPosition(origin,berth.id),infantryBerth=true)
                }
                if(position.baseId==homeBase.id)return null
                if(origin==homeBase.grid)return Plan(OutfitPosition(origin,homeBase.id))
                missionRoute(galaxy,forces,outfit,stocks,homeBase,preferred,returning=true,
                    reserveInPort=mayReserve && outfit.strategy?.expeditionBase!=null,
                    producesSupplies=producesSupplies)?.let {return it}
            }
        }
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
        if(objective==null && !rearm && candidates.isNotEmpty() && beatable(origin) && !short && (position.baseId!=0 || home!=null)) {
            if(origin in candidates)return null
            val goals=candidates.sortedWith(compareBy<Int> {
                if(preferred!=null && distance(preferred,it)<distance(origin,it))0 else 1
            }.thenBy {distance(origin,it)}.thenBy {it})
            val firstHops=goals.asSequence().flatMap {steps(galaxy,forces,outfit,origin,it,preferred)}.distinct()
            for(grid in firstHops) {
                val next=OutfitPosition(grid)
                if(home==null)return Plan(next)
                val projected=outfit.copy(position=next)
                val projectedStocks=stocks.copy(outfits=stocks.outfits+(outfit.id to projected))
                val projectedForces=GridForces.collect(galaxy,stocks.generation,players,projectedStocks,eliminated)
                val returnRoute=home(galaxy,projectedForces,projected,projectedStocks,spentJumps=1)
                if(returnRoute!=null && jumpShortfall(live,balances,1+returnRoute.jumps)==0L)return Plan(next)
            }
        }
        if(home==null)return null
        // A returning expedition can wait for a route to reopen without spending its remaining fuel.
        if(rearm && outfit.strategy?.expeditionBase!=null && jumpShortfall(live,balances,home.jumps)>0L)return null
        return Plan(OutfitPosition(home.next,if(home.next==home.base.grid)home.base.id else 0))
    }
    private fun depot(stocks:PlanetStocks,outfit:OutfitStock,galaxy:Galaxy)=
        stocks.bases[outfit.positionIn(galaxy).baseId]?.takeIf {it.country==outfit.country && it.camp==0}

    private fun jumpShortfall(ships:List<ActiveShipUnit>,balances:Map<Int,Int>,jumps:Int)=
        ships.sumOf {max(0,it.boats*jumps-balances.getValue(it.id)).toLong()}

    /** Redistribute carried fuel in port so every surviving unit can make the same jumps. */
    private fun balanceFuelInPort(outfit:OutfitStock):OutfitStock {
        val ships=outfit.activeShips!!.values.filter {it.boats>0}
        val balances=OutfitSupplies.distribution(outfit)
        val carried=ships.sumOf {balances.getValue(it.id)}
        val boats=ships.sumOf {it.boats}
        val capacityJumps=ships.minOf {OutfitSupplies.unitCapacity(it)/it.boats}
        val jumps=minOf(carried/boats,capacityJumps)
        // Keep the fraction above the shared minimum usable for the next jump.
        val shared=ships.associate {ship->
            val minimum=ship.boats*jumps
            val current=balances.getValue(ship.id)
            val amount=if(jumps<capacityJumps)current.coerceIn(minimum,minimum+ship.boats) else max(current,minimum)
            ship.id to amount
        }.toMutableMap()
        var remainder=carried.toLong()-shared.values.sumOf(Int::toLong)
        for(ship in ships) {
            if(remainder==0L)break
            val current=shared.getValue(ship.id)
            val minimum=ship.boats*jumps
            val adjustment=remainder.coerceIn((minimum-current).toLong(),max(0,minimum+ship.boats-current).toLong())
            shared[ship.id]=current+adjustment.toInt()
            remainder-=adjustment
        }
        check(remainder==0L)
        if(shared.all {(id,amount)->balances.getValue(id)==amount})return outfit
        return OutfitSupplies.withBalances(outfit,balances+shared)
    }

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

    private fun supplied(stocks:PlanetStocks,outfit:OutfitStock,galaxy:Galaxy,departing:Boolean=true,
        missionJumps:Int=0,allowPartialLoad:Boolean=false):Pair<PlanetStocks,OutfitStock>? {
        val live=outfit.activeShips!!.values.filter {it.boats>0}
        val balances=OutfitSupplies.distribution(outfit).toMutableMap()
        val depot=depot(stocks,outfit,galaxy)
        var available=depot?.supplies ?: 0
        // Every ship launching from a depot carries enough for the next jump and a return.
        val jumps=maxOf(missionJumps,if(!departing)0 else if(depot==null)1 else 2)
        if(live.any {it.boats.toLong()*jumps>maxOf(balances.getValue(it.id),OutfitSupplies.unitCapacity(it))})return null
        if((departing || !allowPartialLoad) && jumpShortfall(live,balances,jumps)>available)return null
        for(unit in live) {
            val amount=minOf(available,max(0,unit.boats*jumps-balances.getValue(unit.id)))
            balances[unit.id]=balances.getValue(unit.id)+amount;available-=amount
        }
        // Carry several future jumps where the real depot and surviving cargo permit it.
        if(departing || missionJumps==0)for(unit in live) {
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
        startingClaims:List<OriginalBerthClaim> = emptyList(),
        infantryProduction:(Int,CountryId)->List<Int> = { _, _ -> emptyList() },
        producesSupplies:(PlanetStock)->Boolean = {false}):PlanetStocks {
        if(session.ended!=null)return original
        val now=session.gameMillis/1000
        if(now>0xffffffffL-21600)return original
        var stocks=original
        val logisticsOffice=LogisticsOfficePolicy(galaxy)
        val claimsByFleet=startingClaims.groupBy {it.berth()?.first?.fleetId}
        var forceCache:Map<Int,List<GridForces.Force>>?=null
        fun forces():Map<Int,List<GridForces.Force>> = forceCache ?: GridForces.collect(galaxy,stocks.generation,players.values,stocks,session.eliminated.keys).also {forceCache=it}
        fun setStrategy(outfit:OutfitStock,strategy:NpcStrategicState?) {
            stocks=stocks.copy(outfits=stocks.outfits+(outfit.id to outfit.copy(strategy=strategy)))
        }
        // Pick a reachable expedition before the production day so its ships can hold their fuel.
        countryLoop@ for(country in listOf(Power.EMPIRE.country,Power.ALLIANCE.country)) {
            val enemy=NpcOccupation.expeditionEnemy(country,stocks,galaxy,session.eliminated.keys)
            val assigned=stocks.outfits.values.filter {it.country==country && it.strategy?.expeditionBase!=null}
            val retained=assigned.firstOrNull {fleet->
                enemy!=null && (NpcOccupation.goal(fleet,stocks,galaxy,session.eliminated.keys)!=null ||
                    securedExpedition(fleet,stocks) && TacticalAuthority.uncommanded(fleet,stocks)) &&
                    fleet.activeShips?.values?.any {it.boats>0}==true &&
                    stocks.bases[fleet.baseId]?.let {it.country==country && it.camp==0}==true &&
                    claimsByFleet[fleet.id].orEmpty().none {it.vacantFleet(fleet,stocks,galaxy,players)!=null}
            }?.let {fleet->
                val position=fleet.positionIn(galaxy)
                if(checkNotNull(fleet.strategy).nextDecisionAt>now || depot(stocks,fleet,galaxy)==null ||
                    !eligible(fleet,stocks) || warehouseBlocked(stocks,fleet.id) ||
                    TacticalContact.contested(forces()[position.grid].orEmpty()))fleet
                else balanceFuelInPort(fleet).also {balanced->
                    if(balanced!=fleet)stocks=stocks.copy(outfits=stocks.outfits+(fleet.id to balanced))
                }
            }
            for(fleet in assigned)if(fleet.id!=retained?.id)setStrategy(fleet,NpcStrategicState(now))
            if(enemy==null)continue
            val secured=retained?.let {securedExpedition(it,stocks)}==true
            if(secured && retained.positionIn(galaxy).baseId!=retained.baseId)continue
            if(retained!=null && !secured) {
                val current=checkNotNull(NpcOccupation.goal(retained,stocks,galaxy,session.eliminated.keys))
                if(depot(stocks,retained,galaxy)==null ||
                    checkNotNull(retained.strategy).nextDecisionAt>now || !NpcOccupation.hasCarrier(retained,current,stocks) ||
                    missionRoute(galaxy,forces(),retained,stocks,current,null,reserveInPort=true,
                        producesSupplies=producesSupplies)!=null)continue
            }
            val garrisons=stocks.outfits.values.asSequence().filter {it.country==enemy}
                .flatMap {it.activeGround?.values.orEmpty().asSequence()}.filter {it.base!=0}
                .groupingBy {it.base}.eachCount()
            val targets=galaxy.bases.filter {stocks.bases[it.id]?.let {base->base.country==enemy && base.camp==0}==true}
            val canAllocate=!logisticsOffice.staffed(stocks,country,OutfitKind.FLEET)
            val choices=stocks.outfits.values.asSequence().filter {fleet->
                (retained==null || fleet.id==retained.id) && fleet.country==country &&
                    fleet.kind==OutfitKind.FLEET && fleet.guard==null && eligible(fleet,stocks) &&
                    (retained!=null || fleet.positionIn(galaxy).baseId==fleet.baseId) && depot(stocks,fleet,galaxy)!=null &&
                    (fleet.strategy?.nextDecisionAt ?: 0)<=now &&
                    (secured || canDepart(stocks,fleet,galaxy)) &&
                    claimsByFleet[fleet.id].orEmpty().none {it.vacantFleet(fleet,stocks,galaxy,players)!=null} &&
                    !warehouseBlocked(stocks,fleet.id)
            }.flatMap {fleet->
                val grid=fleet.positionIn(galaxy).grid
                if(TacticalContact.contested(forces()[grid].orEmpty()))return@flatMap emptySequence()
                val baseKinds=if(canAllocate)infantryProduction(fleet.baseId,country)+
                    stocks.bases.getValue(fleet.baseId).units.troops.keys.map {it.kind} else emptyList()
                val depotKinds=if(canAllocate && secured)galaxy.bases.asSequence().filter {it.grid==grid && it.id!=fleet.baseId}
                    .map {stocks.bases.getValue(it.id)}.filter {it.country==country && it.camp==0}
                    .flatMap {it.units.troops.keys.asSequence().map {stack->stack.kind}}.toList() else emptyList()
                val kinds=baseKinds+depotKinds+stocks.warehouses.getValue(fleet.warehouseId).units.troops.keys.map {it.kind}+
                    fleet.activeGround?.values.orEmpty().filter {it.base==0}.map {it.stack.kind}
                targets.asSequence().filter {it.grid!=grid &&
                    (if(retained==null || secured) kinds.any {kind->NpcOccupation.canLand(kind,fleet,it)}
                    else NpcOccupation.hasCarrier(fleet,it,stocks))}
                    .map { Triple(fleet,it,distance(grid,it.grid)) }
            }.sortedWith(compareBy<Triple<OutfitStock,GalaxyBase,Int>> {garrisons[it.second.id] ?: 0}
                .thenBy {it.third}.thenBy {it.first.id}.thenBy {it.second.id})
            val productiveHome=retained?.takeIf {secured}?.let {fleet->
                val grid=fleet.positionIn(galaxy).grid
                galaxy.bases.any {base->base.grid==grid && stocks.bases[base.id]?.let {
                    it.country==country && it.camp==0 && producesSupplies(it)
                }==true}
            }==true
            var waiting:Pair<GalaxyBase,Int>?=null
            for((fleet,base) in choices) {
                val route=missionRoute(galaxy,forces(),fleet,stocks,base,null,reserveInPort=productiveHome,
                    producesSupplies=producesSupplies) ?: continue
                if(route.waitingForSupplies) {
                    // Prefer an immediately funded mission, then the shortest leg the home sector can provision.
                    if(route.missionJumps<(waiting?.second ?: Int.MAX_VALUE))waiting=base to route.missionJumps
                    continue
                }
                if(secured && route.position.grid==fleet.positionIn(galaxy).grid) {
                    // Load the next infantry at home before moving to the berth that can fuel it.
                    setStrategy(fleet,NpcStrategicState(now,expeditionBase=base.id))
                    continue@countryLoop
                }
                val (inventory,provisioned)=supplied(stocks,fleet,galaxy,departing=false,missionJumps=route.missionJumps) ?: continue
                stocks=inventory
                setStrategy(provisioned,NpcStrategicState(now,expeditionBase=base.id))
                continue@countryLoop
            }
            waiting?.let {(base,_)->setStrategy(checkNotNull(retained),NpcStrategicState(now,expeditionBase=base.id))}
        }
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
            return replenished.copy(strategy=NpcStrategicState(now+21600, awaitingDockSupply=awaitingSupply,
                expeditionBase=outfit.strategy?.expeditionBase))
        }
        fun dockLocally(outfit:OutfitStock,destination:OutfitPosition,mission:Plan?) {
            var dockedOutfit=outfit.copy(position=destination)
            if(mission!=null && mission.missionJumps>0) {
                val (inventory,loaded)=checkNotNull(supplied(stocks,dockedOutfit,galaxy,departing=false,
                    missionJumps=mission.missionJumps,allowPartialLoad=mission.partialDepotLoad))
                stocks=inventory
                dockedOutfit=loaded
            }
            val replenished=replenishAtBerth(dockedOutfit)
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
                if(outfit.strategy!=null)setStrategy(outfit,outfit.strategy.expeditionBase?.let {
                    NpcStrategicState(now+21600,expeditionBase=it)
                })
                continue
            }
            if(claimsByFleet[id].orEmpty().any {it.vacantFleet(outfit,stocks,galaxy,players)!=null})continue
            val hasTacticalBerth=position.baseId==0 && outfit.activeShips!!.values.any {unit->
                unit.boats>0 && unit.posture?.let {it.grid==position.grid && it.mode.requiresBase}==true
            }
            val pending=outfit.strategy
            val expedition=pending?.expeditionBase
            var awaitingSupply = position.baseId != 0 && outfit.guard == null &&
                pending?.awaitingDockSupply == true && needsDockSupply(outfit)
            if(pending?.destination!=null && now>=pending.arrivesAt!!) {
                val destination=pending.destination
                val normal=target(galaxy,forces(),outfit,stocks,players.values,session.eliminated.keys,destination,
                    mayReserve=!warehouseBlocked(stocks,id),producesSupplies=producesSupplies)
                val relief=if(normal?.infantryBerth==true || (normal?.missionJumps ?: 0)>0)null
                    else reliefRoute(galaxy,forces(),outfit,stocks,destination)
                val planned=relief?.let {OutfitPosition(it.next,if(it.jumps==1)it.base.id else 0)} ?: normal?.position
                if(planned?.grid==position.grid) {
                    dockLocally(outfit,planned,normal)
                    continue
                }
                if(hasTacticalBerth) {
                    setStrategy(outfit,NpcStrategicState(now+21600,expeditionBase=expedition))
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
                    val objectiveReroute=normal?.takeIf {it.missionJumps>0}?.position?.grid
                        ?.takeIf {distance(position.grid,it)==1 && it!=destination}
                    val next=objectiveReroute ?: if(canDepart(stocks,outfit,galaxy))reroute?.next
                        else reliefRoute(galaxy,forces(),outfit,stocks)?.next
                    val strategy=NpcStrategicState(now+21600,next,next?.let {now+3600},awaitingSupply,expedition)
                    stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(strategy=strategy)))
                    continue
                }
                if(position.baseId!=0)outfit=repairAtDepot(outfit)
                val provision=if(relief==null)supplied(stocks,outfit,galaxy,missionJumps=normal?.missionJumps ?: 0) else {
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
                        strategy=NpcStrategicState(now+21600, awaitingDockSupply=awaitingSupply,expeditionBase=expedition))))
                    continue
                }
                stocks=provision.first;outfit=provision.second
                var moved=outfit.copy(position=planned,strategy=NpcStrategicState(now+21600,expeditionBase=expedition),
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
                        strategy = NpcStrategicState(now + 21600,expeditionBase=expedition),
                        activeShips = ActiveShipUnits(provisioned.activeShips!!.values.map {
                            it.copy(tactical = null, motionCommander = 0, posture = null)
                        }))
                    stocks = inventory.copy(outfits = inventory.outfits + (id to deployed))
                    forceCache = null
                    publishDeployment(deployed, position.grid, position.grid)
                    continue
                }
                if (position.baseId != 0) {
                    outfit = replaceBoatsAtHome(repairAtDepot(outfit))
                    stocks=stocks.copy(outfits=stocks.outfits+(id to outfit))
                    val expeditionDepot=expedition!=null && position.grid==galaxy.bases.single {it.id==outfit.baseId}.grid
                    if(outfit.guard==null && (position.baseId==outfit.baseId || expeditionDepot) &&
                        !TacticalContact.contested(forces()[position.grid].orEmpty()) && !warehouseBlocked(stocks,id)) {
                        val objective=NpcOccupation.goal(outfit,stocks,galaxy,session.eliminated.keys)
                        if(objective!=null) {
                            val loaded=NpcOccupation.loadAtBerth(outfit,objective,stocks,position.baseId) {
                                !logisticsOffice.staffed(stocks,outfit.country,outfit.kind)
                            }
                            if(loaded!=stocks) {
                                stocks=loaded
                                outfit=stocks.outfits.getValue(id)
                                forceCache=null
                            }
                        }
                    }
                }
                val normal=target(galaxy,forces(),outfit,stocks,players.values,session.eliminated.keys,
                    mayReserve=!warehouseBlocked(stocks,id),producesSupplies=producesSupplies)
                val destination=normal?.position
                val waitingAtHome=expedition!=null && position.baseId==outfit.baseId && destination==null
                val relief=if(waitingAtHome || normal?.infantryBerth==true || (normal?.missionJumps ?: 0)>0)null
                    else reliefRoute(galaxy,forces(),outfit,stocks)
                if(relief==null && normal!=null && normal.position.grid==position.grid) {
                    dockLocally(outfit,normal.position,normal)
                } else {
                    val grid=if(hasTacticalBerth)null else relief?.next ?: destination?.grid?.takeIf {canDepart(stocks,outfit,galaxy)}
                    if (grid == null && awaitingSupply && (depot(stocks, outfit, galaxy)?.supplies ?: 0) > 0 &&
                        !TacticalContact.contested(forces()[position.grid].orEmpty()) && !warehouseBlocked(stocks, id)) {
                        val (inventory, replenished) = checkNotNull(supplied(stocks, outfit, galaxy, departing = false))
                        stocks = inventory
                        outfit = replenished
                        awaitingSupply = needsDockSupply(outfit)
                    }
                    val next=NpcStrategicState(now+21600,grid,grid?.let {now+3600},awaitingSupply,expedition)
                    stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(strategy=next)))
                }
            }
        }
        return stocks
    }
}
