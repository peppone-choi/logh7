package org.logh7.protocol

import java.nio.ByteBuffer
import org.logh7.protocol.generated.MessageId

/** CD 00449a10 and 0044c450: no native alignment padding on the wire. */
object FuelMessages {
    data class Supply(val characterId: Int, val card: Int)
    fun supply(message: ByteArray): Supply {
        require(message.size == 24)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() == MessageId.CommandSupplyFuel.opcode)
        input.int; input.int // The client cannot choose time, wait or CP balances.
        val id = input.int
        val card = input.short.toInt() and 65535
        input.int; input.int
        require(id > 0)
        return Supply(id, card)
    }
    fun reply(command: Supply, seconds: Long, pcp: Int, mcp: Int, accepted: Boolean): ByteArray {
        require(seconds in 0..0xffffffffL && pcp >= 0 && mcp >= 0)
        return LoginMessages.sessionMessage(MessageId.CommandSupplyFuel.opcode, ByteBuffer.allocate(22).putInt(seconds.toInt())
            .putInt(if (accepted) 8 * 3600 else 0).putInt(command.characterId).putShort(command.card.toShort())
            .putInt(pcp).putInt(mcp).array()).also { if (!accepted) ByteBuffer.wrap(it).putInt(0, 1) }
    }
    fun supplied(seconds: Long, characterId: Int, unitId: Int, cruise: Int): ByteArray {
        require(seconds in 0..0xffffffffL && characterId > 0 && unitId > 0 && cruise >= 0)
        return LoginMessages.sessionMessage(MessageId.NotifySuppliedFuel.opcode, ByteBuffer.allocate(21).putInt(seconds.toInt()).putInt(0)
            .putInt(characterId).put(1).putInt(unitId).putInt(cruise).array())
    }
}
