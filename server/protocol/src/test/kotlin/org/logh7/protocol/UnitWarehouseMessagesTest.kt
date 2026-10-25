package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class UnitWarehouseMessagesTest {
    @Test fun nativeFiveByteRowsKeepBothListsAndCommodityOffsetsExact() {
        val bytes = WorldMessages.warehouse(1049, 1000, 2000, 3000,
            listOf(WorldMessages.Stock(32, 0, 3)), listOf(WorldMessages.Stock(3, 0, 5), WorldMessages.Stock(0, 2, 65535)))
        val input = ByteBuffer.wrap(bytes)
        assertEquals(1049, input.int); assertEquals(0, input.int); assertEquals(0, input.int)
        assertEquals(1, input.get().toInt())
        assertEquals(32, input.short.toInt()); assertEquals(0, input.get().toInt()); assertEquals(3, input.short.toInt())
        assertEquals(2, input.get().toInt())
        assertEquals(1003, input.short.toInt()); assertEquals(0, input.get().toInt()); assertEquals(5, input.short.toInt())
        assertEquals(1000, input.short.toInt()); assertEquals(2, input.get().toInt()); assertEquals(65535, input.short.toInt() and 65535)
        assertEquals(1000, input.int); assertEquals(2000, input.int); assertEquals(3000, input.int)
        assertFalse(input.hasRemaining())
        assertFails { WorldMessages.warehouse(1, 0, 0, 0, troops = listOf(WorldMessages.Stock(16, 0, 1))) }
        assertFails { WorldMessages.warehouse(1, 0, 0, 0, ships = List(100) { WorldMessages.Stock(it, 0, 1) }) }
    }
}
