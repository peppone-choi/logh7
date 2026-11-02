package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** 2004-06-11 격침 경험치 감소의 계급별 양과 능력 하락 처리. 작성: Peppone Choi. */
class SinkingExperienceTest {
    private fun stocks(experience:List<Int>,rank:Int?=null)=PlanetStocks(1,0,emptyMap(),
        strategic=StrategicState(people=mapOf(7 to StrategicPerson(rank=rank,abilities=listOf(50,50,50,50,50,50,50,1)))),
        social=SocialState(academy=AcademyState(experience=mapOf(7 to experience))))
    @Test fun rankBandsFollowThePatchTable() {
        assertEquals(listOf(50,30,30,10,10,5,5,1,1),listOf(2,3,7,8,10,11,13,14,20).map {SinkingExperience.penalty(it,false)})
        assertEquals(50,SinkingExperience.penalty(1,true));assertEquals(1,SinkingExperience.penalty(1,false))
    }
    @Test fun experienceFallsAndAnExhaustedAbilityDropsButNeverBelowOne() {
        val after=SinkingExperience.apply(stocks(listOf(40,10,0,9,99,0,30,0),rank=8),listOf(7),emptyList())
        assertEquals(listOf(30,0,90,99,89,90,20,0),after.social.academy.experience.getValue(7))
        assertEquals(listOf(50,50,49,49,50,49,50,1),after.strategic.people.getValue(7).abilities)
    }
    @Test fun theIdentityRankAppliesWithoutAStoredOverride() {
        val after=SinkingExperience.apply(stocks(List(8){60}),listOf(7),listOf(StrategicIdentity(7,2,3,0,List(8){50})))
        assertEquals(List(8){30},after.social.academy.experience.getValue(7))
    }
}
