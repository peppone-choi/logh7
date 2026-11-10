package org.logh7.protocol

import java.nio.ByteBuffer
import org.logh7.protocol.generated.MessageId

/** CD CommandSearch readers 004a0d90 / 0049a490. Both directions pack five big-endian words. */
object StrategicSearchMessages {
    fun requester(message: ByteArray): Int {
        require(message.size == 22)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() == MessageId.CommandSearch.opcode)
        // The world owns time, duration and balances. The caller must match this ID to the account.
        return input.getInt(10).also { require(it > 0) }
    }

    fun reply(characterId: Int, seconds: Long, wait: Long, pcp: Int, mcp: Int): ByteArray {
        require(characterId > 0 && seconds in 0..0xffffffffL && wait in 0..0xffffffffL && pcp >= 0 && mcp >= 0)
        val body = ByteBuffer.allocate(20).putInt(seconds.toInt()).putInt(wait.toInt())
            .putInt(characterId).putInt(pcp).putInt(mcp).array()
        return LoginMessages.sessionMessage(MessageId.CommandSearch.opcode, body)
    }
}
