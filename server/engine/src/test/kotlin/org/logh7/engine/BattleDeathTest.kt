package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 전사 판정: 사망 기록·직위 공석·중복 판정 방지·확률. 작성: Peppone Choi. */
class BattleDeathTest {
    private val stocks=PlanetStocks(1,0,emptyMap(),strategic=StrategicState(people=mapOf(7 to StrategicPerson(rank=5)),
        offices=listOf(OfficeHolding(4,1049,7),OfficeHolding(5,1049,8))))
    @Test fun aKilledCharacterIsRecordedAndLosesItsOffices() {
        val after=BattleDeath.apply(stocks,listOf(7),500) {_,_->true}
        assertEquals(500,after.strategic.people.getValue(7).deceasedAt)
        assertTrue(BattleDeath.deceased(after,7));assertFalse(BattleDeath.deceased(after,8))
        assertEquals(listOf(OfficeHolding(5,1049,8)),after.strategic.offices)
        assertEquals(500,BattleDeath.apply(after,listOf(7),900) {_,_->true}.strategic.people.getValue(7).deceasedAt)
    }
    @Test fun survivorsAreUntouchedAndTheOddsAreLow() {
        assertSame(stocks,BattleDeath.apply(stocks,listOf(7),500) {_,_->false})
        val deaths=(1L..10_000L).count {BattleDeath.dies(7,it)}
        assertTrue(deaths in 350..650,"$deaths")
    }
}
