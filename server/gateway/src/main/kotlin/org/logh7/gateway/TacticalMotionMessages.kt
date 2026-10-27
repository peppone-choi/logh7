package org.logh7.gateway

import org.logh7.engine.Navigator
import org.logh7.protocol.TacticalMoveMessages

/** Only called after durable navigation commit, or to restore an already committed path. Author: 최병호. */
internal object TacticalMotionMessages {
    fun reply(player: Navigator, seconds: Long): ByteArray? {
        val motion = player.motion?.takeIf { it.grid == player.grid && player.cpPaused } ?: return null
        val pose = motion.pose(seconds)
        fun point(p: org.logh7.engine.TacticalPoint) = TacticalMoveMessages.Point(p.x, p.y, 0f)
        return TacticalMoveMessages.reply(TacticalMoveMessages.Move(player.characterId,
            listOf(TacticalMoveMessages.Unit(player.unitId, pose.direction, point(pose.position))), motion.velocity,
            motion.direction, motion.remaining(seconds).map(::point)), seconds,
            if (motion.velocity == 0f) 0 else maxOf(0, motion.startsAt - seconds))
    }
}
