package org.logh7.gateway

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class MaintenanceGateTest {
    @TempDir lateinit var directory: Path

    @Test fun failedWritesKeepTheLiveGateUnchangedAndUnreadableStateDoesNotOpenTheWorld() {
        val state = directory.resolve("maintenance")
        val gate = MaintenanceGate(state)
        assertFalse(gate.closed())
        assertTrue(gate.setClosed(true))
        assertEquals("closed", Files.readString(state).trim())
        Files.delete(state)
        Files.createDirectory(state)
        assertFailsWith<IOException> { gate.setClosed(false) }
        assertTrue(gate.closed())
        assertTrue(gate.refuses())
        assertFailsWith<IOException> { MaintenanceGate(state) }
        Files.delete(state)
        Files.writeString(state, "invalid")
        assertFailsWith<IOException> { MaintenanceGate(state) }
    }
}
