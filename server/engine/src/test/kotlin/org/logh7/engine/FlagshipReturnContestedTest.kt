package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** Manual P04: a sunk flagship avoids a return system that is in tactics. Author: Peppone Choi. */
class FlagshipReturnContestedTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2490,4,"Other",2),GalaxySystem(5,2015,4,"Foe",3)),
        listOf(GalaxyBase(1048,2487,80,0,"Soest",0,20f,86400,0,0f,2f),
            GalaxyBase(1060,2490,80,0,"Other",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val stocks=PlanetStocks(1,0,listOf(1048,1060).associateWith {PlanetStock(it,2,0,0,0,0)})
    private val victim=Navigator(1,10000,Power.EMPIRE,2700,1000,1000,1000).copy(returnBase=1048,mode=5)
    private val enemy=Navigator(1,10001,Power.ALLIANCE,2487,1000,1000,1000).copy(mode=5)

    @Test fun aContestedReturnSystemSendsTheFlagshipElsewhere() {
        assertEquals(1048,FlagshipRecovery.defeated(victim,100,galaxy,stocks,listOf(victim)).baseId)
        val moved=FlagshipRecovery.defeated(victim,100,galaxy,stocks,listOf(victim,enemy))
        assertEquals(1060,moved.baseId);assertEquals(2490,moved.grid)
    }
    @Test fun aFlagshipFromAnEliminatedCountryDoesNotBlockTheDesignatedOrOriginPlanet() {
        val third = CountryId(42)
        val ghost = enemy.copy(country = third)
        val birth = victim.copy(returnBase = 0, originBase = 1048)
        for (pilot in listOf(victim, birth)) {
            val moved = FlagshipRecovery.defeated(pilot, 100, galaxy, stocks, listOf(ghost), setOf(third))
            assertEquals(1048, moved.baseId)
            assertEquals(2487, moved.grid)
            assertEquals(pilot.returnBase, moved.returnBase)
            assertEquals(pilot.originBase, moved.originBase)
        }
    }

    @Test fun lostReturnAndOriginPlanetsDoNotSendTheFlagshipToTheEnemyStart() {
        val captured=stocks.copy(bases=listOf(1048,1060).associateWith {PlanetStock(it,3,0,0,0,0)})
        val moved=FlagshipRecovery.defeated(victim.copy(originBase=1060),100,galaxy,captured)
        assertEquals(2015,moved.grid)
        assertEquals(0,moved.baseId)
        assertEquals(0,moved.returnBase)
        assertEquals(1060,moved.originBase)
    }
    @Test fun aMissingWorldBaseCannotOverrideTheSavedOrigin() {
        val recovered=FlagshipRecovery.defeated(victim.copy(originBase=1060),100,galaxy,
            stocks.copy(bases=stocks.bases-1048))
        assertEquals(1060,recovered.baseId)
        assertEquals(2490,recovered.grid)
        assertEquals(0,recovered.returnBase)
        assertEquals(1060,recovered.originBase)
    }
    @Test fun removedEnemyBaseDoesNotBlockReturnToOtherwiseSafeSpace() {
        val captured=stocks.copy(bases=mapOf(1048 to PlanetStock(1048,3,0,0,0,0)))
        val g=Galaxy(galaxy.systems,galaxy.bases,galaxy.starts,
            initialOwners=mapOf(1048 to Allegiance.Country(Power.EMPIRE.country),1060 to Allegiance.Country(Power.ALLIANCE.country)))
        val present=GridForces.collect(g,1,emptyList(),null)
        assertTrue(present.getValue(2490).single().base)
        val hostile=enemy.copy(grid=2015)
        val moved=FlagshipRecovery.defeated(victim,100,g,captured,listOf(hostile))
        assertEquals(2490,moved.grid)
        assertEquals(0,moved.baseId)
        assertEquals(0,moved.returnBase)
    }
    @Test fun noSafeSystemFallsBackToTheDesignatedFriendlyDock() {
        val hostilePresence=listOf(enemy,enemy.copy(characterId=10002,grid=2490),enemy.copy(characterId=10003,grid=2015))
        val recovered=FlagshipRecovery.defeated(victim.copy(originBase=1060),100,galaxy,stocks,hostilePresence)
        assertEquals(1048,recovered.baseId)
        assertEquals(2487,recovered.grid)
        assertEquals(4,recovered.mode)
        assertEquals(1048,recovered.returnBase)
        assertEquals(1060,recovered.originBase)
    }
    @Test fun everyOwnSystemInTacticsKeepsTheDesignationAndReturnsToSafeSpace() {
        val other=enemy.copy(characterId=10002,grid=2490)
        val moved=FlagshipRecovery.defeated(victim,100,galaxy,stocks,listOf(victim,enemy,other))
        assertEquals(2015,moved.grid);assertEquals(0,moved.baseId)
        assertEquals(1048,moved.returnBase)
    }
}
