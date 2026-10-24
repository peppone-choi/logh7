package org.logh7.protocol

import java.nio.ByteBuffer

/** CD readers 00449380/0044d6a0 and NotifyMovedBase. Author: 최병호. */
object PlaceMessages {
    data class Dock(val characterId: Int, val card: Int, val base: Int, val mode: Int)
    data class Move(val characterId: Int, val spot: Int, val owner: Int)
    fun dock(message: ByteArray): Dock {
        require(message.size == 30)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() == 0x0b00)
        input.int; input.int
        val id = input.int; val card = input.short.toInt() and 65535
        input.int; input.int
        val base = input.int; val mode = input.short.toInt() and 65535
        require(id > 0 && base in 0..65535 && mode in 0..5)
        return Dock(id, card, base, mode)
    }
    fun move(message: ByteArray): Move {
        require(message.size == 26)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() == 0x0e00)
        input.int
        val id = input.int
        input.int; input.int
        val spot = input.int; val owner = input.int
        require(id > 0 && spot >= 0 && owner >= 0)
        return Move(id, spot, owner)
    }
    fun docked(command: Dock, seconds: Long, pcp: Int, mcp: Int, accepted: Boolean = true): ByteArray {
        require(seconds in 0..0xffffffffL && pcp >= 0 && mcp >= 0)
        require(command.characterId > 0 && command.card in 0..65535 && command.base in 0..65535 && command.mode in 0..5)
        return LoginMessages.sessionMessage(0x0b00, ByteBuffer.allocate(28)
            .putInt(seconds.toInt()).putInt(0).putInt(command.characterId).putShort(command.card.toShort())
            .putInt(pcp).putInt(mcp).putInt(command.base).putShort(command.mode.toShort()).array()).also {
                if (!accepted) ByteBuffer.wrap(it).putInt(0, 1)
            }
    }
    fun moved(command: Move, seconds: Long, pcp: Int, mcp: Int, accepted: Boolean = true): ByteArray {
        require(seconds in 0..0xffffffffL && pcp >= 0 && mcp >= 0)
        require(command.characterId > 0 && command.spot >= 0 && command.owner >= 0)
        return LoginMessages.sessionMessage(0x0e00, ByteBuffer.allocate(24).putInt(seconds.toInt()).putInt(command.characterId)
            .putInt(pcp).putInt(mcp).putInt(command.spot).putInt(command.owner).array()).also {
                if (!accepted) ByteBuffer.wrap(it).putInt(0, 1)
            }
    }
    fun dockNotification(character: Int, base: Int, mode: Int, seconds: Long): ByteArray {
        require(character > 0 && base in 0..65535 && mode in 0..5 && seconds in 0..0xffffffffL)
        return LoginMessages.sessionMessage(0x0b0b, ByteBuffer.allocate(27).putInt(seconds.toInt()).putInt(character)
            .putInt(base).putShort(mode.toShort()).putInt(0).putInt(character).put(1).putInt(character).array())
    }
}
