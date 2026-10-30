package org.logh7.protocol

import java.nio.ByteBuffer

/** CD 00465240: time, character and return base, each u32. Author: Peppone Choi. */
object ReturnBaseMessages {
    data class Selection(val character: Int, val base: Int)
    fun selection(message: ByteArray): Selection {
        require(message.size == 14)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() == 0x0f1a)
        input.int // Client time is not authoritative.
        return Selection(input.int, input.int).also { require(it.character > 0 && it.base in 0..65535) }
    }
    fun reply(character: Int, base: Int, seconds: Long, accepted: Boolean = true): ByteArray {
        require(character > 0 && base in 0..65535 && seconds in 0..0xffffffffL)
        return LoginMessages.sessionMessage(0x0f1a, ByteBuffer.allocate(12).putInt(seconds.toInt())
            .putInt(character).putInt(base).array()).also { if (!accepted) ByteBuffer.wrap(it).putInt(0, 1) }
    }
}
