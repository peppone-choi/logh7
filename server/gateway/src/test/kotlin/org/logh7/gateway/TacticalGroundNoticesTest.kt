package org.logh7.gateway

import org.logh7.engine.NavigationRejection
import org.logh7.engine.TacticalGroundAction
import org.logh7.engine.TacticalGroundOrder
import org.logh7.protocol.ChatMessages
import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalGroundNoticesTest {
    @Test fun resumedNoticeSkipsExpiredOrdersAndRoundsUpTheNextDeadline() {
        val deploy = TacticalGroundOrder(TacticalGroundAction.DEPLOY, 10002, 2487, 1, 1000, 900, 1240)
        val withdraw = deploy.copy(action = TacticalGroundAction.WITHDRAW, carrier = 2, readyAt = 1101)
        val orders = listOf(deploy, withdraw)
        assertEquals("[육전대] 2건 진행 중 · 다음 완료 약 2초", TacticalGroundNotices.resumed(orders, 1076, 24))
        assertEquals("[육전] 진행 중 · 약 6초 남음", TacticalGroundNotices.resumed(orders, 1101, 24))
        assertNull(TacticalGroundNotices.resumed(orders, 1240, 24))
    }

    @Test fun everyRefusalReasonFitsOneSpotLineWithoutTruncation() {
        for(deploy in listOf(true,false))for(reason in NavigationRejection.entries) {
            val text=TacticalGroundNotices.text(deploy,reason) ?: continue
            assertTrue(text.length<=32,text) // about two lines of the tactical chat box
            ChatMessages.spot(ChatMessages.Spot(10002,text),100)
        }
        assertTrue(TacticalGroundNotices.text(true,NavigationRejection.CREW)!!.contains("장갑병"))
        assertNull(TacticalGroundNotices.text(true,null))
        assertTrue(FORTRESS_PROPULSION_LOST.length<=32,FORTRESS_PROPULSION_LOST)
        ChatMessages.spot(ChatMessages.Spot(10003,FORTRESS_PROPULSION_LOST),100)
    }
}
