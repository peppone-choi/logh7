package org.logh7.gateway

import org.logh7.engine.TacticalQueuedCommand
import org.logh7.protocol.TacticalCommandMessages
import java.nio.ByteBuffer

/** Reconnect replays the accepted command, with only its remaining server delay. Author: Peppone Choi. */
internal object TacticalCommandWire {
    fun command(order:TacticalQueuedCommand):TacticalCommandMessages.Command? {
        if(order.command.payload.isEmpty())return null
        val bytes=ByteBuffer.allocate(2+order.command.payload.size).putShort(order.command.opcode.toShort())
        order.command.payload.forEach {bytes.put(it.toByte())}
        return TacticalCommandMessages.command(bytes.array()).also {
            require(it.character==order.character && it.units==order.command.units && it.base==order.command.base)
            if(it.opcode==0x406)require(it.kind==order.command.kind && it.targetKind==order.command.targetKind && it.target==order.command.target)
        }
    }
}
