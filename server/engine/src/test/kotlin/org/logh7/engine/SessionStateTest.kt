package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class SessionStateTest {
    private val rules = SessionRules.load()
    @Test fun fullStateRestoresRemainderIdentitiesExclusionsBattleClockAndEventOrder() {
        val live = SessionSimulation(rules, 7)
        live.join("original", Power.EMPIRE, true, "connection-1")
        live.join("generated", Power.ALLIANCE, false, "connection-2")
        live.join("excluded", Power.EMPIRE, true); live.exclude("excluded")
        live.advance(101); live.openBattle("z"); live.advance(17); live.openBattle("a"); live.advance(333)
        val full = live.exportState()
        assertEquals(824L, full.gameMillis % 1000)
        val replay = SessionSimulation.restore(rules, full, false)
        assertEquals(full, replay.exportState())
        assertEquals(listOf("z", "a"), replay.exportState().battles.keys.toList())
        assertEquals(live.advance(300_001), replay.advance(300_001))
        assertEquals(live.exportState(), replay.exportState())
        val recovered = SessionSimulation.restore(rules, full)
        assertEquals(0, recovered.snapshot().online)
        assertTrue(recovered.exportState().participants.values.all { it.connection == null })
        assertEquals(Admission.IDENTITY_CHANGED, recovered.join("original", Power.ALLIANCE, true))
        assertEquals(Admission.ORIGINAL_RETURN_FORBIDDEN, recovered.join("excluded", Power.EMPIRE, true))
        assertEquals(Admission.FACTION_CHANGED, recovered.join("excluded", Power.ALLIANCE, false))
        assertEquals(Admission.ACCEPTED, recovered.join("generated", Power.ALLIANCE, false, "new"))
        assertEquals(Admission.ALREADY_ONLINE, recovered.join("generated", Power.ALLIANCE, false, "other"))
    }
    @Test fun restoreRetainsEndAndRejectsInventedClockCountersOrIdentity() {
        val simulation = SessionSimulation(rules)
        simulation.advance(123)
        simulation.updateTerritory(mapOf(Power.EMPIRE to 3, Power.ALLIANCE to 20), emptySet())
        val full = simulation.exportState()
        assertEquals(full, SessionSimulation.restore(rules, full, false).exportState())
        assertEquals(emptyList(), SessionSimulation.restore(rules, full).advance(1_000))
        listOf(full.copy(generation = 0), full.copy(gameMillis = -1), full.copy(strategyTicks = 1),
            full.copy(recoveries = 1), full.copy(battles = mapOf("battle" to BattleState(full.gameMillis + 1, 0))),
            full.copy(participants = mapOf(" " to Participant(Power.EMPIRE, false))),
            full.copy(ended = full.ended!!.copy(gameSeconds = 0))).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { SessionSimulation.restore(rules, invalid) }
        }
    }
    @Test fun rejectedTransitionDiscardsPartialEndOnRestartDeadlineOverflow() {
        val before = DurableWorldState(SessionSimulation(rules).exportState(), elapsed = Long.MAX_VALUE)
        val result = transitionSession(rules, before, SessionAction.Territory(mapOf(Power.EMPIRE to 3, Power.ALLIANCE to 20), emptySet()))
        assertTrue(result.rejected)
        assertEquals(before.session, result.state.session)
        assertNull(result.state.restartAt)
        assertTrue(result.events.isEmpty())
    }
}
