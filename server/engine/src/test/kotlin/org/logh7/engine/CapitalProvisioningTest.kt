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
        val lost=CapitalProvisioning.capitals(galaxy,month.copy(bases=month.bases+(1049 to month.bases.getValue(1049).copy(power=3))))
        assertTrue(lost.isEmpty(),"a captured capital supplies nobody")
    }
}
