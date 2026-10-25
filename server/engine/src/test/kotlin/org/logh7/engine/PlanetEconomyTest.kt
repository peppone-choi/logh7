package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class PlanetEconomyTest {
    private val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Home", 2)),
        listOf(GalaxyBase(1000, 2487, 60, 0, "Home", 0, 20f, 86400, 0, 0f, 2f)),
        mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2487))
    private val base = galaxy.conditions.getValue(1000)
    private val initial = PlanetStock(1000, 2, 0, 100000, 1000000, 100000,
        PlanetEconomy(100000000, 60000000, 70, 80))
    @Test fun defaultCapitalEconomyStaysFedAndWithinWireBoundsForAWholeSession() {
        var state = initial.copy(food = 10000000, economy = PlanetEconomy(1000000000, 600000000, 70, 80))
        repeat(2400) {
            val next = state.nextDay(base, PlanetProductionPolicy())
            assertTrue(next.food > 0 && next.mineral > 0)
            assertTrue(next.economy!!.population > state.economy!!.population)
            assertEquals(70, next.economy!!.approval); assertEquals(80, next.economy!!.peace)
            assertEquals(state.supplies + 1000, next.supplies)
            state = next
        }
    }
    @Test fun foodLaborPopulationAndSuppliesUseOneDayAndConsumeRealMineral() {
        val next = initial.nextDay(base, PlanetProductionPolicy())
        assertEquals(1020000, next.food) // 120000 farm output - 100000 consumption.
        assertEquals(111900, next.mineral) // 12000 output - 100 used by supplies.
        assertEquals(101000, next.supplies)
        assertEquals(PlanetEconomy(100010000, 60006000, 70, 80), next.economy)
        val neutral = initial.copy(power = 0).nextDay(base, PlanetProductionPolicy())
        assertEquals(112000, neutral.mineral); assertEquals(initial.supplies, neutral.supplies)
    }
    @Test fun shortagesCannotCreateFoodOrSuppliesAndReducePeopleApprovalAndPeace() {
        val uninhabitable = base.copy(habitability = 0, atmosphere = 0)
        val empty = initial.copy(food = 0)
        val next = empty.nextDay(uninhabitable, PlanetProductionPolicy())
        assertEquals(0, next.food); assertEquals(initial.supplies, next.supplies)
        assertEquals(PlanetEconomy(99000000, 59400000, 68, 79), next.economy)
        val partial = empty.copy(food = 50000).nextDay(uninhabitable, PlanetProductionPolicy())
        assertEquals(99500000, partial.economy!!.population)
        val recovery = next.copy(food = 100000).nextDay(uninhabitable, PlanetProductionPolicy())
        assertEquals(69, recovery.economy!!.approval); assertEquals(80, recovery.economy!!.peace)
        assertEquals(99000000, recovery.economy!!.population) // A station does not grow population.
    }
    @Test fun materialLimitsEmptyPopulationCapsAndIntegerExtremesAreBounded() {
        val noLabor = initial.copy(mineral = 3, economy = PlanetEconomy(1, 0, 70, 80))
        assertEquals(initial.supplies, noLabor.nextDay(base, PlanetProductionPolicy()).supplies)
        val small = initial.copy(mineral = 3, economy = PlanetEconomy(1000, 1, 70, 80))
        val next = small.nextDay(base, PlanetProductionPolicy())
        assertEquals(100030, next.supplies); assertEquals(0, next.mineral)
        val capped = initial.nextDay(base, PlanetProductionPolicy(maximumStock = 100005))
        assertEquals(100005, capped.supplies); assertEquals(100004, capped.mineral)
        val extreme = initial.copy(food = 0, economy = PlanetEconomy(Int.MAX_VALUE, Int.MAX_VALUE, 0, 0))
            .nextDay(base.copy(habitability = 0), PlanetProductionPolicy(economy = PlanetEconomyPolicy(
                foodPeoplePerUnit = 1, dailyStarvationPerMillion = 1000000)))
        assertEquals(PlanetEconomy(0, 0, 0, 0), extreme.economy)
        val max = initial.copy(economy = PlanetEconomy(Int.MAX_VALUE, Int.MAX_VALUE, 100, 100))
            .nextDay(base, PlanetProductionPolicy())
        assertEquals(Int.MAX_VALUE, max.economy!!.population)
    }
    @Test fun oldWatermarkMigrationPartitionedCatchupReopenAndSaveFailureAreExact() {
        class Store : PlanetStockStore {
            var value = PlanetStocks(1, 23, mapOf(1000 to initial.copy(food = 456, mineral = 789, economy = null)))
            var fail = false
            override fun load() = value
            override fun save(stocks: PlanetStocks) { if (fail) throw java.io.IOException("disk"); value = stocks }
        }
        val store = Store()
        val production = PlanetProduction(galaxy, store, 0, PlanetProductionPolicy())
        val migrated = production.settle(1, 23 * 86400L)
        assertEquals(456, migrated.bases.getValue(1000).food)
        assertEquals(789, migrated.bases.getValue(1000).mineral)
        assertEquals(initial.economy, migrated.bases.getValue(1000).economy)
        store.fail = true
        assertFailsWith<java.io.IOException> { production.settle(1, 24 * 86400L) }
        assertEquals(migrated, production.snapshot(1))
        store.fail = false
        val allAtOnce = production.settle(1, 53 * 86400L)
        val partitionedStore = Store()
        var partitioned = PlanetProduction(galaxy, partitionedStore, 0, PlanetProductionPolicy())
        for (day in 23..53) {
            partitioned.settle(1, day * 86400L)
            partitioned = PlanetProduction(galaxy, partitionedStore, 0, PlanetProductionPolicy())
        }
        assertEquals(allAtOnce, partitionedStore.value)
        assertEquals(allAtOnce, production.settle(1, 23 * 86400L))
    }
}
