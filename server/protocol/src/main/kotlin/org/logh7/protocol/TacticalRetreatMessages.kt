package org.logh7.protocol

import java.nio.ByteBuffer

/** Native 004940d0/0049c5a0 (0404), 004a5cc0 (0425). Author: 최병호. */
object TacticalRetreatMessages {
    data class Retreat(val character: Int, val units: List<Int>) {
        init { require(character > 0 && units.size in 1..32 && units.all { it > 0 } && units.distinct().size == units.size) }
    }
    fun retreat(message: ByteArray): Retreat {
        require(message.size in 19..143)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() and 65535 == 0x0404)
        input.int; input.int
        val character = input.int
        val count = input.get().toInt() and 255
        require(count in 1..32 && input.remaining() == count * 4)
        return Retreat(character, List(count) { input.int })
    }
    fun reply(command: Retreat, seconds: Long, waitSeconds: Long, accepted: Boolean = true): ByteArray {
        require(seconds in 0..0xffffffffL && waitSeconds in 0..0xffffffffL - seconds)
        val out = ByteBuffer.allocate(13 + command.units.size * 4).putInt(seconds.toInt()).putInt(waitSeconds.toInt())
            .putInt(command.character).put(command.units.size.toByte())
        command.units.forEach(out::putInt)
        return LoginMessages.sessionMessage(0x0404, out.array()).also { if (!accepted) ByteBuffer.wrap(it).putInt(0, 1) }
    }
    fun warped(seconds: Long, grid: Int, units: List<Int>): ByteArray {
        require(seconds in 0..0xffffffffL && grid in 0..4999 && units.size in 1..32 && units.all { it > 0 })
        val out = ByteBuffer.allocate(15 + 4 * units.size).putInt(seconds.toInt()).putInt(grid).putInt(0)
            .putShort(5).put(units.size.toByte())
        units.forEach(out::putInt)
        return LoginMessages.sessionMessage(0x0425, out.array())
    }
}
