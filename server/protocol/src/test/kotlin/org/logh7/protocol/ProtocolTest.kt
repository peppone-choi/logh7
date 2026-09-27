package org.logh7.protocol

import kotlin.test.*
import org.junit.jupiter.api.Test
import org.logh7.protocol.generated.MessageId

class ProtocolTest {
    @Test fun frameLimitsAndByteOrder() {
        assertContentEquals(byteArrayOf(0, 3, 0, 0x34, 7), Frames.encode(0x34, byteArrayOf(7)))
        assertEquals(0xF002, Frames.encode(0x30, ByteArray(0xEFFE)).size)
        assertFailsWith<IllegalArgumentException> { Frames.encode(0x30, ByteArray(0xEFFF)) }
        assertFailsWith<IllegalArgumentException> { Frames.decodePayload(byteArrayOf(0, 0x32)) }
    }
    @Test fun envelopeRejectsReplayAndCorruption() {
        val bytes = Envelope.encode(0xffffffffL, byteArrayOf(1, 2, 3))
        val (seq, body) = Envelope.decode(bytes, 0)
        assertEquals(0xffffffffL, seq); assertContentEquals(byteArrayOf(1, 2, 3), body)
        assertFailsWith<IllegalArgumentException> { Envelope.decode(bytes, seq) }
        bytes[8] = 9
        assertFailsWith<IllegalArgumentException> { Envelope.decode(bytes, 0) }
    }
    @Test fun cipherRoundTripAndMask() {
        val key = LegacyBlowfish.HANDSHAKE_KEY.toByteArray(Charsets.US_ASCII)
        val cipher = LegacyBlowfish(key)
        val plain = ByteArray(17) { it.toByte() }
        assertContentEquals(plain.copyOf(24), cipher.decrypt(cipher.encrypt(plain)))
        assertContentEquals(key, LegacyBlowfish.maskStoredKey(LegacyBlowfish.maskStoredKey(key)))
    }
    @Test fun generatedOpcodeCodec() {
        assertContentEquals(byteArrayOf(0x70, 0), MessageId.LGLoginRequest.encodeOpcode())
        assertEquals(MessageId.LGLoginOK, MessageId.decodeOpcode(MessageId.LGLoginOK.encodeOpcode()))
    }
}
