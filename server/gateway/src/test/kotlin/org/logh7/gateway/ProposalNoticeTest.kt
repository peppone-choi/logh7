package org.logh7.gateway

import org.logh7.protocol.ChatMessages
import org.junit.jupiter.api.Test
import kotlin.test.*

class ProposalNoticeTest {
    @Test fun arrivalLineNamesTheSenderAndFitsOneChatMessage() {
        val text=ProposalNotice.arrived("로엔그람가의긴성씨열세자를넘는이름")
        assertTrue(text.startsWith("[직무 편지] 로엔그람가의긴성씨열세자") && text.length<=64,text)
        ChatMessages.spot(ChatMessages.Spot(10002,text),100)
    }
}
