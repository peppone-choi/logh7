package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** Uninhabitable bases are fed from their side's own capital. Author: Peppone Choi. */
class CapitalProvisioningTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Valhalla",2),GalaxySystem(4,1253,4,"Iserlohn",2)),
        listOf(GalaxyBase(1049,2487,100,0,"オーディン",0,20f,86400,0,0f,2f),GalaxyBase(1040,1253,110,1,"Iserlohn",0,12f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    @Test fun aFortressStaysFedFromItsCapitalUntilTheCapitalIsLost() {
        val daily=PlanetProduction(galaxy,null,0,PlanetProductionPolicy())
        val month=daily.settle(1,30*86400L)
        val fortress=month.bases.getValue(1040)
        assertEquals(100_000,fortress.economy!!.population,"no starvation from day 11 any more")
        assertEquals(listOf(70,80),listOf(fortress.economy!!.approval,fortress.economy!!.peace))
        // 1000 initial food covers ten days; the remaining twenty rations of 100 came from Odin.
        val alone=PlanetProduction(Galaxy(listOf(galaxy.systems[0]),listOf(galaxy.bases[0]),galaxy.starts),null,0,PlanetProductionPolicy())
            .settle(1,30*86400L).bases.getValue(1049).food
        assertEquals(alone-2000,month.bases.getValue(1049).food)
        val lost=CapitalProvisioning.capitals(galaxy,month.copy(bases=month.bases+(1049 to month.bases.getValue(1049).copy(owner=Allegiance.fromLegacy(3)))))
        assertTrue(lost.isEmpty(),"a captured capital supplies nobody")
    }

    private val policy=PlanetProductionPolicy(economy=PlanetEconomyPolicy(dailyGrowthPerMillion=0))
    private class Store(var value:PlanetStocks):PlanetStockStore {
        var fail=false
        override fun load()=value
        override fun save(stocks:PlanetStocks) {
            if(fail)throw java.io.IOException("disk failure")
            value=stocks
        }
    }
    private fun stock(capitalFood:Int,world:Galaxy=galaxy,adults:Int=0)=PlanetStocks(1,0,
        world.bases.associate {base->base.id to PlanetStock(base.id,2,0,100_000,
            if(base.id==1049)capitalFood else 0,100_000,PlanetEconomy(100_000,adults,70,80))},
        deploymentInitialized=true)

    @Test fun onlyTheCapitalsRemainingFoodCanFeedAFortress() {
        for((food,population) in listOf(100 to 99_000,175 to 99_750)) {
            val next=PlanetProduction(galaxy,Store(stock(food)),0,policy).settle(1,86400)
            assertEquals(0,next.bases.getValue(1049).food)
            assertEquals(100_000,next.bases.getValue(1049).economy!!.population,"the capital eats first")
            assertEquals(population,next.bases.getValue(1040).economy!!.population,"short rations cause real starvation")
            assertEquals(0,next.bases.getValue(1040).food)
        }
    }

    @Test fun multipleFortressesShareOneFiniteDailyBalanceRegardlessOfSeedOrder() {
        val extra=galaxy.bases[1].copy(id=1041,name="Second")
        val forward=Galaxy(galaxy.systems,galaxy.bases+extra,galaxy.starts)
        val reversed=Galaxy(galaxy.systems,(galaxy.bases+extra).reversed(),galaxy.starts)
        fun settle(world:Galaxy)=PlanetProduction(world,Store(stock(250,world)),0,policy).settle(1,86400)
        val next=settle(forward)
        assertEquals(next,settle(reversed))
        assertEquals(100_000,next.bases.getValue(1040).economy!!.population)
        assertEquals(99_500,next.bases.getValue(1041).economy!!.population)
        assertEquals(0,next.bases.getValue(1049).food)
    }

    @Test fun supplyForecastUsesTheSameFiniteCapitalRationsAsTheNextSettlement() {
        val second=galaxy.bases[1].copy(id=1041,name="Second")
        val world=Galaxy(galaxy.systems,(galaxy.bases+second).reversed(),galaxy.starts)
        val saved=Store(stock(100,world,adults=60_000))
        val production=PlanetProduction(world,saved,0,policy)
        val before=production.settle(1,0)
        val forecast=production.supplyForecast(before)
        val first=before.bases.getValue(1040)
        val later=before.bases.getValue(1041)
        assertTrue(forecast(first))
        assertFalse(forecast(later))
        assertEquals(before,saved.value)

        val after=production.settle(1,86400)
        assertTrue(after.bases.getValue(first.baseId).supplies>first.supplies)
        assertEquals(later.supplies,after.bases.getValue(later.baseId).supplies)
    }

    @Test fun dailyHarvestCannotBeBorrowedFromAFutureDayDuringCatchup() {
        val initial=stock(0,adults=60_000)
        val one=PlanetProduction(galaxy,Store(initial),0,policy).settle(1,86400)
        assertEquals(99_200,one.bases.getValue(1040).economy!!.population,"only today's 20 surplus can be shipped")
        val batch=PlanetProduction(galaxy,Store(initial),0,policy).settle(1,20*86400L)
        val saved=Store(initial)
        for(day in 1..20)PlanetProduction(galaxy,saved,0,policy).settle(1,day*86400L)
        assertEquals(batch,saved.value,"daily queries and server restarts must not change rations")
    }

    @Test fun failedSaveDoesNotPublishOrDuplicateEitherSideOfTheTransfer() {
        val saved=Store(stock(175))
        val production=PlanetProduction(galaxy,saved,0,policy)
        val before=production.settle(1,0)
        saved.fail=true
        assertFailsWith<java.io.IOException> {production.settle(1,86400)}
        assertEquals(before,production.snapshot(1));assertEquals(before,saved.value)
        saved.fail=false
        val after=production.settle(1,86400)
        assertEquals(99_750,after.bases.getValue(1040).economy!!.population)
        assertEquals(after,production.settle(1,86400))
        assertEquals(after,PlanetProduction(galaxy,saved,0,policy).settle(1,86400))
    }

    @Test fun transfersRespectCapacityOwnershipAndIntegerBounds() {
        val base=galaxy.conditions.getValue(1040)
        val row=stock(0).bases.getValue(1040).copy(economy=PlanetEconomy(Int.MAX_VALUE,0,70,80))
        val capitals=mapOf(CountryId(2) to 1049)
        val bounded=policy.copy(maximumStock=25,economy=policy.economy.copy(foodPeoplePerUnit=1))
        val (fed,amount)=checkNotNull(CapitalProvisioning.topUp(row,base,bounded,capitals,Int.MAX_VALUE))
        assertEquals(25,amount);assertEquals(25,fed.food)
        assertNull(CapitalProvisioning.topUp(row.copy(food=26),base,bounded,capitals,Int.MAX_VALUE))
        for(ineligible in listOf(row.copy(owner=Allegiance.fromLegacy(3)),row.copy(camp=1),row.copy(owner=Allegiance.fromLegacy(0)),row.copy(economy=null)))
            assertNull(CapitalProvisioning.topUp(ineligible,base,policy,capitals,1000))
        assertNull(CapitalProvisioning.topUp(row,base.copy(habitability=1),policy,capitals,1000))
        val full=bounded.copy(maximumStock=Int.MAX_VALUE)
        val last=checkNotNull(CapitalProvisioning.topUp(row.copy(food=Int.MAX_VALUE-1),base,full,capitals,Int.MAX_VALUE))
        assertEquals(1,last.second);assertEquals(Int.MAX_VALUE,last.first.food)
    }

    @Test fun provisionedFoodAlsoCountsWhenCheckingDailyUnitProduction() {
        val world=Galaxy(listOf(galaxy.systems[0],galaxy.systems[1].copy(name="イゼルローン")),
            listOf(galaxy.bases[0],galaxy.bases[1].copy(name="イゼルローン")),galaxy.starts)
        val next=PlanetProduction(world,Store(stock(1000,world,60_000)),0,policy).settle(1,86400)
        val fortress=next.bases.getValue(1040)
        assertEquals(100_000,fortress.economy!!.population)
        assertTrue(fortress.units.work.values.any {it==24},"fed workers advance the existing production recipes")
    }
}
