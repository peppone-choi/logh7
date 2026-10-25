package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Path
import kotlin.test.*

class UnitInventoryCodecTest {
    @TempDir lateinit var directory: Path
    @Test fun quantitiesLevelsAndUnfinishedWorkSurviveReopenTogether() {
        val units = UnitInventory(mapOf(UnitStack(32) to 3, UnitStack(119, 2) to 65535), mapOf(UnitStack(3) to 5),
            mapOf("S32" to 48, "T3" to 96))
        assertEquals(units, UnitInventoryCodec.decode(UnitInventoryCodec.encode(units)))
        val expected = PlanetStocks(1, 2, mapOf(1049 to PlanetStock(1049, 2, 0, 456, 789, 123,
            PlanetEconomy(1000000, 600000, 70, 80), units)))
        val path = directory.resolve("stock")
        FilePlanetStockStore(path).use { it.save(expected) }
        FilePlanetStockStore(path).use { assertEquals(expected, it.load()) }
        for (bad in listOf("", "S32.0.3,S32.0.2;;", "S32.0.65536;;", ";T16.0.1;", ";;S32.-1", ";;S32.48,S32.48"))
            assertFails { UnitInventoryCodec.decode(bad) }
    }
}
