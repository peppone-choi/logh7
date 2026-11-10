package org.logh7.protocol

import java.util.HexFormat
import org.junit.jupiter.api.Test
import kotlin.test.*

class StrategicSearchMessagesTest {
    private val hex = HexFormat.of()

    @Test fun readsOnlyTheClaimedIdentityFromAnExactCommandRecord() {
        val message = hex.parseHex("0b03 ffffffff ffffffff 0000271a ffffffff ffffffff".replace(" ", ""))
        assertEquals(StrategicSearchMessages.Request(10010, false), StrategicSearchMessages.request(message))
        assertFailsWith<IllegalArgumentException> { StrategicSearchMessages.request(message.copyOf(21)) }
        assertFailsWith<IllegalArgumentException> { StrategicSearchMessages.request(message.copyOf(23)) }
        assertFailsWith<IllegalArgumentException> {
            StrategicSearchMessages.request(message.copyOf().also { it[1] = 2 })
        }
    }

    @Test fun clearRequiresExplicitCapabilityAndUsesOneZeroShipRow() {
        val request = hex.parseHex("0b034c374753434c52310000271a0000000000000000")
        assertEquals(StrategicSearchMessages.Request(10010, true), StrategicSearchMessages.request(request))
        request[9] = 0
        assertFalse(StrategicSearchMessages.request(request).supportsClear)
        val clear = GridForceMessages.clearSearch(9, 10010, 3, 0, 2487)
        assertEquals("000000000b0d00000009434c52310000271a0109b70103000000", hex.formatHex(clear))
    }

    @Test fun acknowledgmentUsesPackedWordsWithNoCardOrGrid() {
        val reply = StrategicSearchMessages.reply(10010, 0x89abcdef, 7, 990, 1000)
        assertEquals("000000000b0389abcdef000000070000271a000003de000003e8", hex.formatHex(reply))
    }
}
