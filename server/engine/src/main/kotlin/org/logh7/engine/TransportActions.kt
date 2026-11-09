package org.logh7.engine

import org.logh7.engine.strategy.*

/** One transport package. [carrier] 0 = in the source transport warehouse; otherwise the transport outfit
 * carrying it, which had [carrierBoats] live boats at loading. [survivingBoats] expresses the remaining cargo share against that original count. */
data class TransportPackage(val id:Int,val country:CountryId,val source:Int,val destination:Int,val supplies:Int,
    val units:UnitInventory=UnitInventory(),val carrier:Int=0,val carrierBoats:Int=0,val survivingBoats:Int=carrierBoats) {
    init {require(id>0 && source in 1..65535 && destination in 1..65535 && source!=destination &&
        supplies>=0 && (supplies>0 || units.ships.isNotEmpty() || units.troops.isNotEmpty()) && carrier>=0 &&
        (carrier==0) == (carrierBoats==0) && carrierBoats>=0 &&
        survivingBoats in (if(carrier==0)0 else 1)..carrierBoats)}
}

/**
 * 輸送計画 28 (MCP80), 輸送中止 29 (MCP80), 搬出入 65 (MCP160): W p.71/72 command table.
 * Package flow, cards, the per-base limit of 20 and the proportional loss of a sunk carrier are the design
 * approved 2026-10-31 (design-strategic-transport.md, evidence:guess).
 * Request fields: 28 base=source, target=destination, amount=supplies, value=ship kind or 1000+troop kind
 * (0xffff none), argument=unit count; 29 and 65 target=package id, 65 outfit=the transport outfit.
 * Author: Peppone Choi.
 */
object TransportActions {
    const val PLAN=28
    const val CANCEL=29
    const val LOAD=65
    val supported=setOf(PLAN,CANCEL,LOAD)
    const val PER_BASE=20
    const val NO_UNIT=0xffff
    private val commanders=setOf(44,180)

    /** Package unit slots per live ship unit: cargo and troop hulls 10, others 2, the approved carrying split (evidence:guess). */
    fun slots(kind:Int):Int = ProductionUnits.definitions.singleOrNull {it.ship && it.kind==kind}?.name?.let {name ->
        if(listOf("輸送艦","商船").any {name.startsWith(it)})GroundTransfer.CARRIER_SLOTS else GroundTransfer.capacity(kind)} ?: GroundTransfer.WARSHIP_SLOTS
    fun unitSlots(o:OutfitStock):Int = o.activeShips?.values.orEmpty().filter {it.boats>0}.sumOf {slots(it.stack.kind)}
    fun unitCount(units:UnitInventory)=units.ships.values.sum()+units.troops.values.sum()
    fun boats(o:OutfitStock)=o.activeShips?.values.orEmpty().sumOf {it.boats}
    internal fun carriedCargo(outfit:OutfitStock,packages:List<TransportPackage>):List<Pair<Int,UnitInventory>> {
        val alive=boats(outfit)
        return packages.filter {it.carrier==outfit.id}.map {delivered(it,alive)}
    }

    private fun add(a:Map<UnitStack,Int>,b:Map<UnitStack,Int>,sign:Int)=(a.keys+b.keys).associateWith {
        (a[it] ?: 0)+sign*(b[it] ?: 0)}.filterValues {it!=0}
    private fun deposit(base: PlanetStock, supplies: Int, units: UnitInventory): PlanetStock? {
        if (supplies > Int.MAX_VALUE - base.supplies) return null
        val ships = add(base.units.ships, units.ships, 1)
        val troops = add(base.units.troops, units.troops, 1)
        if (ships.size > 99 || troops.size > 24 ||
            ships.values.any { it > 65535 } || troops.values.any { it > 65535 } ||
            !warehouseShipIncreaseFits(base.units.ships,base.spareShips,ships,base.spareShips)) return null
        return base.copy(supplies = base.supplies + supplies, units = UnitInventory(ships, troops, base.units.work))
    }
    private fun minus(a:UnitInventory,b:UnitInventory)=UnitInventory(add(a.ships,b.ships,-1),add(a.troops,b.troops,-1),a.work)

