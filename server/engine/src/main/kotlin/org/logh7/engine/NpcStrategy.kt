package org.logh7.engine

import kotlin.math.abs
import kotlin.math.max

/** Replacement strategic policy for uncommanded fleets; not offline character delegation. */
data class NpcStrategicState(val nextDecisionAt:Long,val destination:Int?=null,val arrivesAt:Long?=null) {
    init {
        require(nextDecisionAt in 0..0xffffffffL && (destination==null)==(arrivesAt==null))
        require(destination==null || destination in 0..4999 && arrivesAt!! in 0..nextDecisionAt)
    }
}
data class NpcStrategicMovement(val generation:Long,val outfit:Int,val source:Int,val destination:Int,
    val seconds:Long,val units:List<Int>,val recipients:Map<Int,String>) {
    init {require(generation>0 && outfit>0 && source in 0..4999 && destination in 0..4999 && source!=destination &&
        seconds in 0..0xffffffffL && units.size in 1..300 && units.distinct().size==units.size && units.all {it in 1..999999} &&
        recipients.keys.all {it>0} && recipients.values.all {it.isNotBlank()})}
}

internal object NpcStrategy {
    private fun distance(a:Int,b:Int)=max(abs(a%100-b%100),abs(a/100-b/100))
    private fun eligible(outfit:OutfitStock)=outfit.kind in setOf(OutfitKind.FLEET,OutfitKind.PATROL) &&
        outfit.members().isEmpty() && outfit.activeGround?.values.orEmpty().none {it.order!=null} && outfit.activeShips?.values?.any {it.boats>0}==true &&
        outfit.activeShips.values.none {it.support!=null || it.attack!=null || it.shot!=null || it.posture?.order!=null || it.tactical?.retreat!=null}
    private fun power(outfit:OutfitStock)=if(outfit.power==2)Power.EMPIRE else Power.ALLIANCE
    private fun allowed(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,target:Int):Boolean {
        val count=GridForces.mobileUnits(outfit)
        if(count !in 1..300)return false
        return GridEntryRules.evaluate(GridForces.state(galaxy,target,forces),GridEntry(Camp(power(outfit),false),count.toInt(),false))==GridEntryVerdict.ALLOWED
    }
    private fun step(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,origin:Int,goal:Int,preferred:Int?=null):Int? {
        val x=origin%100;val y=origin/100
        val camp=Camp(power(outfit),false);val strength=GridForces.mobileUnits(outfit)
        return (-1..1).flatMap {dy->(-1..1).mapNotNull {dx->
            if(dx==0 && dy==0 || x+dx !in 0..99 || y+dy !in 0..49)null else (y+dy)*100+x+dx
        }}.filter {distance(it,goal)<distance(origin,goal) && hostile(forces,it,camp)<=strength*2 && allowed(galaxy,forces,outfit,it)}
            .minWithOrNull(compareBy<Int> {if(it==preferred)0 else 1}.thenBy {distance(it,goal)}.thenBy {it})
    }
    /** Hostile mobile units on [grid] against [camp]. */
    private fun hostile(forces:Map<Int,List<GridForces.Force>>,grid:Int,camp:Camp)=
        forces[grid].orEmpty().filter {it.camp!=camp}.sumOf {it.units}
    private fun home(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,stocks:PlanetStocks):GalaxyBase? {
        val origin=outfit.positionIn(galaxy).grid
        return galaxy.bases.filter {b->stocks.bases[b.id]?.let {it.power==outfit.power && it.camp==0}==true &&
            !TacticalContact.contested(forces[b.grid].orEmpty())}
            .minWithOrNull(compareBy<GalaxyBase> {distance(origin,it.grid)}.thenBy {it.grid}.thenBy {it.id})
    }
    /** Reserve real cargo for every remaining jump to the nearest currently safe depot. */
    fun returnDistance(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,stocks:PlanetStocks):Int? =
        if(!eligible(outfit) || outfit.positionIn(galaxy).baseId!=0)null else
            home(galaxy,forces,outfit,stocks)?.let {distance(outfit.positionIn(galaxy).grid,it.grid)}
    /**
     * Next adjacent grid: towards the nearest enemy it can take on (at most twice its own mobile units), or, when
     * every enemy outmatches it, a stronger enemy shares its grid or its fuel runs short, back towards the nearest own depot
     * (`evidence:guess`, 2026-11-04 replacement policy).
     */
    private fun target(galaxy:Galaxy,forces:Map<Int,List<GridForces.Force>>,outfit:OutfitStock,stocks:PlanetStocks,preferred:Int?=null):OutfitPosition? {
        val position=outfit.positionIn(galaxy)
        val origin=position.grid
        val camp=Camp(power(outfit),false)
        val own=GridForces.mobileUnits(outfit)
        fun beatable(grid:Int)=hostile(forces,grid,camp)<=own*2
        val enemies=forces.entries.filter {(_,rows)->rows.any {it.camp!=camp && (it.units>0 || it.base)}}.map {it.key}
        val candidates=enemies.filter(::beatable)
        // Keep a reserved route towards a still-valid enemy when an unrelated enemy appears.
        val continuing=if(origin in candidates)listOf(origin) else if(preferred==null)candidates else
            candidates.filter {distance(preferred,it)<distance(origin,it)}.ifEmpty {candidates}
        val goal=continuing.minWithOrNull(compareBy<Int> {distance(origin,it)}.thenBy {it})
        val home=home(galaxy,forces,outfit,stocks)
        // Turn back before the next ammunition payment could consume the return trip.
        val live=outfit.activeShips?.values.orEmpty().filter {it.boats>0}
        val balances=OutfitSupplies.distribution(outfit)
        val short=position.baseId==0 && live.any {unit->
            val spec=UnitSpecifications.ships.single {it.kind==unit.stack.kind}
            val shot=TacticalWeapon.entries.maxOf {weapon->
                val mount=GuardCombat.mount(TacticalArmaments.mount(spec,weapon),weapon,outfit,unit.id)
                if(mount.power==0)0 else TacticalFleetShoot.cost(unit,mount,weapon)}
            val required=maxOf(unit.boats*2,(home?.let {distance(origin,it.grid)*unit.boats+shot} ?: 0))
            balances.getValue(unit.id)<required
        }
        if(goal!=null && beatable(origin) && !short)return if(goal==origin)null else step(galaxy,forces,outfit,origin,goal,preferred)?.let(::OutfitPosition)
        if(home==null)return null
        if(home.grid==origin)return if(position.baseId!=0)null else OutfitPosition(origin,home.id)
        return step(galaxy,forces,outfit,origin,home.grid,preferred)?.let {OutfitPosition(it,if(it==home.grid)home.id else 0)}
    }
    private fun supplied(stocks:PlanetStocks,outfit:OutfitStock,galaxy:Galaxy,departing:Boolean=true):Pair<PlanetStocks,OutfitStock>? {
        val live=outfit.activeShips!!.values.filter {it.boats>0}
        val balances=OutfitSupplies.distribution(outfit).toMutableMap()
        val baseId=outfit.positionIn(galaxy).baseId
        val depot=stocks.bases[baseId]?.takeIf {it.power==outfit.power && it.camp==0}
        var available=depot?.supplies ?: 0
        val missing=live.sumOf {max(0,(if(departing)it.boats else 0)-balances.getValue(it.id)).toLong()}
        if(missing>available)return null
        for(unit in live) {
            val amount=max(0,(if(departing)unit.boats else 0)-balances.getValue(unit.id))
            balances[unit.id]=balances.getValue(unit.id)+amount;available-=amount
        }
        // Carry several future jumps where the real depot and surviving cargo permit it.
        for(unit in live) {
            val goal=minOf(OutfitSupplies.unitCapacity(unit),unit.boats*10)
            val amount=minOf(available,max(0,goal-balances.getValue(unit.id)))
            balances[unit.id]=balances.getValue(unit.id)+amount;available-=amount
        }
        if(departing)for(unit in live)balances[unit.id]=balances.getValue(unit.id)-unit.boats
        val inventory=if(depot==null)stocks else stocks.copy(bases=stocks.bases+(baseId to depot.copy(supplies=available)))
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
        for(id in original.outfits.keys.sorted()) {
            var outfit=stocks.outfits.getValue(id)
            val position=outfit.positionIn(galaxy)
            val owner=stocks.bases[position.baseId]
            val permitted=eligible(outfit) && (position.baseId==0 || owner?.let {it.power==outfit.power && it.camp==0}==true)
            if(!permitted) {
                if(outfit.strategy!=null)stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(strategy=null)))
                continue
            }
            val pending=outfit.strategy
            if(pending?.destination!=null && now>=pending.arrivesAt!!) {
                val destination=pending.destination
                val planned=target(galaxy,forces(),outfit,stocks,destination)
                // Re-evaluate source hostility as well as destination capacity after earlier arrivals.
                if(distance(position.grid,destination)!=1 || planned?.grid!=destination || !allowed(galaxy,forces(),outfit,destination)) {
                    stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(strategy=NpcStrategicState(now+21600))))
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
                    // Recover older fleets stranded in orbit over an available depot.
                    val replenished=checkNotNull(supplied(stocks,outfit.copy(position=destination),galaxy,departing=false))
                    val docked=replenished.second.copy(strategy=NpcStrategicState(now+21600),
                        activeShips=ActiveShipUnits(replenished.second.activeShips!!.values.map {it.copy(tactical=null,motionCommander=0)}))
                    stocks=replenished.first.copy(outfits=replenished.first.outfits+(id to docked))
                    forceCache=null
                } else {
                    val next=if(destination==null)NpcStrategicState(now+21600) else NpcStrategicState(now+21600,destination.grid,now+3600)
                    stocks=stocks.copy(outfits=stocks.outfits+(id to outfit.copy(strategy=next)))
                }
            }
        }
        return stocks
    }
}
