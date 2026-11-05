package org.logh7.engine

/**
 * Daily fleet upkeep paid from the national treasury (user decision 2026-11-05, `evidence:guess`).
 * The manual names a national budget but no upkeep rule, so the rate is a balance value: at the initial
 * deployment (600 ship units, 180,000 ships per side) 800 per ship costs about half the day's central income
 * (about 282 million). Only living ships count; a short treasury pays what it holds and keeps no debt.
 * Author: Peppone Choi.
 */
object FleetUpkeep {
    /** Ships per unit when an outfit has no per-unit state yet (the deployment's standard unit). */
    private const val UNIT_SHIPS = 300L

    fun ships(stocks: PlanetStocks, power: Int): Long = stocks.outfits.values.filter { it.power == power }.sumOf { outfit ->
        outfit.activeShips?.values?.sumOf { it.boats.toLong() } ?: Math.multiplyExact(outfit.units.ships.values.sumOf { it.toLong() }, UNIT_SHIPS)
    }

    fun due(stocks: PlanetStocks, power: Int, perShip: Long): Long =
        if (perShip == 0L) 0 else Math.multiplyExact(ships(stocks, power), perShip)

    /** One day of upkeep for both sides, deducted in place from [treasuries]. */
    fun pay(stocks: PlanetStocks, treasuries: MutableMap<Int, Long>, perShip: Long) {
        for (power in 2..3) {
            val due = due(stocks, power, perShip)
            if (due == 0L) continue
            val held = treasuries[power] ?: 0L
            treasuries[power] = held - minOf(held, due)
        }
    }
}