    /** A tactical detachment divides surviving cargo without treating the separation as a casualty. */
    internal fun splitRetreatCargo(state:StrategicState,original:OutfitStock,left:OutfitStock,moved:OutfitStock):StrategicState? {
        if(original.kind!=OutfitKind.TRANSPORT || state.transports.none {it.carrier==original.id})return state
        val originalBoats=boats(original)
        val movedBoats=boats(moved)
        val leftBoats=boats(left)
        var lastId=state.transportIdFloor
        val packages=mutableListOf<TransportPackage>()
        for(pack in state.transports) {
            if(pack.carrier!=original.id) {
                packages+=pack
                continue
            }
            val (supplies,units)=delivered(pack,originalBoats)
            fun movedShare(amount:Int)=(amount.toLong()*movedBoats/originalBoats).toInt()
            val movedSupplies=movedShare(supplies)
            val movedUnits=UnitInventory(units.ships.mapValues {movedShare(it.value)}.filterValues {it>0},
                units.troops.mapValues {movedShare(it.value)}.filterValues {it>0})
            val leftUnits=minus(units,movedUnits)
            val leftSupplies=supplies-movedSupplies
            val hasLeft=leftSupplies>0 || unitCount(leftUnits)>0
            val hasMoved=movedSupplies>0 || unitCount(movedUnits)>0
            if(hasLeft)packages+=pack.copy(supplies=leftSupplies,units=leftUnits,carrierBoats=leftBoats,survivingBoats=leftBoats)
            if(hasMoved) {
                if(hasLeft && lastId==Int.MAX_VALUE)return null
                packages+=pack.copy(id=if(hasLeft)++lastId else pack.id,supplies=movedSupplies,units=movedUnits,
                    carrier=moved.id,carrierBoats=movedBoats,survivingBoats=movedBoats)
            }
        }
        fun fits(outfit:OutfitStock):Boolean {
            val cargo=packages.filter {it.carrier==outfit.id}
            if(cargo.isEmpty())return true
            return cargo.sumOf {it.supplies.toLong()}<=OutfitSupplies.capacity(outfit)-outfit.supplies.toLong() &&
                cargo.sumOf {unitCount(it.units).toLong()}<=unitSlots(outfit)-outfit.activeGround?.values.orEmpty().count {it.carrier>0}
        }
        if(packages.size>4096 || !fits(left) || !fits(moved))return null
        return state.copy(transports=packages,lastTransportId=lastId)
    }

