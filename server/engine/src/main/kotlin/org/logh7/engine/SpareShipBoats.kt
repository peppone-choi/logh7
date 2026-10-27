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

    /** Whole-unit reorganization has already credited a full unit; preserve its actual surviving boats. */
    fun correctReturns(warehouse: OutfitWarehouse, removed: List<ActiveShipUnit>): OutfitWarehouse {
        val full=warehouse.units.ships.toMutableMap();val spare=warehouse.spareShips.values.toMutableMap()
        for (unit in removed) {
            val size=UnitSpecifications.ships.single {it.kind==unit.stack.kind}.number
            if(unit.boats==size) continue
            val count=checkNotNull(full[unit.stack])-1
            if(count==0)full.remove(unit.stack) else full[unit.stack]=count
            val amount=(spare[unit.stack] ?: 0)+unit.boats
            if(amount/size>0)full[unit.stack]=Math.addExact(full[unit.stack] ?: 0,amount/size)
            if(amount%size==0)spare.remove(unit.stack) else spare[unit.stack]=amount%size
        }
        return warehouse.copy(units=UnitInventory(full,warehouse.units.troops),spareShips=SpareShipBoats(spare))
    }
}
