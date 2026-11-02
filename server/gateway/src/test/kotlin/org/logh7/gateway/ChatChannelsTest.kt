package org.logh7.gateway

import org.junit.jupiter.api.Test
import kotlin.test.*

/** U-06 접두어 채널의 해석과 직무·소속 판정. 작성: Peppone Choi. */
class ChatChannelsTest {
    @Test fun aLeadingChannelWordSelectsTheChannelAndOrdinaryLinesStaySpotChat() {
        assertEquals(ChatChannel.ADMINISTRATION to "보급 요청",ChatChannel.parse("/군정 보급 요청"))
        assertEquals(ChatChannel.OWN_OUTFIT to "",ChatChannel.parse("/자부대"))
        assertEquals(ChatChannel.ADMINISTRATION to "supply",ChatChannel.parse("/GOV supply"))
        assertNull(ChatChannel.parse("/군정보급"));assertNull(ChatChannel.parse("안녕"))
        assertEquals(ChatChannel.OUTFITTED,ChatChannel.parse("/부대 집결")?.first,"/부대 is not confused with /자부대")
    }
    @Test fun membershipFollowsOutfitsAndHeldOffices() {
        assertTrue(ChatChannel.OUTFITTED.admits(emptyList(),200));assertFalse(ChatChannel.OUTFITTED.admits(emptyList(),0))
        assertTrue(ChatChannel.UNATTACHED.admits(emptyList(),0));assertFalse(ChatChannel.UNATTACHED.admits(emptyList(),200))
        assertTrue(ChatChannel.PROCLAMATION.admits(listOf(0,1),0),"the emperor proclaims")
        assertFalse(ChatChannel.PROCLAMATION.admits(listOf(0,59),0))
        assertTrue(ChatChannel.ADMINISTRATION.admits(listOf(25),0),"a central logistics office is administration")
        assertFalse(ChatChannel.ADMINISTRATION.admits(listOf(0,59,44),0),"personal, captain and outfit cards are not")
    }
}