    fun evaluate(galaxy:Galaxy,r:StrategyActionRequest,s:SessionState,p:Navigator,stocks:PlanetStocks,
        countries:CountryCatalog=CountryCatalogLoader.current()):StrategyActionResult {
        fun reject(reason:StrategyActionRejection)=StrategyActionResult(p,stocks,reason)
        val country=r.country ?: return reject(StrategyActionRejection.IDENTITY)
        if(country.value !in countries.countries)return reject(StrategyActionRejection.AUTHORITY)
        if(r.command !in supported)return reject(StrategyActionRejection.COMMAND)
        if(p.cpPaused || p.navigationPending)return reject(StrategyActionRejection.BUSY)
        val packages=stocks.strategic.transports
        val lastId=stocks.strategic.transportIdFloor
        var next:PlanetStocks
        val warehouseBase:Int
        when(r.command) {
            PLAN -> {
                if(LogisticsOfficePolicy(galaxy,countries).cards(stocks,r.characterId,country.value).none {it.first==r.card})
                    return reject(StrategyActionRejection.CARD)
                val source=stocks.bases[r.base] ?: return reject(StrategyActionRejection.TARGET)
                val destination=stocks.bases[r.target] ?: return reject(StrategyActionRejection.TARGET)
                if(source.baseId==destination.baseId || !source.belongsTo(country) || !destination.belongsTo(country) ||
                    source.camp!=0 || destination.camp!=0)return reject(StrategyActionRejection.TARGET)
                if(r.amount !in 0..source.supplies.toLong() || r.argument<0 || r.value!=NO_UNIT && r.argument==0 ||
                    r.value==NO_UNIT && r.argument!=0)return reject(StrategyActionRejection.RESOURCE)
                if(packages.count {it.source==source.baseId && it.carrier==0}>=PER_BASE || packages.size>=4096)
                    return reject(StrategyActionRejection.MAXIMUM)
                var remaining=r.argument
                val ships=linkedMapOf<UnitStack,Int>();val troops=linkedMapOf<UnitStack,Int>()
                if(r.value!=NO_UNIT) {
                    val troop=r.value>=1000
                    val kind=if(troop)r.value-1000 else r.value
                    val pool=if(troop)source.units.troops else source.units.ships
                    for((stack,count) in pool.entries.filter {it.key.kind==kind}.sortedBy {it.key.level}) {
                        val moved=minOf(count,remaining);if(moved>0)(if(troop)troops else ships)[stack]=moved;remaining-=moved
                    }
                }
                if(remaining!=0)return reject(StrategyActionRejection.RESOURCE)
                val units=UnitInventory(ships,troops)
                if(r.amount==0L && unitCount(units)==0)return reject(StrategyActionRejection.RESOURCE)
                if(lastId==Int.MAX_VALUE)return reject(StrategyActionRejection.MAXIMUM)
                val made=TransportPackage(lastId+1,country,source.baseId,destination.baseId,r.amount.toInt(),units)
                next=stocks.copy(bases=stocks.bases+(source.baseId to source.copy(supplies=source.supplies-made.supplies,
                    units=minus(source.units,units))),strategic=stocks.strategic.copy(transports=packages+made,lastTransportId=made.id))
                warehouseBase=source.baseId
            }
            CANCEL -> {
                if(LogisticsOfficePolicy(galaxy,countries).cards(stocks,r.characterId,country.value).none {it.first==r.card})
                    return reject(StrategyActionRejection.CARD)
                val pack=packages.singleOrNull {it.id==r.target && it.country==country} ?: return reject(StrategyActionRejection.TARGET)
                if(pack.carrier!=0)return reject(StrategyActionRejection.BUSY)
                val source=stocks.bases[pack.source]?.takeIf {it.belongsTo(country) && it.camp==0} ?: return reject(StrategyActionRejection.LOCATION)
                val stocked=deposit(source,pack.supplies,pack.units) ?: return reject(StrategyActionRejection.MAXIMUM)
                next=stocks.copy(bases=stocks.bases+(source.baseId to stocked),strategic=stocks.strategic.copy(transports=packages-pack,lastTransportId=lastId))
                warehouseBase=source.baseId
            }
            else -> {
                if(r.card !in commanders || OrganizationOffices.of(r.card,country,countries)==null)return reject(StrategyActionRejection.CARD)
                val o=stocks.outfits[r.outfit] ?: return reject(StrategyActionRejection.AUTHORITY)
                if(o.kind!=OutfitKind.TRANSPORT || o.commanderId!=r.characterId || o.country!=country)return reject(StrategyActionRejection.AUTHORITY)
                val position=o.positionIn(galaxy)
                if(position.baseId==0 || position.grid!=p.grid || position.baseId!=p.baseId || p.mode !in 1..4)
                    return reject(StrategyActionRejection.LOCATION)
                val base=stocks.bases[position.baseId]?.takeIf {it.belongsTo(country) && it.camp==0} ?: return reject(StrategyActionRejection.LOCATION)
                warehouseBase=base.baseId
                val pack=packages.singleOrNull {it.id==r.target && it.country==country} ?: return reject(StrategyActionRejection.TARGET)
                if(o.activeShips?.values.orEmpty().any {it.tactical!=null || it.support!=null})return reject(StrategyActionRejection.BUSY)
                val currentIntent=if(pack.carrier==0)1 else 2
                if(r.value!=0 && r.value!=currentIntent)return reject(StrategyActionRejection.QUOTE_CHANGED)
                next=if(pack.carrier==0) {
                    if(pack.source!=base.baseId)return reject(StrategyActionRejection.LOCATION)
                    val alive=boats(o)
                    val carried=carriedCargo(o,packages)
                    val room=OutfitSupplies.capacity(o)-o.supplies-carried.sumOf {it.first.toLong()}
                    val slots=unitSlots(o)-(o.activeGround?.values.orEmpty().count {it.carrier>0})-carried.sumOf {unitCount(it.second)}
                    if(alive<=0 || pack.supplies>room || unitCount(pack.units)>slots)return reject(StrategyActionRejection.TROOPS)
                    stocks.copy(strategic=stocks.strategic.copy(transports=packages.map {if(it.id==pack.id)it.copy(carrier=o.id,carrierBoats=alive,survivingBoats=alive) else it}))
                } else {
                    if(pack.carrier!=o.id)return reject(StrategyActionRejection.AUTHORITY)
                    // A destination lost to capture or revolt while the cargo is aboard would strand it: the carrier
                    // may then unload at the own regular base it docks at (2026-11-04 rerouting, evidence:guess).
                    val lost=stocks.bases[pack.destination]?.let {!it.belongsTo(country) || it.camp!=0} ?: true
                    if(pack.destination!=base.baseId && !lost)return reject(StrategyActionRejection.LOCATION)
                    // Boats lost since loading take the same share of the cargo (approved §2.3).
                    val (supplies,units)=delivered(pack,boats(o))
                    val stocked=deposit(base,supplies,units) ?: return reject(StrategyActionRejection.MAXIMUM)
                    stocks.copy(bases=stocks.bases+(base.baseId to stocked),strategic=stocks.strategic.copy(transports=packages-pack,lastTransportId=lastId))
                }
            }
        }
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),if(r.command==LOAD)160L else 80L,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(StrategyActionRejection.CP)
        return StrategyActionResult(p.paidWith(paid.balance),next,changed=true,warehouseBase=warehouseBase)
    }
    internal fun settleCargo(stocks:PlanetStocks,previous:PlanetStocks?):PlanetStocks {
        val existing=stocks.strategic.transports
        if(existing.none {it.carrier!=0})return stocks
        val prior=previous?.takeIf {it.generation==stocks.generation}
        val priorPackages=prior?.strategic?.transports.orEmpty().associateBy {it.id}
        val settled=existing.mapNotNull {pack->
            if(pack.carrier==0)pack else {
                val alive=stocks.outfits[pack.carrier]?.let(::boats) ?: 0
                val before=priorPackages[pack.id]?.takeIf {it.carrier==pack.carrier}
                    ?.let {prior?.outfits?.get(it.carrier)?.let(::boats)}
                // A new casualty after reinforcement consumes the same fraction of the remaining cargo.
                val surviving=if(before!=null && alive<before)
                    (minOf(before,pack.survivingBoats).toLong()*alive/before).toInt()
                    else minOf(alive,pack.survivingBoats)
                when {
                    surviving==0 -> null
                    surviving<pack.survivingBoats -> pack.copy(survivingBoats=surviving)
                    else -> pack
                }
            }
        }
        return if(settled==existing)stocks else stocks.copy(strategic=stocks.strategic.copy(transports=settled,lastTransportId=stocks.strategic.transportIdFloor))
    }

    /** The share of a carried package that survives with [boats] of its [TransportPackage.carrierBoats]. */
    fun delivered(pack:TransportPackage,boats:Int):Pair<Int,UnitInventory> {
        val surviving=minOf(boats,pack.survivingBoats)
        if(surviving>=pack.carrierBoats)return pack.supplies to pack.units
        fun scale(v:Int)=(v.toLong()*maxOf(0,surviving)/pack.carrierBoats).toInt()
        return scale(pack.supplies) to UnitInventory(pack.units.ships.mapValues {scale(it.value)}.filterValues {it>0},
            pack.units.troops.mapValues {scale(it.value)}.filterValues {it>0})
    }
}
