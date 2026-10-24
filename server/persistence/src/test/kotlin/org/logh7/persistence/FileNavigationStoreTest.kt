package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Path
import kotlin.test.*

class FileNavigationStoreTest {
    @TempDir lateinit var directory: Path
    @Test fun migratesOldRowsAndPersistsFacilityLocationAcrossReopen() {
        val path = directory.resolve("old-navigation")
        java.nio.file.Files.writeString(path, "LOGH7-NAV-1\npilot\t1\t10000\tEMPIRE\t2487\t1000\t960\t900\t1\n")
        lateinit var landed: Navigator
        FileNavigationStore(path).use { store ->
            val old = store.load().getValue("pilot")
            assertEquals(0, old.baseId); assertEquals(5, old.mode)
            landed = old.copy(baseId = 1049, mode = 0, spotId = 10_490_003)
            store.save(mapOf("pilot" to landed))
        }
        assertEquals("LOGH7-NAV-2", java.nio.file.Files.readAllLines(path).first())
        FileNavigationStore(path).use { assertEquals(landed, it.load().getValue("pilot")) }
    }
    @Test fun replacementSurvivesReopenAndExcludesOtherWriters() {
        val path = directory.resolve("navigation")
        val player = Navigator(1, 10000, Power.EMPIRE, 2488, 1000, 960, 900, 1)
        FileNavigationStore(path).use { store ->
            assertTrue(store.load().isEmpty())
            assertFails { FileNavigationStore(path) }
            store.save(mapOf("pilot@example" to player))
        }
        FileNavigationStore(path).use { assertEquals(mapOf("pilot@example" to player), it.load()) }
    }
    @Test fun truncatedStoreIsRejectedRatherThanResetToCapital() {
        val path = directory.resolve("navigation")
        java.nio.file.Files.writeString(path, "LOGH7-NAV-1\npilot\t1\t10000\tEMPIRE\t2488")
        FileNavigationStore(path).use { assertFails { it.load() } }
    }
}
