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
        return selectable(warehouse.units,warehouse.spareShips)
    }
    fun selectable(units: UnitInventory,spare: SpareShipBoats): UnitInventory {
        val ships=units.ships.toMutableMap()
        for (stack in spare.values.keys) ships[stack]=Math.addExact(ships[stack] ?: 0,1)
        return UnitInventory(ships,units.troops,units.work)
    }

    data class StoredTransfer(val source: UnitInventory,val destination: UnitInventory,
        val sourceSpare: SpareShipBoats,val destinationSpare: SpareShipBoats)

    /** Storage-to-storage movement preserves grade and the actual boats of the last selectable unit. */
    fun moveStored(source: UnitInventory,sourceSpare: SpareShipBoats,destination: UnitInventory,
        destinationSpare: SpareShipBoats,transfer: UnitTransfer): StoredTransfer {
        val moved=transfer.apply(selectable(source,sourceSpare),selectable(destination,destinationSpare))
        val left=source.ships.toMutableMap();val right=destination.ships.toMutableMap()
        val leftSpare=sourceSpare.values.toMutableMap();val rightSpare=destinationSpare.values.toMutableMap()
        for ((stack,delta) in transfer.ships) {
            val from=if(delta>0)left else right;val to=if(delta>0)right else left
            val fromSpare=if(delta>0)leftSpare else rightSpare;val toSpare=if(delta>0)rightSpare else leftSpare
            val size=UnitSpecifications.ships.single {it.kind==stack.kind}.number
            val count=kotlin.math.abs(delta);val full=from[stack] ?: 0;val used=minOf(full,count)
            if(full==used)from.remove(stack) else from[stack]=full-used
            var boats=used.toLong()*size
            if(count>used) {require(count-used==1);boats+=requireNotNull(fromSpare.remove(stack))}
            val received=(to[stack] ?: 0).toLong()*size+(toSpare[stack] ?: 0)+boats
            if(received/size==0L)to.remove(stack) else to[stack]=Math.toIntExact(received/size)
            if(received%size==0L)toSpare.remove(stack) else toSpare[stack]=(received%size).toInt()
        }
        return StoredTransfer(UnitInventory(left,moved.source.troops,source.work),
            UnitInventory(right,moved.destination.troops,destination.work),SpareShipBoats(leftSpare),SpareShipBoats(rightSpare))
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
