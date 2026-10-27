package org.logh7.engine

/** Shared deployed-force accounting for map reports and movement. Author: 최병호. */
object GridForces {
    data class Force(val camp: Camp, val units: Long = 0, val nonSolo: Boolean = false,
        val ship: Boolean = false, val base: Boolean = false)

    fun collect(galaxy: Galaxy, generation: Long, navigators: Collection<Navigator>,
        stocks: PlanetStocks?): Map<Int, List<Force>> {
        require(stocks == null || stocks.generation == generation)
        val result = linkedMapOf<Int, MutableMap<Camp, Force>>()
        fun add(grid: Int, force: Force) {
            val camps = result.getOrPut(grid) { linkedMapOf() }
            val previous = camps[force.camp] ?: Force(force.camp)
            camps[force.camp] = Force(force.camp, Math.addExact(previous.units, force.units),
                previous.nonSolo || force.nonSolo, previous.ship || force.ship, previous.base || force.base)
        }
        fun camp(power: Int, insurgent: Int = 0): Camp? = when (power) {
            2 -> Camp(Power.EMPIRE, insurgent == 1)
            3 -> Camp(Power.ALLIANCE, insurgent == 1)
            else -> null
        }
        navigators.filter { it.generation == generation }.forEach {
            add(it.grid, Force(Camp(it.power, false), 1, ship = true))
        }
        val bases = galaxy.bases.associateBy { it.id }
        galaxy.bases.forEach { base ->
            val stock = stocks?.bases?.get(base.id)
            val initial = galaxy.conditions.getValue(base.id)
            camp(stock?.power ?: initial.power, stock?.camp ?: initial.camp)?.let {
                add(base.grid, Force(it, base = true))
            }
        }
        val ground = UnitSpecifications.crews.filter { it.category != 19 }.map { it.kind }.toSet()
        stocks?.outfits?.values?.forEach { outfit ->
            val ships = outfit.units.ships.values.sumOf { it.toLong() }
            val troops = outfit.units.troops.filterKeys { it.kind in ground }.values.sumOf { it.toLong() }
            if (ships + troops > 0) add(bases.getValue(outfit.baseId).grid,
                Force(checkNotNull(camp(outfit.power)), ships + troops, nonSolo = true, ship = ships > 0))
        }
        return result.mapValues { it.value.values.toList() }
    }

    fun state(galaxy: Galaxy, grid: Int, forces: Map<Int, List<Force>>): GridState =
        GridState(galaxy.kind(grid), GridTerrain.NONE, false, forces[grid].orEmpty().map {
            // Capacity decisions need only distinguish counts above 300. Keep the
            // full Long total in collect(), including for native map reports.
            CampPresence(it.camp, it.units.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), it.nonSolo, it.ship, it.base)
        })
}
