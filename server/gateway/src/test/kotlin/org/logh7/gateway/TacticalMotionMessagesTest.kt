package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.TacticalMoveMessages
import kotlin.test.*

/** Zero engine must freeze the native plan without rewriting the stored route. Author: 최병호. */
class TacticalMotionMessagesTest {
    @Test fun zeroSpeedPublishesOneCurrentPoseForPersonalAndRegularUnits() {
        for(parallel in listOf(false,true)) {
            val origin=TacticalPose(TacticalPoint(3f,4f),0.7f)
            val targets=listOf(TacticalPoint(50f,60f),TacticalPoint(0f,90f))
            val motion=TacticalMotion(2017,100,origin,0f,2f,targets,parallel,turnRate=1f)
            val player=Navigator(1,10000,Power.EMPIRE,2017,1000,1000,1000,cpPaused=true,motion=motion)
            val personal=assertNotNull(TacticalMotionMessages.reply(player,200))
            val regular=TacticalMotionMessages.npc(1,motion,200,10000)
            for(packet in listOf(personal,regular)) {
                val move=TacticalMoveMessages.move(packet.copyOfRange(4,packet.size))
                assertEquals(0f,move.velocity);assertEquals(parallel,move.parallel)
                assertEquals(origin.direction,move.direction)
                assertEquals(listOf(move.units.single().position),move.waypoints)
                assertEquals(TacticalMoveMessages.Point(3f,4f,0f),move.waypoints.single())
                assertEquals(origin.direction,move.units.single().direction)
                assertEquals(0,java.nio.ByteBuffer.wrap(packet).getInt(10))
            }
            assertEquals(targets,motion.waypoints);assertEquals(targets,motion.remaining(200))
        }
    }
    @Test fun nonzeroSpeedStillPublishesTheRemainingRouteAndFinalFacing() {
        val motion=TacticalMotion(2017,100,TacticalPose(TacticalPoint(0f,0f),0f),0.5f,2f,
            listOf(TacticalPoint(0f,10f),TacticalPoint(0f,20f)),turnRate=1f)
        val packet=TacticalMotionMessages.npc(1,motion,115,10000)
        val move=TacticalMoveMessages.move(packet.copyOfRange(4,packet.size))
        assertEquals(0.5f,move.velocity);assertEquals(2f,move.direction)
        assertEquals(listOf(TacticalMoveMessages.Point(0f,10f,0f),TacticalMoveMessages.Point(0f,20f,0f)),move.waypoints)
        assertEquals(TacticalMoveMessages.Point(0f,5f,0f),move.units.single().position)
    }
}
