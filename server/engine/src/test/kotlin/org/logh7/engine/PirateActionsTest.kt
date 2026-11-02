package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** U-32a 우주해적: 변경 그리드 출현·주기·상한, 격침과 토벌 치안 보상. 작성: Peppone Choi. */
class PirateActionsTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2490,4,"Frontier",0)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),GalaxyBase(1050,2490,80,0,"Wild",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,1,1,1),1050 to PlanetStock(1050,0,0,1,1,1,PlanetEconomy(1000,500,50,40))))

    @Test fun fleetsAppearOnFrontierGridsEverySevenDaysUpToOnePerGrid() {
        assertEquals(listOf(2490),PirateActions.frontier(galaxy,stocks))
        val armed=PirateActions.settle(galaxy,stocks,100)
        assertTrue(armed.social.pirates.fleets.isEmpty());assertEquals(100+7*86400,armed.social.pirates.nextSpawnAt)
        val spawned=PirateActions.settle(galaxy,armed,100+7*86400)
        val fleet=spawned.social.pirates.fleets.values.single()
        assertEquals(2490,fleet.grid);assertTrue(fleet.units.size in 1..3 && fleet.units.all {it.boats==300 && it.id>=900_000})
        assertEquals(1,PirateActions.settle(galaxy,spawned,100+14*86400).social.pirates.fleets.size,"one fleet per frontier grid")
    }
    @Test fun sinkingEveryUnitSuppressesTheFleetAndRestoresOrder() {
        val spawned=PirateActions.settle(galaxy,PirateActions.settle(galaxy,stocks,100),100+7*86400)
        var s=spawned
        val units=spawned.social.pirates.fleets.values.single().units
        for(u in units) {
            val (next,lost)=PirateActions.damage(s,u.id,1000);assertEquals(300,lost);s=PirateActions.reward(next,10000)
        }
        assertEquals(PirateActions.MERIT*units.size,s.strategic.people.getValue(10000).merit)
        val cleared=PirateActions.settle(galaxy,s,100+8*86400)
        assertTrue(cleared.social.pirates.fleets.isEmpty());assertEquals(45,cleared.bases.getValue(1050).economy?.peace)
    }
}
