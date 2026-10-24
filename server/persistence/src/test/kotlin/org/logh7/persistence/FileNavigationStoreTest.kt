package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Path
import kotlin.test.*

class FileNavigationStoreTest {
    @TempDir lateinit var directory: Path
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
