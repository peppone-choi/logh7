package org.logh7.gateway

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import org.logh7.protocol.Envelope
import org.logh7.protocol.Frames
import org.logh7.protocol.LegacyBlowfish
import kotlin.test.*

class HandshakeSendTest {
    private val peerKey = ByteArray(16) { it.toByte() }
    private val serverKey = ByteArray(16) { (0x80 + it).toByte() }

    private fun established(sequence: Long): Handshake {
        val wrapping = LegacyBlowfish(LegacyBlowfish.HANDSHAKE_KEY.toByteArray(Charsets.US_ASCII))
        fun sealed(plain: ByteArray): ByteArray {
            ByteBuffer.wrap(plain).putShort(Envelope.checksum(plain.copyOfRange(2, plain.size)).toShort())
            return wrapping.encrypt(plain)
        }
        return Handshake(serverKey, sequence).also {
            it.accept(0x34, sealed(ByteBuffer.allocate(24).putShort(0).putShort(16).put(peerKey).putInt(1).array()))
            it.accept(0x36, sealed(ByteBuffer.allocate(20).putShort(0).putShort(16).put(serverKey).array()))
        }
    }

    private fun decode(frame: ByteArray, clearHeader: Int?): Pair<Long, ByteArray> {
        val input = ByteBuffer.wrap(frame)
        assertEquals(frame.size - 2, input.short.toInt() and 0xffff)
        if (clearHeader != null) assertEquals(clearHeader, input.int)
        assertEquals(0x30, input.short.toInt() and 0xffff)
        val ciphertext = ByteArray(input.remaining()).also { input.get(it) }
        return Envelope.decode(LegacyBlowfish(serverKey).decrypt(ciphertext), -1)
    }

    @Test fun rejectedFrameDoesNotConsumeSendSequence() {
        for (clearHeader in listOf(null, 0x01020304)) {
            val handshake = established(2)
            // ECB alignment takes this envelope past the socket payload limit.
            assertFailsWith<IllegalArgumentException> { handshake.send(ByteArray(0xEFF1), clearHeader) }
            val body = byteArrayOf(0x70, 1)
            val (sequence, decodedBody) = decode(handshake.send(body, clearHeader), clearHeader)
            assertEquals(2L, sequence)
            assertContentEquals(body, decodedBody)
            assertEquals(3L, decode(handshake.send(body, clearHeader), clearHeader).first)
        }
    }

    @Test fun rejectedEnvelopeDoesNotConsumeSendSequence() {
        val handshake = established(2)
        assertFailsWith<IllegalArgumentException> { handshake.send(ByteArray(0x10000)) }
        assertEquals(2L, decode(handshake.send(byteArrayOf(0x70, 1)), null).first)
    }

    @Test fun rejectedFramePreservesLastSequenceBeforeRekey() {
        val handshake = established(0x7fffffff)
        assertFailsWith<IllegalArgumentException> { handshake.send(ByteArray(0xEFF1)) }
        assertEquals(0x7fffffffL, decode(handshake.send(byteArrayOf(0x70, 1)), null).first)
        val error = assertFailsWith<IllegalStateException> { handshake.send(byteArrayOf(0x70, 1)) }
        assertEquals("Rekey required but not implemented", error.message)
    }

    @Test fun largestAlignedEnvelopeFitsBothFrameHeaders() {
        for (clearHeader in listOf(null, 0x01020304)) {
            val handshake = established(2)
            val body = ByteArray(0xEFF0) { it.toByte() }
            val frame = handshake.send(body, clearHeader)
            assertTrue(frame.size - 2 <= Frames.MAX_PAYLOAD)
            val (sequence, decodedBody) = decode(frame, clearHeader)
            assertEquals(2L, sequence)
            assertContentEquals(body, decodedBody)
        }
    }
}
