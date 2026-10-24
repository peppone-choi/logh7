package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class PlaceMessagesTest {
    @Test fun fixedMoveReadersIgnoreClientBalancesAndMatchNativeFieldWidths() {
        val dock = PlaceMessages.Dock(10000, 59, 1049, 0)
        val response = PlaceMessages.docked(dock, 123, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(34, response.size)
        assertEquals(dock, PlaceMessages.dock(response.copyOfRange(4, response.size)))
        val move = PlaceMessages.Move(10000, 10_490_003, 0)
        val moved = PlaceMessages.moved(move, 123, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(move, PlaceMessages.move(moved.copyOfRange(4, moved.size)))
        assertEquals(1, ByteBuffer.wrap(PlaceMessages.moved(move, 123, 0, 0, false)).int)
        assertFailsWith<IllegalArgumentException> { PlaceMessages.move(byteArrayOf(14, 0)) }
        assertFailsWith<IllegalArgumentException> { PlaceMessages.moved(move, 123, -1, 0) }
        assertFailsWith<IllegalArgumentException> { PlaceMessages.docked(dock, 0x1_0000_0000L, 0, 0) }
        val notify = ByteBuffer.wrap(PlaceMessages.dockNotification(10000, 1049, 0, 123)).position(6)
        assertEquals(123, notify.int); assertEquals(10000, notify.int); assertEquals(1049, notify.int)
        assertEquals(0, notify.short.toInt()); assertEquals(0, notify.int); assertEquals(10000, notify.int)
        assertEquals(1, notify.get().toInt()); assertEquals(10000, notify.int); assertFalse(notify.hasRemaining())
    }
}
