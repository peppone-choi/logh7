package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalStopMessagesTest {
    @Test fun packedStopContainsBothCountedIdListsAndRejectsTruncationOrTrailingBytes() {
        val stop = TacticalStopMessages.Stop(10000, listOf(1010000, 1010001), listOf(1049))
        val reply = TacticalStopMessages.reply(stop, 123)
        assertEquals(32, reply.size)
        val input = ByteBuffer.wrap(reply)
        assertEquals(0x040a, input.getShort(4).toInt())
        assertEquals(123, input.getInt(6)); assertEquals(0, input.getInt(10))
        assertEquals(2, input.get(18).toInt()); assertEquals(1, input.get(27).toInt())
        val request = reply.copyOfRange(4, reply.size)
        assertEquals(stop, TacticalStopMessages.stop(request))
        assertFailsWith<IllegalArgumentException> { TacticalStopMessages.stop(request.copyOf(request.size - 1)) }
        assertFailsWith<IllegalArgumentException> { TacticalStopMessages.stop(request + byteArrayOf(0)) }
        assertFailsWith<IllegalArgumentException> { TacticalStopMessages.stop(request.copyOf().also { it[14] = 33 }) }
        assertFailsWith<IllegalArgumentException> { TacticalStopMessages.Stop(1, listOf(2, 2)) }
        assertFailsWith<IllegalArgumentException> { TacticalStopMessages.Stop(1, emptyList()) }
        assertEquals(1, ByteBuffer.wrap(TacticalStopMessages.reply(stop, 123, false)).int)
    }
}
