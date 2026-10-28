package org.logh7.protocol

import java.nio.ByteBuffer

/** Native 004944a0/0049ca30 (0405), 0049cf90 (0406). Author: 최병호. */
object TacticalAttackMessages {
    /** 004a61b0 reads 22 packed bytes; 004c0df0 consumes absolute cumulative losses. */
    fun hit(seconds: Long, source: Int, arms: Int, target: Int, destroyed: Int): ByteArray {
        require(seconds in 0..0xffffffffL && source > 0 && target > 0 && arms in 0..2 && destroyed in 1..300)
        return LoginMessages.sessionMessage(0x0426, ByteBuffer.allocate(22).putInt(seconds.toInt()).putInt(source)
            // This path models destroyed boats only. Native damage=0 leaves the separate damage counter unchanged.
            .put(arms.toByte()).put(1).putInt(target).putShort(0).putShort(destroyed.toShort())
            .put(255.toByte()).putShort(0).put(100).array())
    }
    data class Attack(val character: Int, val units: List<Int>, val targetKind: Int, val target: Int) {
        init { validate(character, units, targetKind, target) }
    }
    data class Shoot(val character: Int, val units: List<Int>, val arms: Int, val targetKind: Int, val target: Int) {
        init { validate(character, units, targetKind, target); require(arms in 0..255) }
    }
    // Wire enums are not permission checks. The world actor must resolve the authenticated
    // commander, source units, target identity, hostility, sight, range and ammunition.
    private fun validate(character: Int, units: List<Int>, kind: Int, target: Int) {
        require(character > 0 && units.size in 1..32 && units.all { it > 0 } && units.distinct().size == units.size)
        require(kind in 0..255 && target >= 0)
    }
    private fun read(message: ByteArray, opcode: Int, suffix: Int): Pair<ByteBuffer, Pair<Int, List<Int>>> {
        require(message.size in (15 + 4 + suffix)..(15 + 128 + suffix))
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() and 65535 == opcode)
        input.int; input.int // Only the authoritative clock chooses issue time and delay.
        val character = input.int
        val count = input.get().toInt() and 255
        require(count in 1..32 && input.remaining() == count * 4 + suffix)
        return input to (character to List(count) { input.int })
    }
    fun attack(message: ByteArray): Attack {
        val (input, source) = read(message, 0x0405, 5)
        return Attack(source.first, source.second, input.get().toInt() and 255, input.int)
    }
    fun shoot(message: ByteArray): Shoot {
        val (input, source) = read(message, 0x0406, 6)
        return Shoot(source.first, source.second, input.get().toInt() and 255, input.get().toInt() and 255, input.int)
    }
    private fun reply(opcode: Int, character: Int, units: List<Int>, tail: ByteArray,
        seconds: Long, waitSeconds: Long, accepted: Boolean): ByteArray {
        require(seconds in 0..0xffffffffL && waitSeconds in 0..0xffffffffL - seconds)
        val out = ByteBuffer.allocate(13 + units.size * 4 + tail.size)
            .putInt(seconds.toInt()).putInt(waitSeconds.toInt()).putInt(character).put(units.size.toByte())
        units.forEach(out::putInt)
        out.put(tail)
        return LoginMessages.sessionMessage(opcode, out.array()).also { if (!accepted) ByteBuffer.wrap(it).putInt(0, 1) }
    }
    fun reply(command: Attack, seconds: Long, waitSeconds: Long, accepted: Boolean = true): ByteArray =
        reply(0x0405, command.character, command.units,
            ByteBuffer.allocate(5).put(command.targetKind.toByte()).putInt(command.target).array(), seconds, waitSeconds, accepted)
    fun reply(command: Shoot, seconds: Long, waitSeconds: Long, accepted: Boolean = true): ByteArray =
        reply(0x0406, command.character, command.units,
            ByteBuffer.allocate(6).put(command.arms.toByte()).put(command.targetKind.toByte()).putInt(command.target).array(),
            seconds, waitSeconds, accepted)
}
