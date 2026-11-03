package org.logh7.gateway

import org.logh7.engine.NavigationRejection
import org.logh7.protocol.ChatMessages
import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalGroundNoticesTest {
    @Test fun everyRefusalReasonFitsOneSpotLineWithoutTruncation() {
        for(deploy in listOf(true,false))for(reason in NavigationRejection.entries) {
            val text=TacticalGroundNotices.text(deploy,reason) ?: continue
            assertTrue(text.length<=32,text) // about two lines of the tactical chat box
            ChatMessages.spot(ChatMessages.Spot(10002,text),100)
        }
        assertTrue(TacticalGroundNotices.text(true,NavigationRejection.BASE)!!.contains("장갑병"))
        assertNull(TacticalGroundNotices.text(true,null))
    }
}
