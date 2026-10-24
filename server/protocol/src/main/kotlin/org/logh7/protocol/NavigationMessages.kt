package org.logh7.protocol

import java.nio.ByteBuffer
import org.logh7.protocol.generated.MessageId

/** CD CommandMoveGrid (004498b0) and NotifyMovedGrid (0044b8a0); native padding is omitted. */
object NavigationMessages {
    data class Warp(val characterId: Int, val card: Int, val grid: Int, val base: Int, val mode: Int)
    fun warp(message: ByteArray): Warp {
        require(message.size == 33)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() == MessageId.CommandMoveGrid.opcode)
        input.int; input.int // Client time and wait are not authoritative.
        val id = input.int
        val card = input.short.toInt() and 65535
        input.int; input.int // Neither client CP nor the uninitialized range byte is trusted.
        val grid = input.short.toInt() and 65535
        input.get()
        val base = input.int
        val mode = input.short.toInt() and 65535
        require(id > 0 && grid in 0..4999 && base in 0..65535 && mode == 5)
        return Warp(id, card, grid, base, mode)
    }
    fun accepted(command: Warp, seconds: Long, pcp: Int, mcp: Int): ByteArray {
        require(seconds in 0..0xffffffffL && pcp >= 0 && mcp >= 0)
        return LoginMessages.sessionMessage(MessageId.CommandMoveGrid.opcode, ByteBuffer.allocate(31)
            .putInt(seconds.toInt()).putInt(0).putInt(command.characterId).putShort(command.card.toShort())
            .putInt(pcp).putInt(mcp).putShort(command.grid.toShort()).put(0).putInt(0).putShort(5).array())
    }
    fun moved(command: Warp, seconds: Long, unit: Int, cruise: Int): ByteArray =
        LoginMessages.sessionMessage(MessageId.NotifyMovedGrid.opcode, ByteBuffer.allocate(27)
            .putInt(seconds.toInt()).putInt(command.characterId).putInt(command.grid).putInt(0).putShort(5)
            .put(1).putInt(unit).putInt(cruise).array())
    fun rejected(command: Warp): ByteArray = accepted(command, 0, 0, 0).also {
        ByteBuffer.wrap(it).putInt(0, 1) // Existing S2C result field precedes the opcode.
    }
    fun grid(index: Int): ByteArray {
        require(index in 0..4999)
        return LoginMessages.sessionMessage(MessageId.ResponseInformationGrid.opcode, ByteBuffer.allocate(3).putShort(index.toShort()).put(0).array())
    }
}
