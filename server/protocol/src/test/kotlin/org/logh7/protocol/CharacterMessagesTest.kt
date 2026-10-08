package org.logh7.protocol

import kotlin.test.*
import org.junit.jupiter.api.Test

class CharacterMessagesTest {
    private val captured = java.util.HexFormat.of().parseHex(
        "10080000000000020200060041006c00700068006100000d005400650073007400500069006c006f00740041006c00700000000000000000000000000000000000000000000000000000000000")
    @Test fun capturedSyntheticNameRequestRoundTrip() {
        val data = CharacterMessages.generate(captured)
        assertEquals(0, data.category); assertEquals(0, data.id); assertEquals(2, data.power)
        assertEquals("Alpha", data.surname); assertEquals("TestPilotAlp", data.givenName)
        assertContentEquals(byteArrayOf(0, 0, 0, 0) + captured, CharacterMessages.generateReply(data))
    }
    @Test fun rejectsTruncationOverlongStringsEmbeddedNulAndTrailingBytes() {
        assertFails { CharacterMessages.generate(captured.copyOf(captured.size - 1)) }
        assertFails { CharacterMessages.generate(captured + byteArrayOf(0)) }
        assertFails { CharacterMessages.generate(captured.copyOf().also { it[10] = 14 }) }
        assertFails { CharacterMessages.generate(captured.copyOf().also { it[12] = 0 }) }
    }
}
