package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class ReturnBaseMessagesTest {
    @Test fun originalThreeWordRecordRejectsMalformedIdentityTargetAndLength() {
        val packet = ReturnBaseMessages.reply(10000, 1048, 0xffffffffL)
        val body = packet.copyOfRange(4, packet.size)
        assertEquals(ReturnBaseMessages.Selection(10000, 1048), ReturnBaseMessages.selection(body))
        assertFailsWith<IllegalArgumentException> { ReturnBaseMessages.selection(body.copyOf(13)) }
        assertFailsWith<IllegalArgumentException> { ReturnBaseMessages.selection(body + 0) }
        assertFailsWith<IllegalArgumentException> { ReturnBaseMessages.selection(body.copyOf().also { ByteBuffer.wrap(it).putInt(6, 0) }) }
        assertFailsWith<IllegalArgumentException> { ReturnBaseMessages.selection(body.copyOf().also { ByteBuffer.wrap(it).putInt(10, 65536) }) }
    }
}
