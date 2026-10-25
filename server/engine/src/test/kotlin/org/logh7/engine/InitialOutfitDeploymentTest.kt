package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class InitialOutfitDeploymentTest {
    private val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"ヴァルハラ",2),GalaxySystem(4,2015,4,"バーラト",3)),
        listOf(GalaxyBase(1049,2487,80,0,"オーディン",0,20f,86400,0,0f,2f),
            GalaxyBase(2000,2015,80,0,"ハイネセン",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private fun stocks() = PlanetStocks(1,7,galaxy.conditions.mapValues { (_,c) ->
        PlanetStock(c.baseId,c.power,0,123,456,789,units=UnitInventory(work=mapOf("S32" to 48))) })
    @Test fun manualRangesAndDesignedReservesHaveStableIdsAndSharedLocalWarehouses() {
        val deployment = InitialOutfitDeployment(galaxy)
        val initial = stocks()
        val seeded = deployment.initialize(initial)
        assertEquals(36,seeded.outfits.size)
        assertEquals(6,seeded.warehouses.size)
        assertEquals(initial.bases,seeded.bases); assertEquals(7,seeded.producedDays)
        assertTrue(seeded.deploymentInitialized)
        val empire = seeded.outfits.values.filter { it.power == 2 }
        assertEquals(setOf(1,2),empire.filter { it.kind == OutfitKind.FLEET }.map { it.number }.toSet())
        assertEquals((1..3).toSet() + 48 + (53..60).toSet(),empire.filter { it.kind == OutfitKind.PATROL }.map { it.number }.toSet())
        val shared = empire.filter { it.kind in setOf(OutfitKind.PATROL,OutfitKind.GROUND) }
        assertEquals(1,shared.map { it.warehouseId }.distinct().size)
        assertTrue(seeded.warehouses.values.all { it.units.ships.isEmpty() && it.units.troops.isEmpty() })
        assertTrue(deployment.placements.single { it.power == 2 && it.kind == OutfitKind.PATROL && it.number == 48 }.designedLocation)
        assertTrue(deployment.unmapped.isNotEmpty()) // A partial test world never redirects known remote deployments to a capital.
        assertEquals(seeded,InitialOutfitDeployment(galaxy).initialize(initial))
        assertFailsWith<UnsupportedOperationException> { (deployment.placements as MutableList).clear() }
    }
    @Test fun bothPowersReceiveTheirOwnShipTypesAndExactlyRequiredCrews() {
        val seeded = InitialOutfitDeployment(galaxy).initialize(stocks())
        for (outfit in seeded.outfits.values) {
            val products = ProductionUnits.definitions.filter { it.power == outfit.power }
            assertTrue(outfit.units.ships.keys.all { key -> products.any { it.ship && it.kind == key.kind } })
            assertTrue(outfit.units.troops.keys.all { key -> products.any { !it.ship && it.kind == key.kind } })
            assertTrue((outfit.units.ships.keys + outfit.units.troops.keys).all { it.level == 0 })
            val required = outfit.units.ships.entries.sumOf { (key,count) -> count * UnitSpecifications.ships.single { it.kind == key.kind }
                .number("必要乗組員ユニット数:必要乗組員ユニット数") }
            val crew = UnitSpecifications.crews.single { it.power == outfit.power && it.type == 3 }.kind
            assertEquals(required,outfit.units.troops.filterKeys { it.kind == crew }.values.sum())
            when (outfit.kind) {
                OutfitKind.FLEET -> { assertEquals(40,outfit.units.ships.values.sum()); assertEquals(100000,outfit.supplies) }
                OutfitKind.PATROL -> { assertEquals(2,outfit.units.ships.values.sum()); assertEquals(10000,outfit.supplies) }
                OutfitKind.GROUND -> { assertEquals(5,outfit.units.troops.values.sum()); assertEquals(1000,outfit.supplies) }
                else -> fail("Unexpected transport seed")
            }
        }
    }
    @Test fun laterLossesAndExistingOperatorDeploymentsAreNeverRefilled() {
        val deployment = InitialOutfitDeployment(galaxy)
        val seeded = deployment.initialize(stocks())
        val empty = seeded.copy(outfits=seeded.outfits.mapValues { (_,o) -> o.copy(units=UnitInventory(),supplies=0) })
        assertSame(empty,deployment.initialize(empty))
        val legacy = empty.copy(deploymentInitialized=false)
        val migrated = deployment.initialize(legacy)
        assertEquals(empty.outfits,migrated.outfits); assertTrue(migrated.deploymentInitialized)
        val removed = empty.copy(outfits=emptyMap(),warehouses=emptyMap())
        assertSame(removed,deployment.initialize(removed))
    }
    @Test fun freshGenerationResetsDeploymentButSameGenerationRetainsLosses() {
        val deployment = InitialOutfitDeployment(galaxy)
        var saved = deployment.initialize(stocks().copy(producedDays=0))
        saved = saved.copy(outfits=saved.outfits.mapValues { (_,o) -> o.copy(units=UnitInventory()) })
        val store = object : PlanetStockStore {
            override fun load() = saved
            override fun save(stocks: PlanetStocks) { saved = stocks }
        }
        val production = PlanetProduction(galaxy,store,0,PlanetProductionPolicy())
        assertTrue(production.settle(1,0).outfits.values.all { it.units.ships.isEmpty() })
        val next = production.settle(2,0)
        assertEquals(36,next.outfits.size)
        assertEquals(40,next.outfits.values.first { it.kind == OutfitKind.FLEET }.units.ships.values.sum())
        assertEquals(next,production.settle(2,0))
    }
}
