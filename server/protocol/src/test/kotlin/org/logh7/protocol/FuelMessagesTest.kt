package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class FuelMessagesTest {
    @Test fun readsTheCdRecordWithoutPaddingAndRejectsOtherLayouts() {
        val wire = ByteBuffer.allocate(24).putShort(0x0b02).putInt(-1).putInt(-1).putInt(10000)
            .putShort(195).putInt(-1).putInt(-1).array()
        assertEquals(FuelMessages.Supply(10000, 195), FuelMessages.supply(wire))
        assertFailsWith<IllegalArgumentException> { FuelMessages.supply(wire.copyOf(23)) }
        assertFailsWith<IllegalArgumentException> { FuelMessages.supply(wire.copyOf(25)) }
        assertFailsWith<IllegalArgumentException> { FuelMessages.supply(wire.copyOf().also { ByteBuffer.wrap(it).putInt(10, 0) }) }
    }
    @Test fun ackAndCompletionHaveTheLengthsAndFieldsConsumedByTheOriginalReaders() {
        val command = FuelMessages.Supply(10000, 59)
        val ack = ByteBuffer.wrap(FuelMessages.reply(command, 100, 1000, 840, true))
        assertEquals(28, ack.limit()); assertEquals(0, ack.int); assertEquals(0x0b02, ack.short.toInt())
        assertEquals(100, ack.int); assertEquals(28800, ack.int); assertEquals(10000, ack.int)
        assertEquals(59, ack.short.toInt()); assertEquals(1000, ack.int); assertEquals(840, ack.int)
        assertEquals(1, ByteBuffer.wrap(FuelMessages.reply(command, 100, 1000, 840, false)).int)
        val completed = ByteBuffer.wrap(FuelMessages.supplied(200, 10000, 1010000, 1000))
        assertEquals(27, completed.limit()); assertEquals(0, completed.int); assertEquals(0x0b0c, completed.short.toInt())
        assertEquals(200, completed.int); assertEquals(0, completed.int); assertEquals(10000, completed.int)
        assertEquals(1, completed.get().toInt()); assertEquals(1010000, completed.int); assertEquals(1000, completed.int)
    }
}
