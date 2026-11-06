package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CountryEliminationTest {
    private val rules = SessionRules.load()
    private val empire = CountryId(2)
    private val alliance = CountryId(3)
    private val third = CountryId(42)
    private val fourth = CountryId(43)
    private val systems = mapOf(empire to 8, alliance to 8, third to 1, fourth to 1)

    @Test fun firstDefeatDoesNotEndTheSessionOrRestartClockAndOnlyLastSurvivorEndsIt() {
        var state = DurableWorldState(SessionSimulation(rules).exportState())
        fun apply(action: SessionAction): SessionTransition = transitionSession(rules, state, action).also {
            assertFalse(it.rejected); state = it.state
        }
        apply(SessionAction.Join("third", third, false, "c", 1))
        val first = apply(SessionAction.CountryTerritory(systems, mapOf(third to fourth)))
        assertEquals(listOf(WorldEvent.CountryEliminated(third, CountryDefeat(EndReason.CAPITAL_CAPTURED, 0, fourth))), first.events)
        assertNull(state.session.ended); assertNull(state.restartAt)
        assertEquals(Admission.COUNTRY_ELIMINATED, apply(SessionAction.Join("third", third, false, "c", 1)).admission)
        assertEquals(Admission.COUNTRY_ELIMINATED, apply(SessionAction.Join("new", third, false, "d", 1)).admission)
        assertEquals(Admission.ACCEPTED, apply(SessionAction.Join("other", fourth, false, "e", 1)).admission)
        apply(SessionAction.Advance(1000))
        assertEquals(24_000, state.session.gameMillis)
        assertNull(state.restartAt)
        val old = state.session.eliminated.getValue(third)
        assertTrue(apply(SessionAction.CountryTerritory(systems, mapOf(third to empire))).events.isEmpty())
        assertEquals(old, state.session.eliminated.getValue(third))
        apply(SessionAction.CountryTerritory(systems, mapOf(alliance to fourth)))
        assertNull(state.session.ended)
        val final = apply(SessionAction.CountryTerritory(systems, mapOf(fourth to empire)))
        val end = final.events.filterIsInstance<WorldEvent.Ended>().single().end
        assertEquals(setOf(third, fourth, alliance), end.affectedCountries)
        assertEquals(setOf(empire), end.remainingCountries)
        assertEquals(setOf(Power.ALLIANCE), end.affected)
        assertEquals(1000 + rules.restartDelayRealMillis, state.restartAt)
        assertEquals(Admission.ENDED, apply(SessionAction.Join("winner", empire, false, "w", 1)).admission)
        apply(SessionAction.Advance(state.restartAt!!))
        assertEquals(2, state.session.generation)
        assertTrue(state.session.countries.isEmpty()); assertTrue(state.session.eliminated.isEmpty())
        assertEquals(Admission.ACCEPTED, apply(SessionAction.Join("third", third, false, "c", 2)).admission)
    }

    @Test fun oneBaseFoundingIsAllowedAndCapitalLossTakesPrecedenceOverExplicitReduction() {
        val session = SessionSimulation(rules)
        assertTrue(session.updateCountryTerritory(systems, emptyMap(), setOf(empire, alliance)).isEmpty())
        assertEquals(Admission.ACCEPTED, session.join("founder", third, false))
        val events = session.updateCountryTerritory(systems + (alliance to 3), mapOf(alliance to third), setOf(empire, alliance))
        assertEquals(EndReason.CAPITAL_CAPTURED, (events.single() as WorldEvent.CountryEliminated).defeat.reason)
        assertEquals(setOf(alliance), session.snapshot().eliminated.keys)
        assertNull(session.snapshot().ended)
        assertFailsWith<IllegalArgumentException> { session.defect("founder", alliance) }
    }

    @Test fun simultaneousCapitalLossesHaveNoInventedWinnerAndOnlyOneFinalEvent() {
        val session = SessionSimulation(rules)
        val events = session.updateCountryTerritory(systems,
            mapOf(empire to alliance, alliance to third, third to fourth, fourth to empire))
        assertEquals(4, events.filterIsInstance<WorldEvent.CountryEliminated>().size)
        val end = events.filterIsInstance<WorldEvent.Ended>().single().end
        assertEquals(systems.keys, end.affectedCountries); assertTrue(assertNotNull(end.remainingCountries).isEmpty())
        assertTrue(session.advance(1000).isEmpty())
        assertTrue(session.updateCountryTerritory(systems, emptyMap()).isEmpty())
    }

    @Test fun timeoutRetainsDefeatsAndAllSurvivorsWithoutCallingThemWinners() {
        val session = SessionSimulation(rules)
        session.updateCountryTerritory(systems, mapOf(third to empire))
        val end = session.advance(Long.MAX_VALUE).filterIsInstance<WorldEvent.Ended>().single().end
        assertEquals(EndReason.TIME_LIMIT, end.reason)
        assertTrue(end.affectedCountries.isEmpty())
        assertEquals(setOf(empire, alliance, fourth), end.remainingCountries)
        assertEquals(setOf(third), session.snapshot().eliminated.keys)
        assertEquals(session.exportState(), SessionSimulation.restore(rules, session.exportState(), false).exportState())
    }

    @Test fun countryRosterCannotShrinkAndRejectedFinalTransitionDoesNotLeakDefeat() {
        var before = DurableWorldState(SessionSimulation(rules).exportState(), elapsed = Long.MAX_VALUE)
        before = transitionSession(rules, before, SessionAction.CountryTerritory(systems, mapOf(third to fourth))).state
        val removed = transitionSession(rules, before, SessionAction.CountryTerritory(systems - third, emptyMap()))
        assertTrue(removed.rejected); assertEquals(before.session, removed.state.session)
        val result = transitionSession(rules, before, SessionAction.CountryTerritory(systems, mapOf(alliance to empire, fourth to empire)))
        assertTrue(result.rejected); assertEquals(before.session, result.state.session)
        assertNull(result.state.restartAt); assertTrue(result.events.isEmpty())
    }

    @Test fun restoreRejectsInventedDefeatsAndExportedCollectionsCannotChangeLiveState() {
        val session = SessionSimulation(rules)
        session.join("captain", third, false, "connection")
        session.updateCountryTerritory(systems, mapOf(third to empire))
        val state = session.exportState()
        val snapshot = session.snapshot()
        runCatching { (snapshot.countries as MutableSet).clear() }
        runCatching { (snapshot.eliminated as MutableMap).clear() }
        assertEquals(systems.keys, session.snapshot().countries)
        assertEquals(setOf(third), session.snapshot().eliminated.keys)
        assertEquals(0, SessionSimulation.restore(rules, state).snapshot().online)
        for (defeat in listOf(CountryDefeat(EndReason.CAPITAL_CAPTURED, 1, empire),
            CountryDefeat(EndReason.CAPITAL_CAPTURED, 0, third), CountryDefeat(EndReason.CAPITAL_CAPTURED, 0, CountryId(99)))) {
            assertFailsWith<IllegalArgumentException> { SessionSimulation.restore(rules, state.copy(eliminated = mapOf(third to defeat))) }
        }
    }

    @Test fun eliminatedCountryCannotNavigateWhileAnUnaffectedCountryStillCan() {
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "A", 2), GalaxySystem(4, 2015, 4, "B", 3)),
            emptyList(), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
        val session = SessionSimulation(rules)
        session.join("lost", empire, false, "lost-c")
        session.join("live", alliance, false, "live-c")
        session.updateCountryTerritory(systems, mapOf(empire to third))
        val navigation = Navigation(galaxy, null, NavigationPolicy())
        assertEquals(NavigationRejection.SESSION, navigation.apply(NavigationRequest("lost", 1, empire, "lost-c", 1), session.exportState()).rejection)
        assertNotNull(navigation.apply(NavigationRequest("live", 2, alliance, "live-c", 1), session.exportState()).player)
    }

    @Test fun eliminatedCountryCannotWinTheFinalResultOnPopulation() {
        val session = SessionSimulation(rules)
        session.updateCountryTerritory(mapOf(empire to 4, alliance to 3), emptyMap(), setOf(empire, alliance))
        val end = assertNotNull(session.snapshot().ended)
        val result = SessionResults.assess(end, SessionStatistics(population = 100), mapOf(
            Power.EMPIRE to SessionStatistics(population = 1), Power.ALLIANCE to SessionStatistics(population = 99)))
        assertEquals(SessionGrade.LOCAL, result.powers.getValue(Power.EMPIRE).grade)
        assertEquals(SessionGrade.DEFEAT, result.powers.getValue(Power.ALLIANCE).grade)
    }

    @Test fun aCountryRosterCannotBypassTheNativeWorldFenceOrWriteRecoveryState() = runBlocking {
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "A", 2), GalaxySystem(4, 2015, 4, "B", 3)),
            emptyList(), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
        val simulation = SessionSimulation(rules)
        simulation.join("legacy", empire, false, "connected")
        simulation.updateCountryTerritory(systems, mapOf(third to fourth))
        var writes = 0; var closed = false
        val store = object : SessionDurability {
            override fun load() = DurableWorldState(simulation.exportState())
            override fun commit(action: SessionAction, transition: SessionTransition) { writes++ }
            override fun close() { closed = true }
        }
        assertFailsWith<IllegalStateException> { WorldEngine(this, rules, ticking = false, durability = store, galaxy = galaxy) }
        assertEquals(0, writes); assertTrue(closed)
    }

    @Test fun simultaneousReductionDoesNotAwardAPopulationVictoryToAnEliminatedCountry() {
        val session = SessionSimulation(rules)
        session.updateCountryTerritory(mapOf(empire to 3, alliance to 3), emptyMap(), setOf(empire, alliance))
        val end = assertNotNull(session.snapshot().ended)
        assertEquals(emptySet(), end.remainingCountries)
        val result = SessionResults.assess(end, SessionStatistics(population = 100), mapOf(
            Power.EMPIRE to SessionStatistics(population = 1), Power.ALLIANCE to SessionStatistics(population = 99)))
        assertTrue(result.powers.values.all {it.grade == SessionGrade.UNRESOLVED})
        val legacy = SessionEnd(end.reason, end.affected, end.gameSeconds)
        assertEquals(SessionGrade.LOCAL, SessionResults.assess(legacy, result.total,
            result.powers.mapValues {it.value.statistics}).powers.getValue(Power.ALLIANCE).grade)
    }
}
