package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryEconomyTest {
    private val first=CountryId(42)
    private val second=CountryId(43)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Shared display",2)),listOf(
        GalaxyBase(1000,2487,100,0,"Capital A",0,20f,86400,0,0f,2f),
        GalaxyBase(1001,2487,100,1,"Fortress A",0,20f,86400,0,0f,2f),
        GalaxyBase(1002,2487,100,0,"Capital B",0,20f,86400,0,0f,2f),
        GalaxyBase(1003,2487,100,1,"Fortress B",0,20f,86400,0,0f,2f)),
        Power.entries.associateWith {2487})
    private val policy=PlanetProductionPolicy(economy=PlanetEconomyPolicy(dailyGrowthPerMillion=0))
    private fun initial()=PlanetStocks(1,0,galaxy.bases.associate {base ->
        val capital=base.kind==0
        base.id to PlanetStock(base.id,Allegiance.Country(if(base.id<1002)first else second),0,
            0,if(capital)1000 else 0,100_000,PlanetEconomy(100_000,if(capital)60_000 else 0,70,80))
    },deploymentInitialized=true,nationalTreasuries=mapOf(CountryId(2) to 73),
        strategic=StrategicState(capitals=mapOf(first to 1000,second to 1002)))
    private class Store(var value:PlanetStocks):PlanetStockStore {
        override fun load()=value
        override fun save(stocks:PlanetStocks) {value=stocks}
    }

    @Test fun dailySettlementKeepsActualCountriesSeparateFromTheirSharedSeedPower() {
        val before=initial()
        val saved=Store(before)
        val daily=PlanetProduction(galaxy,saved,0,policy)
        val after=daily.settle(1,86400)
        assertEquals(mapOf(CountryId(2) to 73L,first to 6000L,second to 6000L),after.nationalTreasuries)
        for(id in listOf(1000,1002)) {
            val capital=after.bases.getValue(id)
            assertEquals(6000L,capital.treasury)
            assertEquals(1000,capital.supplies)
            assertEquals(920,capital.food,"only this country's fortress draws a ration")
        }
        for(id in listOf(1001,1003)) {
            val fortress=after.bases.getValue(id)
            assertEquals(100_000,fortress.economy!!.population)
            assertEquals(0,fortress.food)
        }
        assertEquals(before.bases.mapValues {it.value.owner},after.bases.mapValues {it.value.owner})
        assertEquals(after,daily.settle(1,86400),"duplicate queries do not charge or credit twice")
        assertEquals(after,PlanetProduction(galaxy,saved,0,policy).settle(1,86400))
    }

    @Test fun losingOneCapitalDoesNotBorrowFromAnotherCountryAndCatchupMatchesRestarts() {
        val before=initial().let {it.copy(bases=it.bases+(1002 to it.bases.getValue(1002).copy(owner=Allegiance.Country(first))))}
        assertEquals(mapOf(first to 1000),CapitalProvisioning.capitals(galaxy,before))
        val one=PlanetProduction(galaxy,Store(before),0,policy).settle(1,86400)
        assertEquals(100_000,one.bases.getValue(1001).economy!!.population)
        assertEquals(99_000,one.bases.getValue(1003).economy!!.population)
        assertEquals(920,one.bases.getValue(1000).food,"the other country's fortress gets no food")
        val batch=PlanetProduction(galaxy,Store(before),0,policy).settle(1,20*86400L)
        val incremental=Store(before)
        for(day in 1..20)PlanetProduction(galaxy,incremental,0,policy).settle(1,day*86400L)
        assertEquals(batch,incremental.value)
    }

    @Test fun losingAnExplicitCapitalNeverFallsBackToTheOriginalCapital() {
        val world=Galaxy(galaxy.systems,galaxy.bases+galaxy.bases.first().copy(id=1049,name="オーディン"),galaxy.starts)
        val before=initial().let {it.copy(bases=it.bases+(1049 to it.bases.getValue(1000).copy(baseId=1049,owner=Allegiance.Country(CountryId(2)))),
            strategic=StrategicState(capitals=mapOf(CountryId(2) to 1000)))}
        assertTrue(CapitalProvisioning.capitals(world,before).isEmpty())
        val reclaimed=before.copy(bases=before.bases+(1000 to before.bases.getValue(1000).copy(owner=Allegiance.Country(CountryId(2)))))
        assertEquals(mapOf(CountryId(2) to 1000),CapitalProvisioning.capitals(world,reclaimed))
    }
}
