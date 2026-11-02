package org.logh7.protocol

import java.nio.ByteBuffer

/** CD CommandSpotChat: 00465810/00465960, receiver 004be680. Author: Peppone Choi. */
object ChatMessages {
    data class Spot(val characterId: Int, val text: String)
    /** CD cast_type: 0=all, 1=same faction, 2=same fleet, within the current grid. */
    data class Grid(val characterId: Int, val cast: Int, val text: String)
    private fun validate(text: String) {
        require(text.length <= 65 && text.none { it.isSurrogate() || it.isISOControl() })
    }
    fun spot(message: ByteArray): Spot {
        require(message.size in 11..141)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() == 0x0f1d)
        input.int // Client time is never authoritative.
        val character = input.int
        val length = input.get().toInt() and 255
        require(character > 0 && length <= 65 && input.remaining() == length * 2)
        val text = CharArray(length) { input.char }.concatToString()
        validate(text)
        return Spot(character, text)
    }
    fun spot(message: Spot, seconds: Long): ByteArray {
        require(message.characterId > 0 && seconds in 0..0xffffffffL)
        validate(message.text)
        val out = ByteBuffer.allocate(9 + message.text.length * 2)
            .putInt(seconds.toInt()).putInt(message.characterId).put(message.text.length.toByte())
        message.text.forEach(out::putChar)
        return LoginMessages.sessionMessage(0x0f1d, out.array())
    }
    fun grid(message: ByteArray): Grid {
        require(message.size in 12..142)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() == 0x0f1c)
        input.int
        val character = input.int
        val cast = input.get().toInt() and 255
        val length = input.get().toInt() and 255
        require(character > 0 && cast in 0..2 && length <= 65 && input.remaining() == length * 2)
        val text = CharArray(length) { input.char }.concatToString()
        validate(text)
        return Grid(character, cast, text)
    }
    fun grid(message: Grid, seconds: Long): ByteArray {
        require(message.characterId > 0 && message.cast in 0..2 && seconds in 0..0xffffffffL)
        validate(message.text)
        val out = ByteBuffer.allocate(10 + message.text.length * 2)
            .putInt(seconds.toInt()).putInt(message.characterId).put(message.cast.toByte()).put(message.text.length.toByte())
        message.text.forEach(out::putChar)
        return LoginMessages.sessionMessage(0x0f1c, out.array())
    }
}
