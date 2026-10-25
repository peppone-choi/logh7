package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class ChatMessagesTest {
    private fun request(packet: ByteArray) = packet.copyOfRange(4, packet.size)
    @Test fun spotUsesPackedBigEndianFieldsAndAnUnterminatedUtf16Count() {
        val message = ChatMessages.Spot(10000, "안녕 A")
        val packet = ChatMessages.spot(message, 0x12345678)
        assertEquals(23, packet.size)
        assertEquals(0x0f1d, ByteBuffer.wrap(packet).getShort(4).toInt())
        assertEquals(0x12345678, ByteBuffer.wrap(packet).getInt(6))
        assertEquals(10000, ByteBuffer.wrap(packet).getInt(10))
        assertEquals(4, packet[14].toInt())
        assertEquals('안', ByteBuffer.wrap(packet).getChar(15))
        assertEquals(message, ChatMessages.spot(request(packet)))
        val maximum = ChatMessages.Spot(10000, "가".repeat(65))
        assertEquals(maximum, ChatMessages.spot(request(ChatMessages.spot(maximum, 0xffffffffL))))
    }
    @Test fun rejectsMalformedCountsTrailingDataAndInvalidText() {
        val valid = request(ChatMessages.spot(ChatMessages.Spot(1, "A"), 0))
        for (bytes in listOf(valid.copyOf(valid.size - 1), valid + byteArrayOf(0),
            valid.copyOf().also { it[10] = 66 }, valid.copyOf().also { it[0] = 0 }))
            assertFailsWith<IllegalArgumentException> { ChatMessages.spot(bytes) }
        for (text in listOf("", " ", "A\u0000", "A\nB", "A".repeat(66), "\ud800"))
            assertFailsWith<IllegalArgumentException> { ChatMessages.spot(ChatMessages.Spot(1, text), 0) }
    }
}
