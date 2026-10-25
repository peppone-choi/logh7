package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class UnitProductionTest {
    private val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "ヴァルハラ", 2)),
        listOf(GalaxyBase(1049, 2487, 80, 0, "オーディン", 0, 20f, 86400, 0, 0f, 2f)),
        mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2487))
    private val policy = PlanetProductionPolicy()
    private fun producer() = UnitProduction(galaxy, policy.units, policy.economy, policy.maximumStock)
    private fun initial() = galaxy.conditions.getValue(1049).let {
        PlanetStock(1049, 2, 0, 100000, 10000000, 100000,
            PlanetEconomy(it.population, it.adultPopulation, it.approval, it.peace))
    }
    @Test fun sourceKindsTermsAndOdinRepeatedLinesRemainDistinctFromFlagships() {
        assertEquals(106, ProductionUnits.definitions.count { it.ship })
        assertEquals(11, ProductionUnits.definitions.count { !it.ship })
        assertEquals(90, ProductionUnits.find(2, "戦艦Ⅰ").hours)
        assertEquals(32, ProductionUnits.find(2, "戦艦Ⅰ").kind)
        assertEquals(119, ProductionUnits.find(3, "戦艦Ⅰ").kind)
        val lines = producer().lines.getValue(1049).associate { it.unit.key to it.batch }
        assertEquals(mapOf("S32" to 1, "S40" to 1, "S48" to 1, "S56" to 2, "S57" to 1, "T3" to 5, "T0" to 2), lines)
    }
    @Test fun fiveDaysBuildShipsAndGreenTroopsWhilePayingMaterialsAndRemovingCivilians() {
        val producer = producer(); var stock = initial()
        repeat(5) { stock = producer.advance(stock, stock) }
        assertTrue(stock.units.ships.getValue(UnitStack(32)) > 0)
        assertEquals(5, stock.units.troops.getValue(UnitStack(3)))
        assertEquals(4, stock.units.troops.getValue(UnitStack(0))) // 120h / 60h, batch two.
        assertEquals(1000000000 - 9000, stock.economy!!.population)
        assertEquals(600000000 - 9000, stock.economy!!.adults)
        assertEquals(10000000 - 900, stock.food)
        assertTrue(stock.mineral < 100000 && stock.supplies < 100000)
        assertTrue(stock.units.troops.keys.all { it.level == 0 })
        assertFailsWith<UnsupportedOperationException> { (stock.units.ships as MutableMap).clear() }
    }
    @Test fun allianceRecipesProduceTheirOwnKindsAndNeverEnemyShips() {
        val alliance = Galaxy(listOf(GalaxySystem(3, 2487, 4, "バーラト", 3)),
            listOf(GalaxyBase(2015, 2487, 80, 0, "ハイネセン", 0, 20f, 86400, 0, 0f, 2f)),
            mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2487))
        val producer = UnitProduction(alliance, policy.units, policy.economy, policy.maximumStock)
        val recipes = producer.lines.getValue(2015)
        assertTrue(recipes.any { it.unit.kind == 119 && it.unit.ship })
        assertTrue(recipes.any { it.unit.kind == 7 && !it.unit.ship })
        var stock = initial().copy(baseId = 2015, power = 3)
        repeat(10) { stock = producer.advance(stock, stock) }
        assertTrue(stock.units.ships.isNotEmpty() && stock.units.troops.isNotEmpty())
        assertTrue(stock.units.ships.keys.all { it.kind >= 119 })
        assertTrue(stock.units.troops.keys.all { it.kind in 4..7 || it.kind == 10 })
        val occupied = initial().copy(baseId = 2015, power = 2)
        assertEquals(occupied, producer.advance(occupied, occupied))
    }
    @Test fun fortressHasTheShipyardNeededByItsProductionRecipes() {
        val fortress = galaxy.bases.single().copy(kind = 1)
        assertTrue(Places.forBase(fortress, 2).any { it.kind == 10 })
    }
    @Test fun shortagesCapacityAndWrongOwnerDoNotMintUnitsOrChargeFailedBatches() {
        val producer = producer(); val before = initial()
        for (stock in listOf(before.copy(power = 3), before.copy(camp = 1),
            before.copy(food = 0, economy = before.economy!!.copy(adults = 1)))) {
            assertEquals(stock, producer.advance(stock, stock))
        }
        val material = before.copy(mineral = 0, supplies = 0)
        var waiting = material
        repeat(5) { waiting = producer.advance(waiting, waiting) }
        assertTrue(waiting.units.ships.isEmpty() && waiting.units.troops.isEmpty())
        assertEquals(0, waiting.supplies); assertEquals(0, waiting.mineral)
        assertEquals(material.economy, waiting.economy)
        assertEquals(90, waiting.units.work["S32"])
        val full = before.copy(units = UnitInventory(ships = mapOf(UnitStack(32) to 65535), work = mapOf("S32" to 90)))
        assertEquals(65535, producer.advance(full, full).units.ships[UnitStack(32)])
    }
    @Test fun durableDaysProgressAndBatchesAreIndependentOfQueriesAndRestartPartition() {
        class Store : PlanetStockStore {
            var value: PlanetStocks? = null; var fail = false
            override fun load() = value
            override fun save(stocks: PlanetStocks) { if (fail) throw java.io.IOException("disk"); value = stocks }
        }
        val a = Store(); val production = PlanetProduction(galaxy, a, 0, policy)
        val dayTwo = production.settle(1, 2 * 86400L)
        a.fail = true
        assertFailsWith<java.io.IOException> { production.settle(1, 3 * 86400L) }
        assertEquals(dayTwo, production.snapshot(1)); a.fail = false
        val final = production.settle(1, 10 * 86400L)
        val b = Store()
        for (day in 0..10) PlanetProduction(galaxy, b, 0, policy).settle(1, day * 86400L)
        assertEquals(final, b.value)
        assertEquals(final, production.settle(1, 10 * 86400L + 1))
        val fresh = production.settle(2, 0)
        assertTrue(fresh.bases.getValue(1049).units.ships.isEmpty())
    }
}
