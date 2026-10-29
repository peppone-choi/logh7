package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class OfflineDirectionMessagesTest {
    @Test fun originalThirteenBytePayloadRetainsFiveSwitchesAndUsesServerClock() {
        val request=byteArrayOf(0x0f,0x18,0,0,0,99,0,0,0x27,0x10,1,0,1,0,1)
        val command=OfflineDirectionMessages.settings(request)
        assertEquals(10000,command.character);assertEquals(21,command.mask)
        assertContentEquals(byteArrayOf(0,0,0,0,0x0f,0x18,0,0,0,42,0,0,0x27,0x10,1,0,1,0,1),
            OfflineDirectionMessages.reply(command,42))
        assertEquals(1,ByteBuffer.wrap(OfflineDirectionMessages.reply(command,42,false)).int)
        assertFails {OfflineDirectionMessages.settings(request+byteArrayOf(0))}
        assertFails {OfflineDirectionMessages.settings(request.copyOf().also {it[14]=2})}
    }
}
