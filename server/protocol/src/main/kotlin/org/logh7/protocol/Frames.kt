package org.logh7.protocol

import java.nio.ByteBuffer

object Frames {
    const val MAX_PAYLOAD = 0xF000
    val types = setOf(0x30, 0x31, 0x34, 0x35, 0x36)
    fun encode(type: Int, data: ByteArray): ByteArray {
        require(type in types); require(data.size <= MAX_PAYLOAD - 2)
        return ByteBuffer.allocate(data.size + 4).putShort((data.size + 2).toShort()).putShort(type.toShort()).put(data).array()
    }
    fun decodePayload(payload: ByteArray): Pair<Int, ByteArray> {
        require(payload.size in 2..MAX_PAYLOAD)
        val type = ByteBuffer.wrap(payload).short.toInt() and 0xffff
        require(type in types) { "Unknown MPS frame type $type" }
        return type to payload.copyOfRange(2, payload.size)
    }
}

/** candidate: checksum coverage/padding must be compared with client vectors. */
object Envelope {
    fun checksum(bytes: ByteArray): Int {
        var sum = 0
        val end = bytes.size / 4 * 4
        for (i in 0 until end step 4) {
            var word = 0
            for (j in 0..3) word = word or ((bytes[i + j].toInt() and 255) shl (j * 8))
            sum = sum xor word
        }
        for (i in end until bytes.size) sum = sum xor (bytes[i].toInt() and 255)
        return ((sum ushr 16) xor sum) and 0xffff
    }

    fun encode(sequence: Long, body: ByteArray): ByteArray {
        require(sequence in 0..0xffffffffL); require(body.size <= 0xffff)
        val out = ByteBuffer.allocate(((8 + body.size + 7) / 8) * 8)
        out.putShort(0).putInt(sequence.toInt()).putShort(body.size.toShort()).put(body)
        val bytes = out.array(); ByteBuffer.wrap(bytes).putShort(checksum(bytes.copyOfRange(2, 8 + body.size)).toShort())
        return bytes
    }
    fun decode(bytes: ByteArray, previousSequence: Long): Pair<Long, ByteArray> {
        require(bytes.size >= 8 && bytes.size % 8 == 0)
        val input = ByteBuffer.wrap(bytes)
        val expectedChecksum = input.short.toInt() and 0xffff
        val sequence = input.int.toLong() and 0xffffffffL
        require(sequence > previousSequence) { "bad sequence number" }
        val length = input.short.toInt() and 0xffff
        require(length <= bytes.size - 8)
        require(expectedChecksum == checksum(bytes.copyOfRange(2, 8 + length))) { "broken data" }
         require(bytes.size == ((8 + length + 7) / 8) * 8)
        require(bytes.drop(8 + length).all { it == 0.toByte() })
        return sequence to bytes.copyOfRange(8, 8 + length)
    }
}
