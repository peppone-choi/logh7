package org.logh7.protocol

import java.nio.ByteBuffer

/** Native 00492930/0049a5d0: packed CommandMoveShip records. Author: 최병호. */
object TacticalMoveMessages {
    data class Point(val x: Float, val y: Float, val z: Float) {
        init { require(listOf(x, y, z).all(Float::isFinite)) }
    }
    data class Unit(val id: Int, val direction: Float, val position: Point) {
        init { require(id > 0 && direction.isFinite()) }
    }
    data class Move(val character: Int, val units: List<Unit>, val velocity: Float,
        val direction: Float, val waypoints: List<Point>,val parallel:Boolean=false) {
        init {
            require(character >= 0 && units.size in 1..32 && units.map { it.id }.distinct().size == units.size)
            require(velocity.isFinite() && velocity >= 0 && direction.isFinite() && waypoints.size in 1..32)
        }
    }

    /** Input includes its opcode, but no response result header. Client scheduling is discarded. */
    fun move(message: ByteArray): Move {
        require(message.size in 56..1048) { "Invalid move size" }
        val input = ByteBuffer.wrap(message)
        val opcode=input.short.toInt() and 65535
        require(opcode==0x0400 || opcode==0x0402)
        input.int; input.int
        val character = input.int
        require(character>0) // Character zero is reserved for server-owned NPC movement, never a client request.
        val count = input.get().toInt() and 255
        require(count in 1..32 && input.remaining() >= count * 20 + 9) { "Invalid move unit count" }
        fun point() = Point(input.float, input.float, input.float)
        val units = List(count) { Unit(input.int, input.float, point()) }
        val velocity = input.float
        val direction = input.float
        val waypointCount = input.get().toInt() and 255
        require(waypointCount in 1..32 && input.remaining() == waypointCount * 12) { "Invalid move path count" }
        return Move(character, units, velocity, direction, List(waypointCount) { point() },opcode==0x0402)
    }

    /** Call with authoritative origins and speed; never acknowledge a request by echoing its positions. */
    fun reply(move: Move, seconds: Long, waitSeconds: Long, accepted: Boolean = true): ByteArray {
        require(seconds in 0..0xffffffffL && waitSeconds in 0..0xffffffffL && seconds + waitSeconds <= 0xffffffffL)
        val out = ByteBuffer.allocate(22 + move.units.size * 20 + move.waypoints.size * 12)
            .putInt(seconds.toInt()).putInt(waitSeconds.toInt()).putInt(move.character).put(move.units.size.toByte())
        fun point(value: Point) { out.putFloat(value.x).putFloat(value.y).putFloat(value.z) }
        move.units.forEach { out.putInt(it.id).putFloat(it.direction); point(it.position) }
        out.putFloat(move.velocity).putFloat(move.direction).put(move.waypoints.size.toByte())
        move.waypoints.forEach(::point)
        return LoginMessages.sessionMessage(if(move.parallel)0x0402 else 0x0400, out.array()).also {
            if (!accepted) ByteBuffer.wrap(it).putInt(0, 1)
        }
    }
}
