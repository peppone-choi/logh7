package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class InitialGovernmentTest {
    private val galaxy = Galaxy(
        listOf(GalaxySystem(3, 2487, 4, "Empire", 2), GalaxySystem(4, 2015, 4, "Alliance", 3)),
        listOf(GalaxyBase(1049, 2487, 80, 0, Capital.EMPIRE.baseName, 0, 20f, 86400, 0, 0f, 2f),
            GalaxyBase(1177, 2015, 80, 0, Capital.ALLIANCE.baseName, 0, 20f, 86400, 0, 0f, 2f)),
        mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015), initialGovernment = true)
    private val offices = OrganizationalOfficePolicy(galaxy)

    @Test fun initialNpcGovernmentAllowsExpeditionsUntilAPlayerCanSetTheNationalGoal() {
        val empire = Power.EMPIRE.country
        val alliance = Power.ALLIANCE.country
        val fresh = PlanetProduction(galaxy, null, 0, PlanetProductionPolicy()).settle(1, 0)
        assertEquals(alliance, NpcOccupation.expeditionEnemy(empire, fresh, galaxy, emptySet()))
        assertEquals(empire, NpcOccupation.expeditionEnemy(alliance, fresh, galaxy, emptySet()))

        val playerViceChair = offices.set(fresh, OfficeHolding(133, 1177, 10000, alliance))
        assertNull(NpcOccupation.expeditionEnemy(alliance, playerViceChair, galaxy, emptySet()))
        assertEquals(alliance, NpcOccupation.expeditionEnemy(empire, playerViceChair, galaxy, emptySet()))

        val withGoal = fresh.copy(social = fresh.social.copy(goals = GoalState(
            national = mapOf(empire.value to NationalGoal(1177, 901, 0)))))
        assertNull(NpcOccupation.expeditionEnemy(empire, withGoal, galaxy, emptySet()))
    }

    @Test fun startingOfficesUseTheNormalWorldStateAndOnlyReseedOnGenerationChange() {
        val fresh = PlanetProduction(galaxy, null, 0, PlanetProductionPolicy()).settle(1, 0)
        val expected = mapOf(1 to 901, 2 to 901, 5 to 902, 6 to 902, 132 to 903)
        assertEquals(expected, offices.all(fresh).associate { it.card to it.character })
        assertEquals(901, fresh.social.succession.emperor)
        assertEquals(0, fresh.social.succession.rival)
        assertEquals(emptyList(), offices.holders(fresh, 134))

        val dismissed = offices.set(fresh, OfficeHolding(6, 1049, 902), remove = true)
        val resumed = PlanetProduction(galaxy, null, 0, PlanetProductionPolicy(), restored = dismissed)
        assertTrue(offices.holders(resumed.settle(1, 0), 6).isEmpty())
        assertEquals(expected, offices.all(resumed.settle(2, 0)).associate { it.card to it.character })
    }

    @Test fun monthlySettlementKeepsInitialOfficesAndConvertsOnlyTheZeroMeritPlayer() {
        val production = PlanetProduction(galaxy, null, 0, PlanetProductionPolicy())
        val initial = production.settle(1, 0)
        production.identities = {
            InitialGovernment.roster(initial).map { official ->
                StrategicIdentity(official.id, official.country, official.rank, 0, InitialGovernment.baselineAbilities)
            } + StrategicIdentity(10000, Power.ALLIANCE.country, 1, 0, InitialGovernment.baselineAbilities)
        }
        val settled = production.settle(1, 30 * Justice.DAY)
        assertEquals(10, settled.strategic.people.getValue(10000).rank)
        assertEquals(1, settled.strategic.people.getValue(903).rank)
        assertEquals(903, offices.holders(settled, 132).single().character)
        assertEquals(901, offices.holders(settled, 1).single().character)
    }
}
