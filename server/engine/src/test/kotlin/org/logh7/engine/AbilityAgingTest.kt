package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 나이 효과의 잠정 규칙(고령 감소·저연령 성장·중간 연령 불변). 작성: Peppone Choi. */
class AbilityAgingTest {
    private val empty=PlanetStocks(1,0,emptyMap())
    private fun after(age:Int,abilities:List<Int>,months:LongRange)=months.fold(empty) {s,m->
        AbilityAging.monthly(s,listOf(StrategicIdentity(7,2,9,0,abilities,age)),m)}.strategic.people[7]?.abilities
    @Test fun middleAgedAndUnknownAgesNeverChange() {
        assertNull(after(35,List(8){60},1L..72L))
        assertNull(after(0,List(8){60},1L..72L))
    }
    @Test fun theOldLoseOnlyAbovefiftyAndNeverBelowFiftyOne() {
        val values=assertNotNull(after(70,listOf(80,80,80,80,50,40,51,90),1L..72L))
        assertTrue(values.take(4).all {it in 51..79});assertEquals(listOf(50,40),values.subList(4,6))
        assertTrue(values[6] in 50..51);assertTrue(values[7]<90)
    }
    @Test fun theYoungGrowUntilTwentyAndStopAtTheCap() {
        val values=assertNotNull(after(15,List(8){50},1L..36L))
        assertTrue(values.all {it>50});assertTrue(values.all {it<=AcademyActions.ABILITY_CAP})
        // From month 60 the same character is 20 and no longer grows.
        assertNull(after(15,List(8){50},60L..72L))
        assertTrue(assertNotNull(after(15,List(8){99},1L..36L)).all {it==AcademyActions.ABILITY_CAP})
    }
}
