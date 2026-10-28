package org.logh7.engine

/** Shared carrying policy; ship statistics are manual data, ground allowance is designed. Author: 최병호. */
object OutfitSupplies {
    fun unitCapacity(kind: Int): Int {
        val spec = UnitSpecifications.ships.single { it.kind == kind }
        return Math.multiplyExact(spec.number, spec.number("物資搭載量:物資搭載量"))
    }

    /** Preserve explicit unit allocations. Legacy/bulk changes fill in ID order and withdraw in
     * reverse ID order. The outfit total remains authoritative for strategic logistics; unit balances
     * specify where that total is carried, rather than creating another supply pool. */
    fun distribution(outfit: OutfitStock): Map<Int, Int> {
        require(outfit.supplies <= capacity(outfit.units))
        val ships = requireNotNull(outfit.activeShips).values.sortedBy { it.id }
        val result = ships.associateTo(linkedMapOf()) { it.id to (it.supplies ?: 0) }
        var difference = outfit.supplies.toLong() - result.values.sumOf(Int::toLong)
        for (unit in if (difference < 0) ships.asReversed() else ships) {
            val current = result.getValue(unit.id)
            val amount = if (difference >= 0) minOf(difference, (unitCapacity(unit.stack.kind) - current).toLong())
                else -minOf(-difference, current.toLong())
            result[unit.id] = (current + amount).toInt()
            difference -= amount
        }
        check(difference == 0L || ships.isEmpty()) { "Outfit supplies exceed commissioned cargo capacity" }
        return result
    }

    /** Materialize one allocation and its exact aggregate in the same immutable replacement. */
    fun withBalances(outfit: OutfitStock, balances: Map<Int, Int>): OutfitStock {
        val ships = requireNotNull(outfit.activeShips).values
        require(balances.keys == ships.map { it.id }.toSet())
        require(ships.all { balances.getValue(it.id) in 0..unitCapacity(it.stack.kind) })
        return outfit.copy(supplies = Math.toIntExact(balances.values.sumOf(Int::toLong)),
            activeShips = ActiveShipUnits(ships.map { it.copy(supplies = balances.getValue(it.id)) }))
    }

    /** Spend or receive on the resolved unit, preserving every other unit's allocation. */
    fun change(outfit: OutfitStock, unit: Int, delta: Int): OutfitStock {
        val balances = distribution(outfit)
        val current = requireNotNull(balances[unit]) { "Unknown cargo unit" }
        return withBalances(outfit, balances + (unit to Math.addExact(current, delta)))
    }
    fun capacity(units: UnitInventory): Int = Math.toIntExact(units.ships.entries.sumOf { (stack,quantity) ->
        val spec=UnitSpecifications.ships.single { it.kind==stack.kind }
        quantity.toLong()*spec.number*spec.number("物資搭載量:物資搭載量")
    } + if (units.ships.isEmpty()) units.troops.values.sumOf { it.toLong()*1000 } else 0L)
}
