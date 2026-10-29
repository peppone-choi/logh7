package org.logh7.persistence

import java.nio.ByteBuffer
import java.util.Base64
import org.logh7.engine.TacticalMotion
import org.logh7.engine.TacticalPoint
import org.logh7.engine.TacticalPose
import org.logh7.engine.TacticalRotation
import org.logh7.engine.TacticalManeuver

/** Bounded NAV-9 motion, with no locale-dependent float serialization. Author: 최병호. */
internal object TacticalMotionCodec {
    fun encode(motion: TacticalMotion): String {
        val flags=(if(motion.parallel)1 else 0) or (if(motion.reportedWaypoints>0)2 else 0) or
            (motion.rotation?.let {if(it.kind==TacticalManeuver.TURN)4 else 8} ?: 0) or (if(motion.turnRate>0f)16 else 0) or
            (if(motion.rotation?.reverseSide==1)32 else 0) or (if(motion.waitSeconds!=TacticalMotion.WAIT_SECONDS)64 else 0)
        val extra=(if(flags==0)0 else 1)+(if(motion.reportedWaypoints>0)1 else 0)+(motion.rotation?.let {9+4*it.selection.size} ?: 0)+
            (if(flags and 16!=0)4 else 0)+(if(flags and 64!=0)4 else 0)
        val out = ByteBuffer.allocate(33 + 8 * motion.waypoints.size+extra).putInt(motion.grid).putLong(motion.acceptedAt)
            .putFloat(motion.origin.position.x).putFloat(motion.origin.position.y).putFloat(motion.origin.direction)
            .putFloat(motion.velocity).putFloat(motion.direction).put(motion.waypoints.size.toByte())
        motion.waypoints.forEach { out.putFloat(it.x).putFloat(it.y) }
        if(flags!=0)out.put(flags.toByte())
        if(flags and 2!=0)out.put(motion.reportedWaypoints.toByte())
        if(flags and 16!=0)out.putFloat(motion.turnRate)
        if(flags and 64!=0)out.putInt(motion.waitSeconds.toInt())
        motion.rotation?.let {r->out.putInt(r.leader).putInt(r.wait.toInt()).put(r.selection.size.toByte());r.selection.forEach(out::putInt)}
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.array())
    }
    fun decode(value: String): TacticalMotion {
        require(value.length in 55..430)
        val input = ByteBuffer.wrap(Base64.getUrlDecoder().decode(value))
        require(input.remaining() in 41..320)
        val grid = input.int; val time = input.long
        val origin = TacticalPose(TacticalPoint(input.float, input.float), input.float)
        val velocity = input.float; val direction = input.float
        val count = input.get().toInt() and 255
        require(count in 1..32 && input.remaining() in count*8..count*8+147)
        val points=List(count) {TacticalPoint(input.float,input.float)}
        val flags=if(input.hasRemaining())input.get().toInt().also {require(it in 1..127 && it and 12!=12 && (it and 32==0 || it and 8!=0))} else 0
        require(input.remaining()>=(if(flags and 2!=0)1 else 0)+(if(flags and 12!=0)13 else 0)+(if(flags and 16!=0)4 else 0)+(if(flags and 64!=0)4 else 0))
        val reported=if(flags and 2!=0)input.get().toInt().also {require(it in 1 until count)} else 0
        val turnRate=if(flags and 16!=0)input.float else 0f
        val wait=if(flags and 64!=0)input.int.toLong() else TacticalMotion.WAIT_SECONDS
        val rotation=if(flags and 12!=0) {
            val leader=input.int;val wait=input.int.toLong() and 0xffffffffL;val size=input.get().toInt() and 255
            require(size in 1..32 && input.remaining()==size*4)
            TacticalRotation(if(flags and 4!=0)TacticalManeuver.TURN else TacticalManeuver.REVERSE,leader,wait,List(size){input.int},if(flags and 32!=0)1 else 0)
        } else null
        require(!input.hasRemaining())
        return TacticalMotion(grid, time, origin, velocity, direction, points,flags and 1!=0,reported,rotation,turnRate,wait)
            .also { require(encode(it) == value) }
    }
}
