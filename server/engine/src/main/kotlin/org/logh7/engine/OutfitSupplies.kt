package org.logh7.engine

/** Shared carrying policy; ship statistics are manual data, ground allowance is designed. Author: 최병호. */
object OutfitSupplies {
    fun capacity(units: UnitInventory): Int = Math.toIntExact(units.ships.entries.sumOf { (stack,quantity) ->
        val spec=UnitSpecifications.ships.single { it.kind==stack.kind }
        quantity.toLong()*spec.number*spec.number("物資搭載量:物資搭載量")
    } + if (units.ships.isEmpty()) units.troops.values.sumOf { it.toLong()*1000 } else 0L)
}
