package org.logh7.protocol

import java.util.HexFormat
import org.junit.jupiter.api.Test
import kotlin.test.*

class StrategicSearchMessagesTest {
    private val hex = HexFormat.of()

    @Test fun readsOnlyTheClaimedIdentityFromAnExactCommandRecord() {
        val message = hex.parseHex("0b03 ffffffff ffffffff 0000271a ffffffff ffffffff".replace(" ", ""))
        assertEquals(10010, StrategicSearchMessages.requester(message))
        assertFailsWith<IllegalArgumentException> { StrategicSearchMessages.requester(message.copyOf(21)) }
        assertFailsWith<IllegalArgumentException> { StrategicSearchMessages.requester(message.copyOf(23)) }
        assertFailsWith<IllegalArgumentException> {
            StrategicSearchMessages.requester(message.copyOf().also { it[1] = 2 })
        }
    }

    @Test fun acknowledgmentUsesPackedWordsWithNoCardOrGrid() {
        val reply = StrategicSearchMessages.reply(10010, 0x89abcdef, 7, 990, 1000)
        assertEquals("000000000b0389abcdef000000070000271a000003de000003e8", hex.formatHex(reply))
    }
}
