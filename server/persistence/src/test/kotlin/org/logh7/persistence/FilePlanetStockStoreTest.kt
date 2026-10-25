package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.*
import kotlin.test.*

class FilePlanetStockStoreTest {
    @TempDir lateinit var directory: Path
    @Test fun oldStocksRetainTheirBalancesAndDayWithoutInventingPastPopulationChanges() {
        val path = directory.resolve("old")
        Files.writeString(path, "LOGH7-STOCK-1\t1\t23\n1000\t2\t0\t123\t456\t789\n")
        FilePlanetStockStore(path).use {
            val old = it.load()!!
            assertEquals(PlanetStock(1000, 2, 0, 123, 456, 789), old.bases.getValue(1000))
            assertEquals(23, old.producedDays)
            it.save(old)
        }
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-4\t1\t23\t1\t0\t0\t0"))
        FilePlanetStockStore(path).use { assertNull(it.load()!!.bases.getValue(1000).economy) }
    }
    @Test fun exactBalancesAndProductionWatermarkSurviveReopeningAndOtherWritersAreRejected() {
        val path = directory.resolve("stocks")
        val expected = PlanetStocks(7, 23, mapOf(1000 to PlanetStock(1000, 2, 0, 123000, 456, 789,
            PlanetEconomy(100000, 60000, 70, 80))))
        FilePlanetStockStore(path).use {
            assertNull(it.load()); assertFails { FilePlanetStockStore(path) }
            it.save(expected)
        }
        FilePlanetStockStore(path).use { assertEquals(expected, it.load()) }
    }
    @Test fun malformedOrTruncatedStocksNeverResetToInitialBalances() {
        for ((index, contents) in listOf("", "LOGH7-STOCK-4\t1\t0\n", "LOGH7-STOCK-1\t1\t0\n1000\t2\t0\t123\n",
            "LOGH7-STOCK-2\t1\t0\n1000\t2\t0\t1\t2\t3\t100\t101\t70\t80\n",
            "LOGH7-STOCK-2\t1\t0\n1000\t2\t0\t1\t2\t3\t-\t-\t70\t80\n",
            "LOGH7-STOCK-1\t1\t0\n1000\t2\t0\t-1\t0\t0\n",
            "LOGH7-STOCK-1\t1\t0\n1000\t2\t0\t1\t2\t3\n1000\t2\t0\t4\t5\t6\n").withIndex()) {
            val path = directory.resolve("bad-$index")
            Files.writeString(path, contents)
            FilePlanetStockStore(path).use { assertFails { it.load() } }
            assertEquals(contents, Files.readString(path))
        }
    }
}
