package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** Manual P04: a sunk flagship avoids a return system that is in tactics. Author: Peppone Choi. */
class FlagshipReturnContestedTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2490,4,"Other",2),GalaxySystem(5,2015,4,"Foe",3)),
        listOf(GalaxyBase(1048,2487,80,0,"Soest",0,20f,86400,0,0f,2f),
            GalaxyBase(1060,2490,80,0,"Other",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val stocks=PlanetStocks(1,0,emptyMap())
    private val victim=Navigator(1,10000,Power.EMPIRE,2700,1000,1000,1000).copy(returnBase=1048,mode=5)
    private val enemy=Navigator(1,10001,Power.ALLIANCE,2487,1000,1000,1000).copy(mode=5)

    @Test fun aContestedReturnSystemSendsTheFlagshipElsewhere() {
        assertEquals(1048,FlagshipRecovery.defeated(victim,100,galaxy,stocks,listOf(victim)).baseId)
        val moved=FlagshipRecovery.defeated(victim,100,galaxy,stocks,listOf(victim,enemy))
        assertEquals(1060,moved.baseId);assertEquals(2490,moved.grid)
    }
    @Test fun everyOwnSystemInTacticsKeepsTheChosenReturnBase() {
        val other=enemy.copy(characterId=10002,grid=2490)
        assertEquals(1048,FlagshipRecovery.defeated(victim,100,galaxy,stocks,listOf(victim,enemy,other)).baseId)
    }
}
