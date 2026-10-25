package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class PlaceMessagesTest {
    @Test fun switchModeUsesItsOwnPackedRecordAndBoundedLists() {
        val bytes = ByteBuffer.allocate(32).putShort(0x0b06).putInt(999).putInt(10000).putShort(59)
            .putInt(-1).putInt(-1).putShort(5).put(0).putInt(999).putInt(999).put(0).array()
        val command = PlaceMessages.SwitchMode(10000, 59, 5)
        assertEquals(command, PlaceMessages.switchMode(bytes))
        assertFailsWith<IllegalArgumentException> { PlaceMessages.switchMode(bytes.copyOf(31)) }
        assertFailsWith<IllegalArgumentException> { PlaceMessages.switchMode(bytes + 0) }
        assertFailsWith<IllegalArgumentException> { PlaceMessages.switchMode(bytes.copyOf().also { it[22] = 71 }) }
        assertFailsWith<IllegalArgumentException> { PlaceMessages.switchMode(bytes.copyOf().also { it[31] = 11 }) }
        val response = PlaceMessages.switched(command, 123, 990, 840, 5, 0, 10000, true)
        val decoded = PlaceMessages.switchMode(response.copyOfRange(4, response.size))
        assertEquals(command.copy(units = listOf(1010000), characters = listOf(10000)), decoded)
        assertEquals(990, ByteBuffer.wrap(response).getInt(16))
        assertEquals(840, ByteBuffer.wrap(response).getInt(20))
        assertEquals(1, ByteBuffer.wrap(PlaceMessages.switched(command, 123, 990, 840, 4, 10000807, 0, false)).int)
    }
    @Test fun fixedMoveReadersIgnoreClientBalancesAndMatchNativeFieldWidths() {
        val dock = PlaceMessages.Dock(10000, 59, 1049, 0)
        val response = PlaceMessages.docked(dock, 123, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(34, response.size)
        assertEquals(dock, PlaceMessages.dock(response.copyOfRange(4, response.size)))
        assertEquals(28800, ByteBuffer.wrap(PlaceMessages.docked(dock, 123, 1000, 840, waitSeconds = 28800)).getInt(10))
        assertEquals(0, ByteBuffer.wrap(PlaceMessages.docked(dock, 123, 1000, 840, false, 28800)).getInt(10))
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
