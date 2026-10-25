package org.logh7.engine

import java.util.Collections

data class PlanetStock(val baseId: Int, val power: Int, val camp: Int,
    val supplies: Int, val food: Int, val mineral: Int, val economy: PlanetEconomy? = null) {
    init { require(baseId in 1..65535 && power in 0..4 && camp in 0..1 && supplies >= 0 && food >= 0 && mineral >= 0) }
}
data class PlanetStocks(val generation: Long, val producedDays: Long, val bases: Map<Int, PlanetStock>) {
    init { require(generation > 0 && producedDays in 0..50000 && bases.size <= 350 && bases.all { (id, stock) -> id == stock.baseId }) }
}
interface PlanetStockStore : AutoCloseable {
    fun load(): PlanetStocks?
    fun save(stocks: PlanetStocks)
    override fun close() {}
}
/** Only the supply interval is recovered; quantities, economy formulas and caps are designed policy. */
data class PlanetProductionPolicy(val suppliesPerDay: Int = 1000, val maximumStock: Int = Int.MAX_VALUE,
    val economy: PlanetEconomyPolicy = PlanetEconomyPolicy()) {
    init { require(suppliesPerDay >= 0 && maximumStock > 0) }
}

/** World-actor-owned. Lazy settlement gives the same result regardless of query frequency or logins. */
internal class PlanetProduction(private val galaxy: Galaxy, private val store: PlanetStockStore?,
    private val startSeconds: Long, private val policy: PlanetProductionPolicy, generation: Long = 1) {
    private var current = store?.load()?.also { restored ->
        require(restored.bases.keys == galaxy.bases.map { it.id }.toSet()) { "Inventory bases differ from the world seed" }
        require(restored.generation <= generation) { "Inventory belongs to a newer session" }
    }?.let(::detached)
    private fun detached(stocks: PlanetStocks) = stocks.copy(bases = Collections.unmodifiableMap(LinkedHashMap(stocks.bases)))
    fun snapshot(generation: Long) = current?.takeIf { it.generation == generation }
    fun settle(generation: Long, seconds: Long): PlanetStocks {
        require(generation > 0 && seconds in startSeconds..0xffffffffL)
        val previous = current
        require(previous == null || previous.generation <= generation) { "Inventory belongs to a newer session" }
        val days = (seconds - startSeconds) / 86400
        val initial = previous?.takeIf { it.generation == generation } ?: PlanetStocks(generation, 0,
            galaxy.conditions.mapValues { (_, base) -> PlanetStock(base.baseId, base.power, base.camp, base.supplies, base.food, 100_000) })
        // Upgrade old rows at their existing watermark; never replay already-produced days.
        val initialized = if (initial.bases.values.all { it.economy != null }) initial else initial.copy(bases = initial.bases.mapValues { (id, stock) ->
            if (stock.economy != null) stock else galaxy.conditions.getValue(id).let { base ->
                stock.copy(economy = PlanetEconomy(base.population, base.adultPopulation, base.approval, base.peace))
            }
        })
        // A non-durable test clock may rewind. Keep stock and the watermark; never produce a day twice.
        val result = if (days <= initialized.producedDays) initialized else initialized.copy(producedDays = days,
            bases = initialized.bases.mapValues { (id, stock) ->
                var next = stock
                repeat(Math.toIntExact(days - initialized.producedDays)) { next = next.nextDay(galaxy.conditions.getValue(id), policy) }
                next
            })
        if (result == previous) return checkNotNull(previous)
        val next = detached(result)
        store?.save(next) // Publish only after durable replacement.
        current = next
        return next
    }
}
