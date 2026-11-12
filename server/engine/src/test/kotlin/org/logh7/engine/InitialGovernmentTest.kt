package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class InitialGovernmentTest {
    private val galaxy = Galaxy(
        listOf(GalaxySystem(3, 2487, 4, "Empire", 2), GalaxySystem(4, 2015, 4, "Alliance", 3)),
        listOf(GalaxyBase(1049, 2487, 80, 0, Capital.EMPIRE.baseName, 0, 20f, 86400, 0, 0f, 2f),
            GalaxyBase(1050, 2487, 80, 0, "Planet", 0, 30f, 86400, 0, 0f, 2f),
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
        val expected = mapOf(1 to 901, 2 to 901, 5 to 902, 6 to 902, 132 to 903, 139 to 904)
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

    @Test fun monthlyNpcAppointmentEnablesTheExistingRecruitmentCommandForBothCountries() {
        val empire = Navigator(1, 10000, Power.EMPIRE, 2487, 1000, 1000, 1000, baseId = 1050)
        val alliance = Navigator(1, 10001, Power.ALLIANCE, 2015, 1000, 1000, 1000, baseId = 1177)
        val other = alliance.copy(characterId = 10002)
        val players = listOf(empire, alliance, other)
        val fresh = PlanetProduction(galaxy, null, 0, PlanetProductionPolicy()).settle(1, 0)
        val initial = fresh.copy(strategic = fresh.strategic.copy(people = fresh.strategic.people + mapOf(
            empire.characterId to StrategicPerson(rank = 1, militaryRank = 13),
            alliance.characterId to StrategicPerson(rank = 1, merit = 20),
            other.characterId to StrategicPerson(rank = 1, merit = 10))))
        val people = InitialGovernment.roster(initial).map { StrategicIdentity(it.id, it.country, it.rank, 0, InitialGovernment.baselineAbilities) } +
            players.map { StrategicIdentity(it.characterId, it.country, 13, 0, InitialGovernment.baselineAbilities) }
        val production = PlanetProduction(galaxy, null, 0, PlanetProductionPolicy(), restored = initial, civicPlayers = { players })
        production.identities = { people }
        assertEquals(emptyList(), offices.holders(production.settle(1, 30 * Justice.DAY - 1), 54))
        val appointed = production.settle(1, 30 * Justice.DAY)
        assertEquals(10000, offices.holders(appointed, 54).single().character)
        assertEquals(904, offices.holders(appointed, 134).single().character)
        assertEquals(10001, offices.holders(appointed, 192).single().character)
        assertTrue(offices.held(appointed, 10002).isEmpty())
        assertEquals(appointed, NpcGovernmentAppointments.settle(galaxy, appointed, people, players, emptySet()))

        for ((player, card) in listOf(empire to 54, alliance to 192)) {
            val session = SessionSimulation(SessionRules.load()).also { it.join("pilot", player.power, false, "c") }.exportState()
            val kind = ProductionActions.kinds(player.country!!, false).first().kind
            val order = SocialRequest(StrategyActionRequest("pilot", "c", player.characterId, player.power, 1,
                ProductionActions.RECRUIT, card, 1, value = kind))
            assertEquals(SocialRejection.CARD, ProductionActions.evaluate(galaxy, order, session, player, initial).rejection)
            val result = ProductionActions.evaluate(galaxy, order, session, player, appointed)
            assertNull(result.rejection)
            assertTrue(result.changed)
            assertEquals(840, result.player?.mcp)
        }
        val withoutMinister = offices.set(initial, OfficeHolding(6, 1049, 902), remove = true)
        assertEquals(withoutMinister, NpcGovernmentAppointments.settle(galaxy, withoutMinister, people, listOf(empire), emptySet()))
        assertEquals(initial, NpcGovernmentAppointments.settle(galaxy, initial, people, listOf(alliance), setOf(Power.ALLIANCE.country)))
    }
}
