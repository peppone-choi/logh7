package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import kotlin.test.*

/** 계승 상태(사회 상태 16번째 필드) 왕복. 작성: Peppone Choi. */
class SuccessionCodecTest {
    @Test fun successionRoundTripsAsTheSixteenthField() {
        val s=SocialState(succession=SuccessionState(heir=20,vacant=true,emperor=22,rival=21,at=99))
        val text=SocialStateCodec.encode(s)
        assertEquals(16,text.split('|').size);assertEquals(s,SocialStateCodec.decode(text))
        assertEquals(SocialState(),SocialStateCodec.decode(SocialStateCodec.encode(SocialState())))
    }
}
