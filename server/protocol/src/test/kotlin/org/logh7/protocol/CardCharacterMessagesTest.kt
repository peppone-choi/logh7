package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class CardCharacterMessagesTest {
    @Test fun nativeQueryHasOnlyOneUnsignedCardAndNoTargetOrPadding() {
        assertEquals(190,CardCharacterMessages.query(byteArrayOf(3,0x4e,0,190.toByte())))
        assertFails { CardCharacterMessages.query(byteArrayOf(3,0x4e,0,54,0)) }
        assertFails { CardCharacterMessages.query(byteArrayOf(3,0x4f,0,54)) }
        assertFails { CardCharacterMessages.query(byteArrayOf(3,0x4e,1,0)) }
    }
    @Test fun emptyRosterHasCardAndCountAndNativeLimitIsEnforced() {
        val empty=CardCharacterMessages.response(54,emptyList())
        assertEquals(9,empty.size)
        assertEquals(0x034f,ByteBuffer.wrap(empty).getShort(4).toInt())
        assertContentEquals(byteArrayOf(0,54,0),empty.copyOfRange(6,9))
        val record=LoginMessages.sessionMessage(0x0323,byteArrayOf(1,2,3,4))
        val full=CardCharacterMessages.response(190,List(64) { record })
        assertEquals(64,full[8].toInt())
        assertContentEquals(byteArrayOf(1,2,3,4),full.copyOfRange(9,13))
        assertFails { CardCharacterMessages.response(54,List(65) { record }) }
        assertFails { CardCharacterMessages.response(54,listOf(LoginMessages.sessionMessage(0x0356,byteArrayOf(0)))) }
    }
}
