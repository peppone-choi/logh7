package org.logh7.gateway

import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.security.SecureRandom

/** Immutable write identity and byte snapshot. No caller can replace the bytes associated with a stage. */
internal class OutboundBatch internal constructor(
    internal val owner: Handshake, internal val id: Long?, bytes: ByteArray,
) {
    private val content = bytes.copyOf()
    val bytes: ByteArray get() = content.copyOf()
}

/** Initial exchange validated; rekey follows static source only, not original-client interoperability. */
class Handshake(key: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }, initialSequence: Long = 1,
    private val newKey: () -> ByteArray = { ByteArray(16).also { SecureRandom().nextBytes(it) } },
    private val rekeyCipher: (ByteArray) -> LegacyBlowfish = { LegacyBlowfish(it) },
) {
    enum class State { AWAITING_INITIAL_KEY, AWAITING_CONFIRMATION, ESTABLISHED, CLOSED }
    var state = State.AWAITING_INITIAL_KEY
        private set
    private val localKey = key.copyOf().also { require(it.size == 16) }
    private val wrapping = LegacyBlowfish(LegacyBlowfish.HANDSHAKE_KEY.toByteArray(Charsets.US_ASCII))
    private var incoming: LegacyBlowfish? = null
    private var previous = -1L
    private data class Outgoing(val cipher: LegacyBlowfish, val next: Long)
    private class Stage(val candidate: Outgoing, val batch: OutboundBatch,
        var submitted: Boolean = false, var succeeded: Boolean = false)
    private var committed = Outgoing(LegacyBlowfish(localKey), initialSequence.let {
        require(it in 0..0xffffffffL); if (it == 0L) 1L else it
    })
    // Candidate metadata only; Netty owns transmission and no second byte queue is introduced.
    private val stages = linkedMapOf<Long, Stage>()
    private var lastId = 0L
    internal val committedSequence: Long get() = committed.next
    internal val pendingBatches: Int get() = stages.size

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
            .putShort(localKey.size.toShort()).put(localKey).putInt(committed.next.toInt()).array()
        ByteBuffer.wrap(reply).putShort(Envelope.checksum(reply.copyOfRange(2, reply.size)).toShort())
        incoming = LegacyBlowfish(peerKey); previous = sequence - 1
        state = State.AWAITING_CONFIRMATION
        return Frames.encode(0x35, wrapping.encrypt(reply))
    }
    fun receive(type: Int, ciphertext: ByteArray): ByteArray? {
        check(state == State.ESTABLISHED)
        require(type == 0x30) { "Expected encrypted frame 0x30" }
        val (sequence, body) = Envelope.decode(incoming!!.decrypt(ciphertext), previous)
        require(body.size >= 2) { "Truncated application opcode" }
        if ((ByteBuffer.wrap(body).short.toInt() and 65535) == 0x31) {
            val key = body.copyOfRange(2, body.size)
            require(key.size in 1..56) { "Invalid rekey length" }
            val candidate = try { rekeyCipher(key) } catch (_: Exception) {
                close(); error("Rekey cipher unavailable")
            }
            incoming = candidate; previous = 0
            return null
        }
        previous = sequence
        return body
    }
    /** Codec convenience. The gateway uses stageAll's bound write identity instead of these bytes alone. */
    fun send(body: ByteArray, clearHeader: Int? = null): ByteArray = sendAll(listOf(body), clearHeader)
    fun sendAll(bodies: List<ByteArray>, clearHeader: Int? = null): ByteArray = stageAll(bodies, clearHeader).bytes

    internal fun plain(bytes: ByteArray) = OutboundBatch(this, null, bytes)
    internal fun stageAll(bodies: List<ByteArray>, clearHeader: Int? = null): OutboundBatch {
        check(state == State.ESTABLISHED)
        require(bodies.isNotEmpty()) { "Empty encrypted batch" }
        check(stages.size < 1024) { "Too many pending encrypted writes" }
        var candidate = stages.values.lastOrNull()?.candidate ?: committed
        val output = java.io.ByteArrayOutputStream()
        for (body in bodies) {
            check(candidate.next <= 0xffffffffL) { "Outgoing sequence exhausted" }
            val rotate = candidate.next >= 0x80000000L
            if (rotate) {
                check(clearHeader != null) { "Login rekey carrier is not established" }
                check(candidate.next < 0xffffffffL) { "Rekey sequence exhausted" }
            }
            output.write(Frames.encode(0x30, candidate.cipher.encrypt(Envelope.encode(candidate.next, body)), clearHeader))
            candidate = candidate.copy(next = candidate.next + 1)
            if (rotate) {
                val key = try { newKey().copyOf() } catch (_: Exception) { error("Rekey key unavailable") }
                require(key.size == 16) { "Invalid outgoing rekey length" }
                val cipher = try { rekeyCipher(key) } catch (_: Exception) { error("Rekey cipher unavailable") }
                // Both aux=0 values are the existing synthetic session carrier, not observed original values.
                val control = LoginMessages.sessionMessage(0x31, key)
                output.write(Frames.encode(0x30, candidate.cipher.encrypt(Envelope.encode(candidate.next, control)), clearHeader))
                candidate = Outgoing(cipher, 1)
            }
        }
        // Nothing above mutates committed/staged state, including failed multi-message encodes.
        val id = Math.addExact(lastId, 1)
        val batch = OutboundBatch(this, id, output.toByteArray())
        stages[id] = Stage(candidate, batch); lastId = id
        return batch
    }
    internal fun submitting(batch: OutboundBatch) {
        check(state != State.CLOSED && batch.owner === this) { "Invalid outbound owner or closed exchange" }
        val id = batch.id ?: return // Initial key exchange bytes; there is no outgoing candidate yet.
        val next = stages.entries.firstOrNull { !it.value.submitted }
        check(next?.key == id && next.value.batch === batch) { "Invalid, duplicate or out of order encrypted write" }
        next.value.submitted = true
    }
    internal fun committed(batch: OutboundBatch) {
        if (state == State.CLOSED || batch.owner !== this) return
        val id = batch.id ?: return
        val stage = stages[id] ?: return // Old/duplicate callback cannot advance state.
        if (stage.batch !== batch) return
        check(stage.submitted) { "Unsubmitted encrypted write completed" }
        stage.succeeded = true
        while (stages.entries.firstOrNull()?.value?.succeeded == true) {
            val first = stages.entries.first()
            committed = first.value.candidate; stages.remove(first.key)
        }
    }
    fun close() { stages.clear(); state = State.CLOSED }
}
