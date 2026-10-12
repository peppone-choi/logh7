package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.protocol.*
import java.nio.ByteBuffer
import kotlin.test.*

/** Static source contract; aux=0 and keys are synthetic, not original-client interoperability. */
class HandshakeRekeyTest {
    private val a = ByteArray(16) { it.toByte() }
    private val b = ByteArray(16) { (240 + it).toByte() }
    private val c = ByteArray(16) { (64 + it).toByte() }
    private fun hex(value: String) = java.util.HexFormat.of().parseHex(value)
    private fun established(next: Long = 2, key: () -> ByteArray = { c },
        cipher: (ByteArray) -> LegacyBlowfish = { LegacyBlowfish(it) }): Handshake = Handshake(b, next, key, cipher).also {
        it.accept(0x34, hex("35eb18155d09bbb52a8d5c7fcef57d7712d9f1f6a2bebede"))
        it.accept(0x36, hex("385c1e213879e5fc44d62157e541b061e556d88f3a5ae35b"))
    }
    private fun incoming(key: ByteArray, seq: Long, body: ByteArray) =
        LegacyBlowfish(key).encrypt(Envelope.encode(seq, body))
    private fun frames(bytes: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val size = (ByteBuffer.wrap(bytes, offset, 2).short.toInt() and 65535) + 2
            require(size <= bytes.size - offset)
            frames += bytes.copyOfRange(offset, offset + size)
            offset += size
        }
        return frames
    }
    private fun decode(frame: ByteArray, key: ByteArray, previous: Long = 0): Pair<Long, ByteArray> {
        assertEquals(0, ByteBuffer.wrap(frame, 2, 4).int) // synthetic outer aux
        assertEquals(0x30, ByteBuffer.wrap(frame, 6, 2).short.toInt())
        return Envelope.decode(LegacyBlowfish(key).decrypt(frame.copyOfRange(8, frame.size)), previous)
    }
    @Test fun unsignedBoundaryEmitsOldKeyControlThenNewKeySequenceOne() {
        val h = established(0x7fffffff)
        val app = LoginMessages.sessionMessage(0x0301, byteArrayOf(1))
        assertEquals(0x7fffffffL, decode(h.send(app, 0), b).first)
        val crossing = frames(h.send(app, 0))
        assertEquals(2, crossing.size)
        assertEquals(0x80000000L, decode(crossing[0], b).first)
        val (seq, control) = decode(crossing[1], b)
        assertEquals(0x80000001L, seq)
        assertEquals(0x31, ByteBuffer.wrap(control, 4, 2).short.toInt())
        val nextKey = control.copyOfRange(6, control.size)
        assertEquals(16, nextKey.size)
        val (newSeq, newBody) = decode(h.send(app, 0), nextKey)
        assertEquals(1L, newSeq); assertContentEquals(app, newBody)
    }
    @Test fun incomingControlIsConsumedThenAcceptsNewKeySequenceOne() {
        val h = established()
        val control = byteArrayOf(0, 0x31) + c
        assertNull(h.receive(0x30, incoming(a, 1, control)))
        val app = byteArrayOf(3, 0)
        assertContentEquals(app, h.receive(0x30, incoming(c, 1, app)))
        assertEquals(2L, decode(h.send(LoginMessages.sessionMessage(0x0301, byteArrayOf()), 0), b).first)
    }
    @Test fun oneBatchSpansBoundaryAndStagesWithoutCommitting() {
        val h = established(0x80000000)
        val app = LoginMessages.sessionMessage(0x0301, byteArrayOf(1))
        val batch = h.stageAll(listOf(app, app), 0)
        val wire = frames(batch.bytes)
        assertEquals(3, wire.size)
        assertEquals(0x80000000L, decode(wire[0], b).first)
        assertEquals(0x80000001L, decode(wire[1], b).first)
        assertContentEquals(LoginMessages.sessionMessage(0x31, c), decode(wire[1], b).second)
        assertEquals(1L, decode(wire[2], c).first)
        assertEquals(0x80000000L, h.committedSequence)
        h.submitting(batch); h.committed(batch)
        assertEquals(2L, h.committedSequence); assertEquals(0, h.pendingBatches)
    }
    @Test fun exhaustedAndUnsupportedLoginBatchesDoNotStage() {
        val app = LoginMessages.sessionMessage(0x0301, byteArrayOf())
        val exhausted = established(0xffffffffL)
        assertFails { exhausted.stageAll(listOf(app), 0) }
        assertEquals(0xffffffffL, exhausted.committedSequence); assertEquals(0, exhausted.pendingBatches)
        val login = established(0x80000000)
        assertFails { login.stageAll(listOf(byteArrayOf(0x70, 1))) }
        assertEquals(0, login.pendingBatches)
    }
    @Test fun failedMultiMessageEncodingPreservesThePreviousCandidateAndId() {
        val h = established()
        val app = LoginMessages.sessionMessage(0x0301, byteArrayOf())
        val first = h.stageAll(listOf(app), 0)
        assertFails { h.stageAll(listOf(app, ByteArray(Frames.MAX_PAYLOAD)), 0) }
        val second = h.stageAll(listOf(app), 0)
        assertEquals(first.id!! + 1, second.id)
        assertEquals(3L, decode(frames(second.bytes).single(), b).first)
        assertEquals(2, h.pendingBatches)
    }
    @Test fun incomingKeyBoundsAndTruncatedOpcodesPreserveTheOldCipherOnRejection() {
        for (size in listOf(1, 56)) {
            val h = established(); val key = ByteArray(size) { (it + 1).toByte() }
            assertNull(h.receive(0x30, incoming(a, 1, byteArrayOf(0, 0x31) + key)))
            assertContentEquals(byteArrayOf(3, 0), h.receive(0x30, incoming(key, 1, byteArrayOf(3, 0))))
        }
        for (body in listOf(byteArrayOf(0), byteArrayOf(0, 0x31), byteArrayOf(0, 0x31) + ByteArray(57))) {
            val h = established()
            assertFailsWith<IllegalArgumentException> { h.receive(0x30, incoming(a, 1, body)) }
            assertContentEquals(byteArrayOf(3, 0), h.receive(0x30, incoming(a, 1, byteArrayOf(3, 0))))
        }
    }
    @Test fun invalidChecksumAndReplayCannotReplaceTheIncomingKey() {
        val h = established()
        assertNotNull(h.receive(0x30, incoming(a, 1, byteArrayOf(3, 0))))
        assertFails { h.receive(0x30, incoming(a, 1, byteArrayOf(0, 0x31) + c)) }
        val damaged = Envelope.encode(2, byteArrayOf(0, 0x31) + c).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFails { h.receive(0x30, LegacyBlowfish(a).encrypt(damaged)) }
        assertContentEquals(byteArrayOf(3, 0), h.receive(0x30, incoming(a, 2, byteArrayOf(3, 0))))
    }
    @Test fun keyFactoryFailureClosesReceiveAndLeavesNoCandidates() {
        val h = established(cipher = { error("synthetic factory failure") })
        h.stageAll(listOf(LoginMessages.sessionMessage(0x0301, byteArrayOf())), 0)
        val failure = assertFails { h.receive(0x30, incoming(a, 1, byteArrayOf(0, 0x31) + c)) }
        assertEquals("Rekey cipher unavailable", failure.message)
        assertEquals(Handshake.State.CLOSED, h.state); assertEquals(0, h.pendingBatches)
    }
    @Test fun failedOutgoingFactoryDoesNotPublishItsPartialBatch() {
        for (key in listOf<() -> ByteArray>({ ByteArray(15) }, { error("synthetic key failure") })) {
            val h = established(0x80000000, key)
            assertFails { h.stageAll(listOf(LoginMessages.sessionMessage(0x0301, byteArrayOf())), 0) }
            assertEquals(0x80000000L, h.committedSequence); assertEquals(0, h.pendingBatches)
        }
        val h = established(0x80000000, cipher = { error("synthetic cipher failure") })
        assertFails { h.stageAll(listOf(LoginMessages.sessionMessage(0x0301, byteArrayOf())), 0) }
        assertEquals(0x80000000L, h.committedSequence); assertEquals(0, h.pendingBatches)
    }
    @Test fun pendingMetadataIsBoundedAndCloseDiscardsAllCandidates() {
        val h = established()
        repeat(1024) { h.stageAll(listOf(LoginMessages.sessionMessage(0x0301, byteArrayOf())), 0) }
        assertFails { h.stageAll(listOf(LoginMessages.sessionMessage(0x0301, byteArrayOf())), 0) }
        assertEquals(1024, h.pendingBatches); assertEquals(2L, h.committedSequence)
        h.close(); assertEquals(0, h.pendingBatches)
    }
}
