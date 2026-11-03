package org.logh7.engine

/**
 * Daily provisioning of uninhabitable bases (fortresses, gas planets) from their side's capital (2026-11-04 balance fix,
 * evidence:guess). Such a base grows no food and its garrison population starved from day 11, emptying every fortress
 * within months when nobody runs transports. Each day a regular (camp 0) imperial/alliance uninhabitable base receives
 * exactly its missing daily ration while its side still holds its own capital; the total is taken from that capital's
 * food after the settlement loop (never below zero). Captured or neutral bases and lost capitals get nothing.
 * Author: Peppone Choi.
 */
internal object CapitalProvisioning {
    /** Side (2/3) -> capital base id, only while that side regularly owns its own capital. */
    fun capitals(galaxy: Galaxy, stocks: PlanetStocks): Map<Int, Int> = Capital.entries.mapNotNull { capital ->
        galaxy.bases.singleOrNull { Capital.of(it) == capital }?.id?.takeIf { id ->
            stocks.bases[id]?.let { it.power == capital.power && it.camp == 0 } == true }?.let { capital.power to it }
    }.toMap()

    /** The stock topped up to today's ration and the amount shipped, or null when nothing is shipped. */
    fun topUp(stock: PlanetStock, base: BaseCondition, policy: PlanetProductionPolicy, capitals: Map<Int, Int>): Pair<PlanetStock, Long>? {
        val people = stock.economy ?: return null
        if (base.habitability != 0 || stock.camp != 0 || stock.power !in capitals || stock.baseId == capitals[stock.power]) return null
        val per = policy.economy.foodPeoplePerUnit
        val required = (people.population.toLong() + per - 1) / per
        val missing = required - stock.food
        if (missing <= 0) return null
        return stock.copy(food = Math.toIntExact(required)) to missing
    }

    fun charge(stocks: PlanetStocks, capitals: Map<Int, Int>, shipped: Map<Int, Long>): PlanetStocks {
        if (shipped.isEmpty()) return stocks
        var bases = stocks.bases
        for ((power, amount) in shipped) {
            val id = capitals[power] ?: continue
            val capital = bases.getValue(id)
            bases = bases + (id to capital.copy(food = maxOf(0L, capital.food - amount).toInt()))
        }
        return stocks.copy(bases = bases)
    }
}
