package org.logh7.engine

/** Shared carrying policy; ship statistics are manual data, ground allowance is designed. Author: 최병호. */
object OutfitSupplies {
    fun unitCapacity(kind: Int): Int {
        val spec = UnitSpecifications.ships.single { it.kind == kind }
        return Math.multiplyExact(spec.number, spec.number("物資搭載量:物資搭載量"))
    }

    /** Designed bulk-stowage policy: fill commissioned units in stable ID order.
     * The durable source remains outfit.supplies; do not invent an independent second balance.
     */
    fun distribution(outfit: OutfitStock): Map<Int, Int> {
        require(outfit.supplies <= capacity(outfit.units))
        var remaining = outfit.supplies
        val result = linkedMapOf<Int, Int>()
        for (unit in requireNotNull(outfit.activeShips).values.sortedBy { it.id }) {
            val amount = minOf(remaining, unitCapacity(unit.stack.kind))
            result[unit.id] = amount
            remaining -= amount
        }
        return result
    }
    fun capacity(units: UnitInventory): Int = Math.toIntExact(units.ships.entries.sumOf { (stack,quantity) ->
        val spec=UnitSpecifications.ships.single { it.kind==stack.kind }
        quantity.toLong()*spec.number*spec.number("物資搭載量:物資搭載量")
    } + if (units.ships.isEmpty()) units.troops.values.sumOf { it.toLong()*1000 } else 0L)
}
