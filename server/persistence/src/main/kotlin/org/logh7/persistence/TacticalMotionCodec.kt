package org.logh7.persistence

import java.nio.ByteBuffer
import java.util.Base64
import org.logh7.engine.TacticalMotion
import org.logh7.engine.TacticalPoint
import org.logh7.engine.TacticalPose

/** Bounded NAV-9 motion, with no locale-dependent float serialization. Author: 최병호. */
internal object TacticalMotionCodec {
    fun encode(motion: TacticalMotion): String {
        val out = ByteBuffer.allocate(33 + 8 * motion.waypoints.size+if(motion.parallel)1 else 0).putInt(motion.grid).putLong(motion.acceptedAt)
            .putFloat(motion.origin.position.x).putFloat(motion.origin.position.y).putFloat(motion.origin.direction)
            .putFloat(motion.velocity).putFloat(motion.direction).put(motion.waypoints.size.toByte())
        motion.waypoints.forEach { out.putFloat(it.x).putFloat(it.y) }
        if(motion.parallel)out.put(1)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.array())
    }
    fun decode(value: String): TacticalMotion {
        require(value.length in 55..387)
        val input = ByteBuffer.wrap(Base64.getUrlDecoder().decode(value))
        require(input.remaining() in 41..290)
        val grid = input.int; val time = input.long
        val origin = TacticalPose(TacticalPoint(input.float, input.float), input.float)
        val velocity = input.float; val direction = input.float
        val count = input.get().toInt() and 255
        require(count in 1..32 && input.remaining() in count*8..count*8+1)
        val points=List(count) {TacticalPoint(input.float,input.float)}
        val parallel=if(input.hasRemaining())input.get().toInt().also {require(it==1)}==1 else false
        return TacticalMotion(grid, time, origin, velocity, direction, points,parallel)
            .also { require(encode(it) == value) }
    }
}
