package org.logh7.engine

import java.util.Collections

/** Commissioned unit identity, not a ship-kind ID or a personal flagship ID. Author: 최병호. */
data class ActiveShipUnit(val id: Int, val stack: UnitStack) {
    init { require(id in 1..999_999) }
}

class ActiveShipUnits(values: List<ActiveShipUnit>) {
    val values: List<ActiveShipUnit> = Collections.unmodifiableList(ArrayList(values))
    init {
        require(values.map { it.id }.distinct().size == values.size)
        require(values.groupingBy { it.stack.kind }.eachCount().values.sumOf { (it + 69) / 70 } <= 60) {
            "Active outfit exceeds native party capacity"
        }
    }
    fun quantities(): Map<UnitStack, Int> = values.groupingBy { it.stack }.eachCount()
    override fun equals(other: Any?) = other is ActiveShipUnits && values == other.values
    override fun hashCode() = values.hashCode()
}

/** IDs are retired when units return to aggregate storage, and never reused in a generation.
 * Aggregate warehouse stock has no native unit IDs. Commissioning creates the active instance.
 * This lifecycle and the range below the personal-flagship range are server policy.
 */
object ActiveShips {
    private val order = compareBy<UnitStack> { it.kind }.thenBy { it.level }
    private fun checkCapacity(inventory: UnitInventory) {
        val groups = inventory.ships.entries.groupBy { it.key.kind }.values.sumOf { entries ->
            (entries.sumOf { it.value.toLong() } + 69) / 70
        }
        require(groups <= 60) { "Active outfit exceeds native party capacity" }
    }
    fun initialize(stocks: PlanetStocks): PlanetStocks {
        val missing = stocks.outfits.values.filter { it.activeShips == null }.sortedBy { it.id }
        if (missing.isEmpty()) return stocks
        missing.forEach { checkCapacity(it.units) }
        val count = missing.sumOf { it.units.ships.values.sumOf(Int::toLong) }
        require(count <= 1_000_000L - stocks.nextShipId) { "Active ship identity space exhausted" }
        var cursor = stocks.nextShipId
        val outfits = stocks.outfits.toMutableMap()
        for (outfit in missing) {
            val ships = buildList {
                outfit.units.ships.toSortedMap(order).forEach { (stack, quantity) ->
                    repeat(quantity) { add(ActiveShipUnit(cursor++, stack)) }
                }
            }
            outfits[outfit.id] = outfit.copy(activeShips = ActiveShipUnits(ships))
        }
        return stocks.copy(outfits = outfits, nextShipId = cursor)
    }

    fun reorganize(stocks: PlanetStocks, outfitId: Int, inventory: UnitInventory): PlanetStocks {
        checkCapacity(inventory)
        val outfit = stocks.outfits.getValue(outfitId)
        val previous = requireNotNull(outfit.activeShips) { "Outfit units are not initialized" }
        val byStack = previous.values.groupBy { it.stack }
        val added = inventory.ships.entries.sumOf { (stack, quantity) ->
            maxOf(0, quantity - byStack[stack].orEmpty().size).toLong()
        }
        require(added <= 1_000_000L - stocks.nextShipId) { "Active ship identity space exhausted" }
        var cursor = stocks.nextShipId
        val units = buildList {
            inventory.ships.toSortedMap(order).forEach { (stack, quantity) ->
                val retained = byStack[stack].orEmpty().sortedBy { it.id }.take(quantity)
                addAll(retained)
                repeat(quantity - retained.size) { add(ActiveShipUnit(cursor++, stack)) }
            }
        }
        val changed = outfit.copy(units = inventory, activeShips = ActiveShipUnits(units))
        return stocks.copy(outfits = stocks.outfits + (outfitId to changed), nextShipId = cursor)
    }
}
