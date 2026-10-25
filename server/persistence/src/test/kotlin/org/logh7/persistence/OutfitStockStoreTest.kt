package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.*
import kotlin.test.*

class OutfitStockStoreTest {
    @TempDir lateinit var directory: Path
    private fun stocks() = PlanetStocks(7,23,
        mapOf(1000 to PlanetStock(1000,2,0,123,456,789,units=UnitInventory(mapOf(UnitStack(32) to 2)))),
        mapOf(1 to OutfitStock(1,2,OutfitKind.PATROL,1000,101,10,UnitInventory(mapOf(UnitStack(32) to 1))),
            2 to OutfitStock(2,2,OutfitKind.PATROL,1000,101,11)),
        mapOf(101 to OutfitWarehouse(101,2,OutfitKind.PATROL,1000,units=UnitInventory(troops=mapOf(UnitStack(3) to 5)),supplies=500,food=600,mineral=700)),
        mapOf(12 to LogisticsOffice.EMPIRE_SECOND))
    @Test fun combinedPlanetOutfitSharedWarehouseAndAuthoritySurviveReopening() {
        val path = directory.resolve("inventory")
        val expected = stocks()
        FilePlanetStockStore(path).use { it.save(expected) }
        FilePlanetStockStore(path).use { reopened ->
            val actual = reopened.load()!!
            assertEquals(expected,actual)
            assertEquals(actual.outfits.getValue(1).warehouseId,actual.outfits.getValue(2).warehouseId)
            assertEquals(500,actual.warehouses.getValue(101).supplies)
            assertEquals(LogisticsOffice.EMPIRE_SECOND,actual.logisticsOfficers[12])
        }
    }
    @Test fun truncatedMissingDuplicateAndBrokenReferencesFailWithoutResettingStock() {
        val path = directory.resolve("inventory")
        FilePlanetStockStore(path).use { it.save(stocks()) }
        val original = Files.readString(path)
        val lines = Files.readAllLines(path)
        val corrupt = listOf(
            lines.dropLast(1).joinToString("\n") + "\n",
            original + lines.last() + "\n",
            original.replace("O\t2\t2\tPATROL\t1000\t101", "O\t2\t2\tPATROL\t1000\t999"),
            original.replace("W\t101\t2\tPATROL", "W\t101\t3\tPATROL"),
            original.replace("O\t2\t2\tPATROL", "O\t1\t2\tPATROL"),
            original.replace("A\t12", "A\t0"),
            original.replace("W\t101", "X\t101"))
        for (text in corrupt) {
            Files.writeString(path,text)
            FilePlanetStockStore(path).use { assertFails { it.load() } }
            assertEquals(text,Files.readString(path))
        }
    }
    @Test fun oldCompletedProductionAndProgressArePreservedOnMigration() {
        val path = directory.resolve("old")
        Files.writeString(path,"LOGH7-STOCK-3\t7\t23\n1000\t2\t0\t123\t456\t789\t-\t-\t-\t-\tS32.0.2;T3.0.5;S32.48\n")
        val expected = FilePlanetStockStore(path).use { it.load()!!.also(it::save) }
        FilePlanetStockStore(path).use { reopened ->
            assertEquals(expected,reopened.load())
            assertEquals(48,reopened.load()!!.bases.getValue(1000).units.work["S32"])
            assertTrue(reopened.load()!!.outfits.isEmpty())
        }
    }
    @Test fun privateWarehousesCannotBeAliasedAndSharedWarehousesCannotBeSplit() {
        val value = stocks()
        assertFails { value.copy(outfits=value.outfits.mapValues { (_,o) -> o.copy(kind=OutfitKind.FLEET) },
            warehouses=value.warehouses.mapValues { (_,w) -> w.copy(kind=OutfitKind.FLEET) }) }
        assertFails { value.copy(outfits=value.outfits + (2 to value.outfits.getValue(2).copy(warehouseId=102)),
            warehouses=value.warehouses + (102 to OutfitWarehouse(102,2,OutfitKind.PATROL,1000))) }
        assertFails { value.copy(outfits=value.outfits + (2 to value.outfits.getValue(2).copy(baseId=1234))) }
    }
    @Test fun movingAnOutfitSelectsANewLocalWarehouseWithoutMovingTheOldWarehouseStock() {
        val original = PlanetStocks(1,0,
            mapOf(1000 to PlanetStock(1000,2,0,0,0,0),1001 to PlanetStock(1001,2,0,0,0,0)),
            mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,101,10)),
            mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1000,1,UnitInventory(mapOf(UnitStack(32) to 7)))))
        assertFails { original.copy(outfits=mapOf(1 to original.outfits.getValue(1).copy(baseId=1001))) }
        val moved = original.copy(outfits=mapOf(1 to original.outfits.getValue(1).copy(baseId=1001,warehouseId=102)),
            warehouses=original.warehouses + (102 to OutfitWarehouse(102,2,OutfitKind.FLEET,1001,1)))
        val path = directory.resolve("moved")
        FilePlanetStockStore(path).use { it.save(moved) }
        FilePlanetStockStore(path).use {
            val actual = it.load()!!
            assertEquals(7,actual.warehouses.getValue(101).units.ships[UnitStack(32)])
            assertTrue(actual.warehouses.getValue(actual.outfits.getValue(1).warehouseId).units.ships.isEmpty())
            assertEquals(1000,actual.warehouses.getValue(101).baseId)
        }
    }
    @Test fun numbersCarriedSuppliesAndDeploymentMarkerSurviveReopening() {
        val value = stocks().copy(outfits=stocks().outfits.mapValues { (id,o) -> o.copy(number=id,supplies=id*1000) },deploymentInitialized=true)
        val path = directory.resolve("version5")
        FilePlanetStockStore(path).use { it.save(value) }
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-5\t7\t23\t1\t2\t1\t1\t1\n"))
        FilePlanetStockStore(path).use { assertEquals(value,it.load()) }
    }
    @Test fun oldVersion4KeepsExistingDeploymentWithoutInventingNumbersOrSupplies() {
        val path = directory.resolve("version4")
        Files.writeString(path,"LOGH7-STOCK-4\t1\t0\t1\t1\t1\t0\n" +
            "P\t1000\t2\t0\t1\t2\t3\t-\t-\t-\t-\t;;\n" +
            "O\t1\t2\tFLEET\t1000\t101\t10\tS32.0.2;;\n" +
            "W\t101\t2\tFLEET\t1000\t1\t4\t5\t6\t;;\n")
        val old = FilePlanetStockStore(path).use { it.load()!! }
        assertFalse(old.deploymentInitialized)
        assertEquals(0,old.outfits.getValue(1).number); assertEquals(0,old.outfits.getValue(1).supplies)
        assertEquals(2,old.outfits.getValue(1).units.ships[UnitStack(32)])
        FilePlanetStockStore(path).use { it.save(old) }
        FilePlanetStockStore(path).use { assertEquals(old,it.load()) }
    }
    @Test fun patrolAndGroundShareTheSameLocalWarehouseAndCannotCreateParallelCopies() {
        val value = stocks()
        val combined = value.copy(outfits=value.outfits + (2 to value.outfits.getValue(2).copy(kind=OutfitKind.GROUND)))
        val path = directory.resolve("shared")
        FilePlanetStockStore(path).use { it.save(combined) }
        FilePlanetStockStore(path).use { assertEquals(combined,it.load()) }
        assertFails { combined.copy(outfits=combined.outfits + (2 to combined.outfits.getValue(2).copy(warehouseId=102)),
            warehouses=combined.warehouses + (102 to OutfitWarehouse(102,2,OutfitKind.GROUND,1000))) }
    }
}
