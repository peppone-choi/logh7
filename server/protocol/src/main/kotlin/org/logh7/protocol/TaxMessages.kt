package org.logh7.protocol

import java.nio.ByteBuffer

/** CD group 00438e30, writer 00434880, reader 00435e80. Author: Peppone Choi. */
object TaxMessages {
    data class Query(val character: Int, val base: Int, val rate: Int, val card: Int, val mode: Int)
    fun query(message: ByteArray): Query {
        require(message.size == 27)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() == 0x0a00)
        input.int // Client time is not the authoritative command clock.
        val character = input.int; val mode = input.get().toInt() and 255
        input.int; input.int // Never accept client PCP/MCP as a balance.
        val base = input.int; val rate = input.get().toInt() and 255
        val card = input.short.toInt() and 65535
        input.get() // Client result cannot authorize a command.
        require(character > 0 && base in 1..65535 && rate in 0..100)
        return Query(character, base, rate, card, mode)
    }
    fun response(query: Query, seconds: Long, pcp: Int, mcp: Int, accepted: Boolean): ByteArray {
        require(seconds in 0..0xffffffffL && pcp >= 0 && mcp >= 0)
        require(query.character > 0 && query.base in 1..65535 && query.rate in 0..100 && query.card in 0..65535 && query.mode in 0..255)
        return LoginMessages.sessionMessage(0x0a00, ByteBuffer.allocate(25).putInt(seconds.toInt()).putInt(query.character)
            .put(query.mode.toByte()).putInt(pcp).putInt(mcp).putInt(query.base).put(query.rate.toByte())
            .putShort(query.card.toShort()).put(if (accepted) 0 else 1).array()).also {
                if (!accepted) ByteBuffer.wrap(it).putInt(0, 1)
            }
    }
}
