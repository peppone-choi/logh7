package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class GridForceMessagesTest {
    @Test fun searchMatchesNativeReaderWithoutStructPadding() {
        val input = ByteBuffer.wrap(GridForceMessages.search(123, 10000, 3, 0, mapOf(2017 to 2, 2015 to 41)))
        assertEquals(0, input.int); assertEquals(0x0b0d, input.short.toInt())
        assertEquals(123, input.int); assertEquals(0, input.int); assertEquals(10000, input.int)
        assertEquals(2, input.get().toInt())
        for ((grid, count) in listOf(2015 to 41, 2017 to 2)) {
            assertEquals(grid, input.short.toInt()); assertEquals(1, input.get().toInt())
            assertEquals(3, input.get().toInt()); assertEquals(0, input.get().toInt())
            assertEquals(count, input.short.toInt())
        }
        assertFalse(input.hasRemaining())
    }
    @Test fun refusesNativeOverflowAndPhantomZeroGroups() {
        assertFails { GridForceMessages.search(0, 1, 2, 0, emptyMap()) }
        assertFails { GridForceMessages.search(0, 1, 2, 0, mapOf(0 to 0)) }
        assertFails { GridForceMessages.search(0, 1, 2, 0, mapOf(5000 to 1)) }
        assertFails { GridForceMessages.search(0, 1, 2, 0, mapOf(0 to 65536)) }
        assertFails { GridForceMessages.search(0, 1, 2, 0, (0..225).associateWith { 1 }) }
        assertFails { GridForceMessages.search(0, 1, 2, 2, mapOf(0 to 1)) }
        assertEquals(19 + 225 * 7, GridForceMessages.search(0, 1, 2, 0, (0..224).associateWith { 65535 }).size)
    }
}
