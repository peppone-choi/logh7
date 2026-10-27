package org.logh7.persistence

import java.nio.ByteBuffer
import java.util.Base64
import org.logh7.engine.TacticalMotion
import org.logh7.engine.TacticalPoint
import org.logh7.engine.TacticalPose

/** Bounded NAV-9 motion, with no locale-dependent float serialization. Author: 최병호. */
internal object TacticalMotionCodec {
    fun encode(motion: TacticalMotion): String {
        val out = ByteBuffer.allocate(33 + 8 * motion.waypoints.size).putInt(motion.grid).putLong(motion.acceptedAt)
            .putFloat(motion.origin.position.x).putFloat(motion.origin.position.y).putFloat(motion.origin.direction)
            .putFloat(motion.velocity).putFloat(motion.direction).put(motion.waypoints.size.toByte())
        motion.waypoints.forEach { out.putFloat(it.x).putFloat(it.y) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.array())
    }
    fun decode(value: String): TacticalMotion {
        require(value.length in 55..386)
        val input = ByteBuffer.wrap(Base64.getUrlDecoder().decode(value))
        require(input.remaining() in 41..289)
        val grid = input.int; val time = input.long
        val origin = TacticalPose(TacticalPoint(input.float, input.float), input.float)
        val velocity = input.float; val direction = input.float
        val count = input.get().toInt() and 255
        require(count in 1..32 && input.remaining() == count * 8)
        return TacticalMotion(grid, time, origin, velocity, direction, List(count) { TacticalPoint(input.float, input.float) })
            .also { require(encode(it) == value) }
    }
}
