package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalPersonnelTest {
    private fun stocks(total:Long=30,credited:Long=0,merit:Long=100)=PlanetStocks(1,0,emptyMap(),
        strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=5,merit=merit,money=900))),
        tacticalCommands=TacticalCommandState(ranks=mapOf(10000 to 20),merits=mapOf(10000 to total),
            creditedMerits=if(credited==0L)emptyMap() else mapOf(10000 to credited)))
    @Test fun legacyLedgerIsCreditedOnceAndPersonnelRankWinsOverCachedTacticalRank() {
        val old=stocks()
        assertEquals(130L,TacticalPersonnel.merit(old,10000));assertEquals(5,TacticalPersonnel.rank(old,10000))
        val credited=TacticalPersonnel.credit(old)
        assertEquals(130L,credited.strategic.people.getValue(10000).merit)
        assertEquals(900L,credited.strategic.people.getValue(10000).money)
        assertEquals(30L,credited.tacticalCommands.creditedMerits[10000])
        assertEquals(credited,TacticalPersonnel.credit(credited))
        val next=TacticalPersonnel.credit(credited.copy(tacticalCommands=credited.tacticalCommands.copy(merits=mapOf(10000 to 31L))))
        assertEquals(131L,next.strategic.people.getValue(10000).merit)
    }
    @Test fun saturatedCreditsCannotBeClaimedAgainAfterMeritIsSpent() {
        val saturated=TacticalPersonnel.credit(stocks(total=Long.MAX_VALUE,merit=0xffffffffL-1))
        assertEquals(0xffffffffL,saturated.strategic.people.getValue(10000).merit)
        assertEquals(Long.MAX_VALUE,saturated.tacticalCommands.creditedMerits[10000])
        val spent=saturated.copy(strategic=saturated.strategic.copy(people=mapOf(10000 to StrategicPerson(merit=50))))
        assertEquals(50L,TacticalPersonnel.credit(spent).strategic.people.getValue(10000).merit)
        assertFails {stocks(total=1,credited=2)}
    }
}
