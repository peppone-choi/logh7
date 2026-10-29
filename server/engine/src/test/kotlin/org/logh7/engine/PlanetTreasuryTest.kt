package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class PlanetTreasuryTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1000,2487,60,0,"Home",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private class Store:PlanetStockStore {
        var value:PlanetStocks?=null
        var fail=false
        override fun load()=value
        override fun save(stocks:PlanetStocks) {if(fail)throw java.io.IOException("disk failure");value=stocks}
    }
    @Test fun dailyRevenueIsSplitOnceAndReopeningDoesNotRetaxPastDays() {
        val store=Store()
        val production=PlanetProduction(galaxy,store,0,PlanetProductionPolicy())
        val initial=production.settle(1,0)
        val people=initial.bases.getValue(1000).economy!!
        val collected=people.adults.toLong()*galaxy.conditions.getValue(1000).tax/100
        val daily=production.settle(1,86400)
        assertEquals(collected/2,daily.nationalTreasuries[2])
        assertEquals(collected-collected/2,daily.bases.getValue(1000).treasury)
        assertEquals(daily,production.settle(1,86400))
        assertEquals(daily,PlanetProduction(galaxy,store,0,PlanetProductionPolicy()).settle(1,86400))
        assertFailsWith<UnsupportedOperationException> {(daily.nationalTreasuries as MutableMap).clear()}
        val fresh=production.settle(2,0)
        assertEquals(0L,fresh.bases.getValue(1000).treasury)
        assertTrue(fresh.nationalTreasuries.isEmpty())
    }
    @Test fun offlineSettlementMatchesDailyQueriesAndFailurePublishesNoPartialMoney() {
        val store=Store()
        val production=PlanetProduction(galaxy,store,0,PlanetProductionPolicy())
        val before=production.settle(1,0)
        store.fail=true
        assertFailsWith<java.io.IOException> {production.settle(1,3*86400L)}
        assertEquals(before,production.snapshot(1))
        store.fail=false
        val offline=production.settle(1,3*86400L)
        val daily=PlanetProduction(galaxy,null,0,PlanetProductionPolicy())
        daily.settle(1,0)
        for(day in 1..3)daily.settle(1,day*86400L)
        assertEquals(offline,daily.snapshot(1))
    }
    @Test fun existingProductionWatermarkDoesNotInventPastTaxAndOccupationChangesOnlyFutureRecipient() {
        val store=Store()
        val production=PlanetProduction(galaxy,store,0,PlanetProductionPolicy())
        val initial=production.settle(1,0)
        store.value=initial.copy(producedDays=5)
        val reopened=PlanetProduction(galaxy,store,0,PlanetProductionPolicy())
        val legacy=reopened.settle(1,5*86400L)
        assertEquals(0L,legacy.bases.getValue(1000).treasury)
        assertTrue(legacy.nationalTreasuries.isEmpty())
        val taxed=reopened.settle(1,6*86400L)
        val seized=taxed.copy(bases=taxed.bases.mapValues {(_,base)->base.copy(power=3)})
        reopened.replace(taxed,seized)
        val next=reopened.settle(1,7*86400L)
        assertEquals(taxed.nationalTreasuries[2],next.nationalTreasuries[2])
        assertTrue(next.nationalTreasuries.getValue(3)>0)
        assertTrue(next.bases.getValue(1000).treasury>taxed.bases.getValue(1000).treasury)
    }
    @Test fun overflowingEitherTreasuryNeverPublishesOrWrapsMoney() {
        for(central in listOf(false,true)) {
            val store=Store()
            val production=PlanetProduction(galaxy,store,0,PlanetProductionPolicy())
            val initial=production.settle(1,0)
            val full=if(central)initial.copy(nationalTreasuries=mapOf(2 to Long.MAX_VALUE)) else
                initial.copy(bases=initial.bases.mapValues {(_,base)->base.copy(treasury=Long.MAX_VALUE)})
            production.replace(initial,full)
            assertFailsWith<ArithmeticException> {production.settle(1,86400)}
            assertEquals(full,production.snapshot(1))
            assertEquals(full,store.value)
        }
    }
}

