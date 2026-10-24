package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.*
import kotlin.test.*

class FilePlanetStockStoreTest {
    @TempDir lateinit var directory: Path
    @Test fun exactBalancesAndProductionWatermarkSurviveReopeningAndOtherWritersAreRejected() {
        val path = directory.resolve("stocks")
        val expected = PlanetStocks(7, 23, mapOf(1000 to PlanetStock(1000, 2, 0, 123000, 456, 789)))
        FilePlanetStockStore(path).use {
            assertNull(it.load()); assertFails { FilePlanetStockStore(path) }
            it.save(expected)
        }
        FilePlanetStockStore(path).use { assertEquals(expected, it.load()) }
    }
    @Test fun malformedOrTruncatedStocksNeverResetToInitialBalances() {
        for ((index, contents) in listOf("", "LOGH7-STOCK-2\t1\t0\n", "LOGH7-STOCK-1\t1\t0\n1000\t2\t0\t123\n",
            "LOGH7-STOCK-1\t1\t0\n1000\t2\t0\t-1\t0\t0\n",
            "LOGH7-STOCK-1\t1\t0\n1000\t2\t0\t1\t2\t3\n1000\t2\t0\t4\t5\t6\n").withIndex()) {
            val path = directory.resolve("bad-$index")
            Files.writeString(path, contents)
            FilePlanetStockStore(path).use { assertFails { it.load() } }
            assertEquals(contents, Files.readString(path))
        }
    }
}
