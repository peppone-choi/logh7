package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class LogisticsOfficePolicyTest {
    private val empire=GalaxyBase(1049,2487,80,0,"オーディン",0,20f,86400,0,0f,2f)
    private val alliance=empire.copy(id=1177,grid=2015,name="ハイネセン")
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(empire,alliance),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    @Test fun originalOfficeCardsUseStoredHoldersAndTheCorrectCapitalHeadquarters() {
        val policy=LogisticsOfficePolicy(galaxy)
        for (office in LogisticsOffice.entries) {
            val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,0,0,0),1177 to PlanetStock(1177,3,0,0,0,0)),
                logisticsOfficers=mapOf(10000 to office))
            val base=if (office.power==2) 1049 else 1177
            assertEquals(listOf(office.card to (base*100+office.facility+1)),policy.cards(stocks,10000,office.power))
            assertTrue(policy.cards(stocks,10001,office.power).isEmpty())
            assertTrue(policy.cards(stocks,10000,if (office.power==2) 3 else 2).isEmpty())
            val lost=stocks.copy(bases=stocks.bases+(base to stocks.bases.getValue(base).copy(camp=1)))
            assertTrue(policy.cards(lost,10000,office.power).isEmpty())
            assertEquals(office,LogisticsOffice.of(office.card))
        }
    }
}
