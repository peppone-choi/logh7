package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class FileSessionGenerationStoreTest {
    @TempDir lateinit var directory: Path
    @Test fun generationSurvivesProcessRestartAndStaleAdvanceIsRejected() {
        val file = directory.resolve("generation")
        val store = FileSessionGenerationStore(file)
        assertEquals(1L, store.current())
        assertEquals(2L, store.advance(1))
        val restored = FileSessionGenerationStore(file)
        assertEquals(2L, restored.current())
        assertFails { restored.advance(1) }
        assertEquals(2L, FileSessionGenerationStore(file).current())
        assertEquals(3L, restored.advance(2))
    }
    @Test fun invalidOrUnwritableMarkerDoesNotPublishANewGeneration() {
        val invalid = directory.resolve("invalid")
        Files.writeString(invalid, "0")
        assertFails { FileSessionGenerationStore(invalid) }
        val blocked = directory.resolve("blocked")
        val store = FileSessionGenerationStore(blocked)
        Files.createDirectory(blocked); Files.writeString(blocked.resolve("child"), "keep")
        assertFails { store.advance(1) }
        assertEquals(1L, store.current())
        assertEquals("keep", Files.readString(blocked.resolve("child")))
    }
}
