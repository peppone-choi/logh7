package org.logh7.engine

import java.util.Collections

data class PlanetStock(val baseId: Int, val power: Int, val camp: Int,
    val supplies: Int, val food: Int, val mineral: Int, val economy: PlanetEconomy? = null,
    val units: UnitInventory = UnitInventory(), val taxRate: Int? = null, val governorId: Int = 0, val garrisonCommanderId: Int = 0,
    val capitalAdministratorId: Int = 0, val capitalDefenseCommanderId: Int = 0, val guardInspectorId: Int = 0) {
    init { require(baseId in 1..65535 && power in 0..4 && camp in 0..1 && supplies >= 0 && food >= 0 && mineral >= 0 &&
        (taxRate == null || taxRate in 0..100) && governorId >= 0 && (governorId == 0 || power in 2..3 && camp == 0) &&
        garrisonCommanderId >= 0 && (garrisonCommanderId == 0 || power in 2..3 && camp == 0) &&
        capitalAdministratorId>=0 && (capitalAdministratorId==0 || power==3 && camp==0) &&
        capitalDefenseCommanderId>=0 && (capitalDefenseCommanderId==0 || power in 2..3 && camp==0) &&
        guardInspectorId>=0 && (guardInspectorId==0 || power==2 && camp==0)) }
}
data class PlanetStocks(val generation: Long, val producedDays: Long, val bases: Map<Int, PlanetStock>,
    val outfits: Map<Int, OutfitStock> = emptyMap(), val warehouses: Map<Int, OutfitWarehouse> = emptyMap(),
    val logisticsOfficers: Map<Int, LogisticsOffice> = emptyMap(), val deploymentInitialized: Boolean = false,
    val nextShipId: Int = 1, val nationalOffices:Map<NationalOffice,Int> = emptyMap()) {
    init {
        require(generation > 0 && producedDays in 0..50000 && bases.size <= 350 && bases.all { (id, stock) -> id == stock.baseId })
        validateOutfitStocks(bases, outfits, warehouses, logisticsOfficers)
        require(nextShipId in 1..1_000_000)
        val ids = outfits.values.flatMap { it.activeShips?.values.orEmpty() }.map { it.id }
        require(ids.distinct().size == ids.size && ids.all { it < nextShipId }) { "Invalid active ship identity sequence" }
        require(nationalOffices.values.all { it>0 }) { "Invalid national office holder" }
    }
}
interface PlanetStockStore : AutoCloseable {
    fun load(): PlanetStocks?
    fun save(stocks: PlanetStocks)
    override fun close() {}
}
fun interface WorldStateCommitter {
    fun saveWorld(navigation: Map<String, Navigator>, inventory: PlanetStocks?)
}
/** Only the supply interval is recovered; quantities, economy formulas and caps are designed policy. */
data class PlanetProductionPolicy(val suppliesPerDay: Int = 1000, val maximumStock: Int = Int.MAX_VALUE,
    val economy: PlanetEconomyPolicy = PlanetEconomyPolicy(), val units: UnitProductionPolicy = UnitProductionPolicy()) {
    init { require(suppliesPerDay >= 0 && maximumStock > 0) }
}

/** World-actor-owned. Lazy settlement gives the same result regardless of query frequency or logins. */
internal class PlanetProduction(private val galaxy: Galaxy, private val store: PlanetStockStore?,
    private val startSeconds: Long, private val policy: PlanetProductionPolicy, generation: Long = 1) {
    private val units = UnitProduction(galaxy, policy.units, policy.economy, policy.maximumStock)
    private val deployment = InitialOutfitDeployment(galaxy)
    private var current = store?.load()?.also { restored ->
        require(restored.bases.keys == galaxy.bases.map { it.id }.toSet()) { "Inventory bases differ from the world seed" }
        restored.bases.values.forEach(BaseOfficePolicy(galaxy)::validate)
        require(restored.generation <= generation) { "Inventory belongs to a newer session" }
        val terms = ProductionUnits.definitions.associate { it.key to it.hours }
        require(restored.bases.values.all { stock -> stock.units.work.all { (key, hours) ->
            terms[key]?.let { hours in 0..it } == true
        } }) { "Invalid production progress" }
    }?.let(::detached)
    private fun detached(stocks: PlanetStocks) = stocks.copy(bases = Collections.unmodifiableMap(LinkedHashMap(stocks.bases)),
        outfits = Collections.unmodifiableMap(LinkedHashMap(stocks.outfits)),
        warehouses = Collections.unmodifiableMap(LinkedHashMap(stocks.warehouses)),
        logisticsOfficers = Collections.unmodifiableMap(LinkedHashMap(stocks.logisticsOfficers)),
        nationalOffices = Collections.unmodifiableMap(LinkedHashMap(stocks.nationalOffices)))
    fun snapshot(generation: Long) = current?.takeIf { it.generation == generation }
    fun settle(generation: Long, seconds: Long): PlanetStocks {
        require(generation > 0 && seconds in startSeconds..0xffffffffL)
        val previous = current
        require(previous == null || previous.generation <= generation) { "Inventory belongs to a newer session" }
        val days = (seconds - startSeconds) / 86400
        val initial = ActiveShips.initialize(deployment.initialize(previous?.takeIf { it.generation == generation } ?: PlanetStocks(generation, 0,
            galaxy.conditions.mapValues { (_, base) -> PlanetStock(base.baseId, base.power, base.camp, base.supplies, base.food, 100_000) })))
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
                repeat(Math.toIntExact(days - initialized.producedDays)) {
                    next = units.advance(next.nextDay(galaxy.conditions.getValue(id), policy), next)
                }
                next
            })
        if (result == previous) return checkNotNull(previous)
        val next = detached(result)
        store?.save(next) // Publish only after durable replacement.
        current = next
        return next
    }

    /** Called only by the world actor after authorization; both sides share one durable replacement. */
    fun replace(expected: PlanetStocks, next: PlanetStocks): PlanetStocks {
        val result = prepareReplacement(expected, next)
        store?.save(result)
        publishCommitted(expected, result)
        return result
    }
    fun prepareReplacement(expected: PlanetStocks, next: PlanetStocks): PlanetStocks {
        check(current === expected) { "Inventory changed during transaction" }
        require(next.generation == expected.generation && next.producedDays == expected.producedDays)
        require(next.bases.keys == expected.bases.keys)
        return detached(next)
    }
    fun publishCommitted(expected: PlanetStocks, next: PlanetStocks) {
        check(current === expected)
        current = next
    }
}
