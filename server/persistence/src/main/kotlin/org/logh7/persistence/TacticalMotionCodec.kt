package org.logh7.persistence

import java.nio.ByteBuffer
import java.util.Base64
import org.logh7.engine.TacticalMotion
import org.logh7.engine.TacticalPoint
import org.logh7.engine.TacticalPose

/** Bounded NAV-9 motion, with no locale-dependent float serialization. Author: 최병호. */
internal object TacticalMotionCodec {
    fun encode(motion: TacticalMotion): String {
        val flags=(if(motion.parallel)1 else 0) or (if(motion.reportedWaypoints>0)2 else 0)
        val out = ByteBuffer.allocate(33 + 8 * motion.waypoints.size+if(flags==0)0 else if(flags and 2==0)1 else 2).putInt(motion.grid).putLong(motion.acceptedAt)
            .putFloat(motion.origin.position.x).putFloat(motion.origin.position.y).putFloat(motion.origin.direction)
            .putFloat(motion.velocity).putFloat(motion.direction).put(motion.waypoints.size.toByte())
        motion.waypoints.forEach { out.putFloat(it.x).putFloat(it.y) }
        if(flags!=0)out.put(flags.toByte())
        if(flags and 2!=0)out.put(motion.reportedWaypoints.toByte())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.array())
    }
    fun decode(value: String): TacticalMotion {
        require(value.length in 55..388)
        val input = ByteBuffer.wrap(Base64.getUrlDecoder().decode(value))
        require(input.remaining() in 41..291)
        val grid = input.int; val time = input.long
        val origin = TacticalPose(TacticalPoint(input.float, input.float), input.float)
        val velocity = input.float; val direction = input.float
        val count = input.get().toInt() and 255
        require(count in 1..32 && input.remaining() in count*8..count*8+2)
        val points=List(count) {TacticalPoint(input.float,input.float)}
        val flags=if(input.hasRemaining())input.get().toInt().also {require(it in 1..3)} else 0
        require(input.remaining()==if(flags and 2!=0)1 else 0)
        val reported=if(flags and 2!=0)input.get().toInt().also {require(it in 1 until count)} else 0
        require(!input.hasRemaining())
        return TacticalMotion(grid, time, origin, velocity, direction, points,flags and 1!=0,reported)
            .also { require(encode(it) == value) }
    }
}
