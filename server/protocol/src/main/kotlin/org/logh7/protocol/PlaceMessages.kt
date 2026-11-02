package org.logh7.protocol

import java.nio.ByteBuffer

/** CD readers 00449380/0044d6a0 and NotifyMovedBase. Author: Peppone Choi. */
object PlaceMessages {
    data class Dock(val characterId: Int, val card: Int, val base: Int, val mode: Int)
    data class Move(val characterId: Int, val spot: Int, val owner: Int)
    data class SwitchMode(val characterId: Int, val card: Int, val mode: Int,
        val units: List<Int> = emptyList(), val characters: List<Int> = emptyList())
    /** 00448ea0: a different packed record from CommandMoveBase, used by strategy departure. */
    fun switchMode(message: ByteArray): SwitchMode {
        require(message.size in 32..352)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() == 0x0b06)
        input.int
        val id = input.int; val card = input.short.toInt() and 65535
        input.int; input.int // Authoritative balances come from the world actor.
        val mode = input.short.toInt() and 65535
        val unitCount = input.get().toInt() and 255
        require(unitCount <= 70 && input.remaining() >= unitCount * 4 + 9)
        val units = List(unitCount) { input.int }
        input.int; input.int // Client-proposed spot/owner cannot select the departure location.
        val characterCount = input.get().toInt() and 255
        require(characterCount <= 10 && input.remaining() == characterCount * 4)
        val characters = List(characterCount) { input.int }
        require(id > 0 && mode in 0..5)
        return SwitchMode(id, card, mode, units, characters)
    }
    fun switched(command: SwitchMode, seconds: Long, pcp: Int, mcp: Int, mode: Int,
        spot: Int, owner: Int, accepted: Boolean): ByteArray {
        require(seconds in 0..0xffffffffL && pcp >= 0 && mcp >= 0 && mode in 0..5 && spot >= 0 && owner >= 0)
        return LoginMessages.sessionMessage(0x0b06, ByteBuffer.allocate(38).putInt(seconds.toInt()).putInt(command.characterId)
            .putShort(command.card.toShort()).putInt(pcp).putInt(mcp).putShort(mode.toShort())
            .put(1).putInt(command.characterId + 1_000_000).putInt(spot).putInt(owner)
            .put(1).putInt(command.characterId).array()).also { if (!accepted) ByteBuffer.wrap(it).putInt(0, 1) }
    }
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
    fun docked(command: Dock, seconds: Long, pcp: Int, mcp: Int, accepted: Boolean = true, waitSeconds: Long = 0): ByteArray {
        require(seconds in 0..0xffffffffL && pcp >= 0 && mcp >= 0 && waitSeconds in 0..0xffffffffL)
        require(command.characterId > 0 && command.card in 0..65535 && command.base in 0..65535 && command.mode in 0..5)
        return LoginMessages.sessionMessage(0x0b00, ByteBuffer.allocate(28)
            .putInt(seconds.toInt()).putInt(if (accepted) waitSeconds.toInt() else 0).putInt(command.characterId).putShort(command.card.toShort())
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
    fun dockNotification(character: Int, base: Int, mode: Int, seconds: Long, spot: Int = 0, owner: Int = character): ByteArray {
        require(character > 0 && base in 0..65535 && mode in 0..5 && seconds in 0..0xffffffffL && spot >= 0 && owner >= 0)
        return LoginMessages.sessionMessage(0x0b0b, ByteBuffer.allocate(27).putInt(seconds.toInt()).putInt(character)
            .putInt(base).putShort(mode.toShort()).putInt(spot).putInt(owner).put(1).putInt(character).array())
    }
}
