package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class ChatMessagesTest {
    private fun request(packet: ByteArray) = packet.copyOfRange(4, packet.size)
    @Test fun gridCarriesASeparateCastByteAndPackedUtf16() {
        val message = ChatMessages.Grid(10000, 2, "한글 日本 A")
        val packet = ChatMessages.grid(message, 0x12345678)
        assertEquals(16 + message.text.length * 2, packet.size)
        assertEquals(0x0f1c, ByteBuffer.wrap(packet).getShort(4).toInt())
        assertEquals(0x12345678, ByteBuffer.wrap(packet).getInt(6))
        assertEquals(10000, ByteBuffer.wrap(packet).getInt(10))
        assertEquals(2, packet[14].toInt())
        assertEquals(message.text.length, packet[15].toInt())
        assertEquals('한', ByteBuffer.wrap(packet).getChar(16))
        for (cast in 0..2) for (text in listOf("", "가".repeat(65), message.text)) {
            val value = message.copy(cast = cast, text = text)
            assertEquals(value, ChatMessages.grid(request(ChatMessages.grid(value, 0xffffffffL))))
        }
        val valid = request(packet)
        for (bytes in listOf(valid.copyOf(valid.size - 1), valid + byteArrayOf(0),
            valid.copyOf().also { it[10] = 3 }, valid.copyOf().also { it[11] = 66 },
            valid.copyOf().also { it[1] = 0x1d }))
            assertFailsWith<IllegalArgumentException> { ChatMessages.grid(bytes) }
        for (text in listOf("A\u0000", "A\nB", "가".repeat(66), "\ud800"))
            assertFailsWith<IllegalArgumentException> { ChatMessages.grid(message.copy(text = text), 0) }
    }
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
        val empty = ChatMessages.Spot(1, "")
        assertEquals(empty, ChatMessages.spot(request(ChatMessages.spot(empty, 0))))
    }
    @Test fun rejectsMalformedCountsTrailingDataAndInvalidText() {
        val valid = request(ChatMessages.spot(ChatMessages.Spot(1, "A"), 0))
        for (bytes in listOf(valid.copyOf(valid.size - 1), valid + byteArrayOf(0),
            valid.copyOf().also { it[10] = 66 }, valid.copyOf().also { it[0] = 0 }))
            assertFailsWith<IllegalArgumentException> { ChatMessages.spot(bytes) }
        for (text in listOf("A\u0000", "A\nB", "A".repeat(66), "\ud800"))
            assertFailsWith<IllegalArgumentException> { ChatMessages.spot(ChatMessages.Spot(1, text), 0) }
    }
}
