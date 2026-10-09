package org.logh7.gateway

import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.security.SecureRandom

/** evidence:client — kex-envelope.md; responder B validated with the original client on the host. Rekey pending. */
class Handshake(key: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }, initialSequence: Long = 1) {
    enum class State { AWAITING_INITIAL_KEY, AWAITING_CONFIRMATION, ESTABLISHED, CLOSED }
    var state = State.AWAITING_INITIAL_KEY
        private set
    private val localKey = key.copyOf().also { require(it.size == 16) }
    private val wrapping = LegacyBlowfish(LegacyBlowfish.HANDSHAKE_KEY.toByteArray(Charsets.US_ASCII))
    private var incoming: LegacyBlowfish? = null
    private val outgoing = LegacyBlowfish(localKey)
    private var previous = -1L
    private var next = initialSequence.let { require(it in 0..0x7fffffffL); if (it == 0L) 1L else it }

    fun accept(type: Int, ciphertext: ByteArray): ByteArray? {
        check(state == State.AWAITING_INITIAL_KEY || state == State.AWAITING_CONFIRMATION)
        require(type == if (state == State.AWAITING_INITIAL_KEY) 0x34 else 0x36) { "Unexpected key exchange frame" }
        val plain = wrapping.decrypt(ciphertext)
        require(plain.size >= 8) { "Truncated key exchange" }
        val input = ByteBuffer.wrap(plain)
        val checksum = input.short.toInt() and 65535
        val length = input.short.toInt() and 65535
        require(length in 1..56 && input.remaining() >= length) { "Invalid key length" }
        val peerKey = ByteArray(length).also { input.get(it) }
        val first = state == State.AWAITING_INITIAL_KEY
        if (first) require(input.remaining() >= 4) { "Truncated initial sequence" }
        val sequence = if (first) input.int.toLong() and 0xffffffffL else 0L
        val end = input.position()
        require(plain.size == (end + 7) / 8 * 8 && plain.drop(end).all { it == 0.toByte() }) { "Invalid key padding" }
        require(checksum == Envelope.checksum(plain.copyOfRange(2, end))) { "Key checksum mismatch" }
        if (!first) {
            require(java.security.MessageDigest.isEqual(localKey, peerKey)) { "Key echo mismatch" }
            state = State.ESTABLISHED
            return null
        }
        require(sequence in 1..0xffffffffL) { "Invalid initial sequence" }
        val reply = ByteBuffer.allocate(length + localKey.size + 10).putShort(0).putShort(length.toShort()).put(peerKey)
            .putShort(localKey.size.toShort()).put(localKey).putInt(next.toInt()).array()
        ByteBuffer.wrap(reply).putShort(Envelope.checksum(reply.copyOfRange(2, reply.size)).toShort())
        incoming = LegacyBlowfish(peerKey); previous = sequence - 1
        state = State.AWAITING_CONFIRMATION
        return Frames.encode(0x35, wrapping.encrypt(reply))
    }
    fun receive(type: Int, ciphertext: ByteArray): ByteArray {
        check(state == State.ESTABLISHED)
        require(type == 0x30) { "Expected encrypted frame 0x30" }
        val (sequence, body) = Envelope.decode(incoming!!.decrypt(ciphertext), previous)
        require(body.size >= 2) { "Truncated application opcode" }
        if ((ByteBuffer.wrap(body).short.toInt() and 65535) == 0x31) error("Encrypted rekey 0x31 is not implemented")
        previous = sequence
        return body
    }
    fun send(body: ByteArray, clearHeader: Int? = null): ByteArray {
        check(state == State.ESTABLISHED)
        check(next <= 0x7fffffffL) { "Rekey required but not implemented" }
        val frame = Frames.encode(0x30, outgoing.encrypt(Envelope.encode(next, body)), clearHeader)
        next++
        return frame
    }
    fun close() { state = State.CLOSED }
}
