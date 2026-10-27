package org.logh7.engine

/** Unpacked replacement boats survive across actions; full units remain in UnitInventory. Author: 최병호. */
class SpareShipBoats(values: Map<UnitStack, Int> = emptyMap()) {
    val values: Map<UnitStack, Int> = java.util.Collections.unmodifiableMap(LinkedHashMap(values))
    init {
        require(values.size<=99 && values.all { (stack,count) ->
            count in 1 until UnitSpecifications.ships.single { it.kind==stack.kind }.number })
    }
    override fun equals(other: Any?) = other is SpareShipBoats && values==other.values
    override fun hashCode() = values.hashCode()
}

internal object ReplacementBoats {
    /** A remainder occupies one selectable native unit, while retaining its physical boat count. */
    fun selectable(warehouse: OutfitWarehouse): UnitInventory {
        val ships=warehouse.units.ships.toMutableMap()
        for (stack in warehouse.spareShips.values.keys) ships[stack]=Math.addExact(ships[stack] ?: 0,1)
        return UnitInventory(ships,warehouse.units.troops)
    }

    /** Move whole selectable units, including an understrength final unit, without refilling it. */
    fun reorganize(stocks: PlanetStocks,outfitId: Int,transfer: UnitTransfer): PlanetStocks {
        val ready=ActiveShips.initialize(stocks)
        val outfit=ready.outfits.getValue(outfitId)
        val warehouse=ready.warehouses.getValue(outfit.warehouseId)
        val moved=transfer.apply(selectable(warehouse),outfit.units)
        OutfitShipLimits.validate(outfit.kind,moved.destination)
        val next=ActiveShips.reorganize(ready,outfitId,moved.destination)
        val previous=checkNotNull(outfit.activeShips).values
        val previousIds=previous.map {it.id}.toSet()
        val nextUnits=checkNotNull(next.outfits.getValue(outfitId).activeShips).values.toMutableList()
        val retained=nextUnits.map {it.id}.toSet()
        val full=warehouse.units.ships.toMutableMap()
        val spare=warehouse.spareShips.values.toMutableMap()
        for ((stack,delta) in transfer.ships) if(delta>0) {
            val complete=full[stack] ?: 0
            val fromFull=minOf(complete,delta)
            if(complete==fromFull)full.remove(stack) else full[stack]=complete-fromFull
            if(delta>fromFull) {
                require(delta-fromFull==1)
                val boats=requireNotNull(spare.remove(stack))
                val added=nextUnits.indexOfLast {it.stack==stack && it.id !in previousIds}
                require(added>=0)
                nextUnits[added]=nextUnits[added].copy(boats=boats)
            }
        }
        for (unit in previous.filter {it.id !in retained}) {
            val size=UnitSpecifications.ships.single {it.kind==unit.stack.kind}.number
            val amount=(spare[unit.stack] ?: 0)+unit.boats
            if(amount/size>0)full[unit.stack]=Math.addExact(full[unit.stack] ?: 0,amount/size)
            if(amount%size==0)spare.remove(unit.stack) else spare[unit.stack]=amount%size
        }
        return next.copy(outfits=next.outfits+(outfitId to next.outfits.getValue(outfitId).copy(
            activeShips=ActiveShipUnits(nextUnits))),warehouses=next.warehouses+(warehouse.id to warehouse.copy(
            units=UnitInventory(full,moved.source.troops),spareShips=SpareShipBoats(spare))))
    }

    fun crewRequired(outfit: OutfitStock): Long = checkNotNull(outfit.activeShips).values.sumOf {unit ->
        val spec=UnitSpecifications.ships.single {it.kind==unit.stack.kind}
        (unit.boats.toLong()*spec.number("必要乗組員ユニット数:必要乗組員ユニット数")+spec.number-1)/spec.number
    }

    /** Consume only the exact native ship kind, including a previous unpacked remainder. */
    fun take(warehouse: OutfitWarehouse, kind: Int, count: Int): OutfitWarehouse {
        require(count>=0)
        val size=UnitSpecifications.ships.single { it.kind==kind }.number
        val full=warehouse.units.ships.toMutableMap();val spare=warehouse.spareShips.values.toMutableMap()
        var left=count
        val stacks=(full.keys+spare.keys).filter {it.kind==kind}.distinct().sortedBy {it.level}
        for (stack in stacks) {
            val available=(full[stack] ?: 0).toLong()*size+(spare[stack] ?: 0)
            val used=minOf(available,left.toLong()).toInt();left-=used
            val remaining=available-used
            if (remaining/size==0L) full.remove(stack) else full[stack]=Math.toIntExact(remaining/size)
            if (remaining%size==0L) spare.remove(stack) else spare[stack]=(remaining%size).toInt()
        }
        require(left==0) { "Insufficient replacement boats" }
        return warehouse.copy(units=UnitInventory(full,warehouse.units.troops),spareShips=SpareShipBoats(spare))
    }

}
