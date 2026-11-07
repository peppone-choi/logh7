package org.logh7.protocol

import java.nio.ByteBuffer

/** Native 004952b0/0049dc30: packed CommandStop, no structure padding. Author: Peppone Choi. */
object TacticalStopMessages {
    data class Stop(val character: Int, val units: List<Int>, val bases: List<Int> = emptyList()) {
        init {
            require(character > 0 && units.size <= 32 && bases.size <= 32 && (units.isNotEmpty() || bases.isNotEmpty()))
            require(units.all { it > 0 } && units.distinct().size == units.size)
            require(bases.all { it in 1..65535 } && bases.distinct().size == bases.size)
        }
    }
    fun stop(message: ByteArray): Stop {
        require(message.size in 20..272)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() and 65535 == 0x040a)
        input.int; input.int // Scheduling is chosen by the server.
        val character = input.int
        val count = input.get().toInt() and 255
        require(count <= 32 && input.remaining() >= count * 4 + 1)
        val units = List(count) { input.int }
        val baseCount = input.get().toInt() and 255
        require(baseCount <= 32 && input.remaining() == baseCount * 4)
        return Stop(character, units, List(baseCount) { input.int })
    }
    fun reply(stop: Stop, seconds: Long, accepted: Boolean = true): ByteArray = encode(stop, seconds, 0, accepted)

    // The CD only caches this reply. Reserve an impossible stop wait for the compatibility
    // client so an ordinary stop reply, including a rejection, cannot clear support.
    fun supportCancelled(character: Int, unit: Int, seconds: Long): ByteArray =
        encode(Stop(character, listOf(unit)), seconds, -1, true)

    private fun encode(stop: Stop, seconds: Long, wait: Int, accepted: Boolean): ByteArray {
        require(seconds in 0..0xffffffffL)
        val out = ByteBuffer.allocate(14 + 4 * (stop.units.size + stop.bases.size))
            .putInt(seconds.toInt()).putInt(wait).putInt(stop.character).put(stop.units.size.toByte())
        stop.units.forEach(out::putInt)
        out.put(stop.bases.size.toByte()); stop.bases.forEach(out::putInt)
        return LoginMessages.sessionMessage(0x040a, out.array()).also {
            if (!accepted) ByteBuffer.wrap(it).putInt(0, 1)
        }
    }
}
