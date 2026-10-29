package org.logh7.gateway

import org.logh7.engine.Navigator
import org.logh7.protocol.TacticalMoveMessages

/** Only called after durable navigation commit, or to restore an already committed path. Author: 최병호. */
internal object TacticalMotionMessages {
    fun formation(motions:Map<Int,org.logh7.engine.TacticalMotion>,seconds:Long,character:Int):List<ByteArray> {
        require(motions.size in 1..32 && character>0)
        val first=motions.values.first()
        val rotation=checkNotNull(first.rotation)
        require(motions.values.all {it.rotation==rotation && it.acceptedAt==first.acceptedAt && it.grid==first.grid})
        require(motions.keys.all {it in rotation.selection})
        val ordered=rotation.selection.mapNotNull {id->motions[id]?.let {id to it}}
        // 0401 uses existing native positions. Freeze authoritative origins before computing its formation.
        val origins=ordered.map {(id,motion)->
            val p=motion.origin
            val point=TacticalMoveMessages.Point(p.position.x,p.position.y,0f)
            TacticalMoveMessages.reply(TacticalMoveMessages.Move(character,listOf(TacticalMoveMessages.Unit(id,p.direction,point)),
                0f,p.direction,listOf(point)),seconds,0)
        }
        return origins+org.logh7.protocol.TacticalTurnMessages.reply(org.logh7.protocol.TacticalTurnMessages.Command(character,
            ordered.map {(id,m)->org.logh7.protocol.TacticalTurnMessages.Unit(id,m.origin.direction)},
            if(rotation.kind==org.logh7.engine.TacticalManeuver.REVERSE)null else first.direction,rotation.reverseSide),seconds,maxOf(0,first.startsAt-seconds))
    }
    fun npc(unit:Int,motion:org.logh7.engine.TacticalMotion,seconds:Long,character:Int=0):ByteArray {
        if(motion.rotation!=null)return formation(mapOf(unit to motion),seconds,character).last()
        // A zero-engine path retains its destinations in storage, but the native renderer
        // needs a stationary pose to retire the previous movement plan.
        val pose=motion.pose(seconds)
        fun point(p:org.logh7.engine.TacticalPoint)=TacticalMoveMessages.Point(p.x,p.y,0f)
        return TacticalMoveMessages.reply(TacticalMoveMessages.Move(character,
            listOf(TacticalMoveMessages.Unit(unit,pose.direction,point(pose.position))),motion.velocity,if(motion.velocity==0f)pose.direction else motion.direction,
            (if(motion.velocity==0f)listOf(pose.position) else motion.remaining(seconds)).map(::point),motion.parallel),seconds,if(motion.velocity==0f)0 else maxOf(0,motion.startsAt-seconds))
    }
    fun reply(player: Navigator, seconds: Long): ByteArray? {
        val motion = player.motion?.takeIf { it.grid == player.grid && player.cpPaused } ?: return null
        val pose = motion.pose(seconds)
        fun point(p: org.logh7.engine.TacticalPoint) = TacticalMoveMessages.Point(p.x, p.y, 0f)
        return TacticalMoveMessages.reply(TacticalMoveMessages.Move(player.characterId,
            listOf(TacticalMoveMessages.Unit(player.unitId, pose.direction, point(pose.position))), motion.velocity,
            if(motion.velocity==0f)pose.direction else motion.direction,
            (if(motion.velocity==0f)listOf(pose.position) else motion.remaining(seconds)).map(::point),motion.parallel), seconds,
            if (motion.velocity == 0f) 0 else maxOf(0, motion.startsAt - seconds))
    }
}
