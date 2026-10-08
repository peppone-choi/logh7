package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class SessionSimulationTest {
    private val rules = SessionRules.load()
    @Test fun manualW10_oneRealHourEqualsOneGameDayAndFractionalStepsArePreserved() {
        val session = SessionSimulation(rules)
        val events = session.advance(3_600_000)
        assertEquals(86_400L, session.snapshot().gameSeconds)
        assertEquals(3600L, session.snapshot().strategyTicks)
        assertEquals(12L, session.snapshot().cpRecoveryEvents)
        assertEquals(listOf(WorldEvent.CpRecovery(7200, 7200, 12)), events.filterIsInstance<WorldEvent.CpRecovery>())
        val fractions = SessionSimulation(rules)
        repeat(1000) { fractions.advance(1) }
        assertEquals(24L, fractions.snapshot().gameSeconds)
    }
    @Test fun manualW27_cpRecoveryOccursEveryFiveRealMinutesIncludingOfflineParticipants() {
        val session = SessionSimulation(rules)
        session.join("offline", Power.EMPIRE, false); session.disconnect("offline")
        assertTrue(session.advance(299_999).none { it is WorldEvent.CpRecovery })
        assertEquals(listOf(WorldEvent.CpRecovery(7200, 7200, 1)), session.advance(1).filterIsInstance<WorldEvent.CpRecovery>())
        assertEquals(1, session.snapshot().participants); assertEquals(0, session.snapshot().online)
        assertEquals(1L, session.snapshot().cpRecoveryEvents)
    }
    @Test fun manualW10_battlesShareGlobalTimeAndMissedTicksAreBatchedWithoutPausingOtherInstances() {
        val session = SessionSimulation(rules)
        session.openBattle("first"); session.advance(1000); session.openBattle("second")
        val events = session.advance(2000)
        assertEquals(72L, session.snapshot().gameSeconds)
        assertEquals(mapOf("first" to 12L, "second" to 8L), session.snapshot().tacticalTicks)
        assertEquals(2, events.filterIsInstance<WorldEvent.TacticalTicks>().size)
        session.closeBattle("first"); session.advance(1000)
        assertEquals(mapOf("second" to 12L), session.snapshot().tacticalTicks)
    }
    @Test fun manualW10_capacityIsTwoThousandAndOfflineCharactersKeepTheirSlot() {
        val session = SessionSimulation(rules)
        repeat(2000) { assertEquals(Admission.ACCEPTED, session.join("account$it", Power.EMPIRE, false)) }
        session.disconnect("account0")
        assertEquals(Admission.FULL, session.join("overflow", Power.ALLIANCE, false))
        assertEquals(Admission.ACCEPTED, session.join("account0", Power.EMPIRE, false))
        assertEquals(2000, session.snapshot().participants)
        session.exclude("account0")
        assertEquals(Admission.ACCEPTED, session.join("replacement", Power.ALLIANCE, false))
    }
    @Test fun manualW10_excludedPlayerReturnsOnlyWithGeneratedCharacterInPreviousFaction() {
        val session = SessionSimulation(rules)
        session.join("owner", Power.EMPIRE, true); session.exclude("owner")
        assertEquals(Admission.ORIGINAL_RETURN_FORBIDDEN, session.join("owner", Power.EMPIRE, true))
        assertEquals(Admission.FACTION_CHANGED, session.join("owner", Power.ALLIANCE, false))
        assertEquals(Admission.ACCEPTED, session.join("owner", Power.EMPIRE, false))
        assertEquals(Admission.IDENTITY_CHANGED, session.join("owner", Power.ALLIANCE, false))
    }
    @Test fun manualW12_capitalCaptureTerminatesOnceAndStopsTicks() {
        val session = SessionSimulation(rules)
        val events = session.updateTerritory(mapOf(Power.EMPIRE to 8, Power.ALLIANCE to 8), setOf(Power.ALLIANCE))
        assertEquals(EndReason.CAPITAL_CAPTURED, (events.single() as WorldEvent.Ended).end.reason)
        assertEquals(setOf(Power.ALLIANCE), session.snapshot().ended!!.affected)
        assertTrue(session.advance(100_000).isEmpty())
        assertEquals(Admission.ENDED, session.join("new", Power.EMPIRE, false))
        assertTrue(session.updateTerritory(mapOf(Power.EMPIRE to 3, Power.ALLIANCE to 3), emptySet()).isEmpty())
    }
    @Test fun manualW12_threeControlledSystemsEndsSessionButFourDoesNot() {
        val session = SessionSimulation(rules)
        assertTrue(session.updateTerritory(mapOf(Power.EMPIRE to 4, Power.ALLIANCE to 4), emptySet()).isEmpty())
        val events = session.updateTerritory(mapOf(Power.EMPIRE to 3, Power.ALLIANCE to 4), emptySet())
        assertEquals(EndReason.SYSTEMS_REDUCED, (events.single() as WorldEvent.Ended).end.reason)
        assertEquals(setOf(Power.EMPIRE), session.snapshot().ended!!.affected)
    }
    @Test fun manualW12_timeLimitUsesClientCalendarAndClampsLargeCatchup() {
        val session = SessionSimulation(rules)
        assertEquals(204_422_400L, rules.endSeconds) // (6 * 360 + 6 * 30 + 26) days since 795-01-01
        val realMillis = (rules.endSeconds - rules.startSeconds) * 1000 / rules.speed
        session.advance(realMillis - 1)
        assertNull(session.snapshot().ended)
        val events = session.advance(1)
        assertEquals(EndReason.TIME_LIMIT, (events.last() as WorldEvent.Ended).end.reason)
        assertEquals(rules.endSeconds, session.snapshot().gameSeconds)
        val huge = SessionSimulation(rules)
        huge.advance(Long.MAX_VALUE)
        assertEquals(rules.endSeconds, huge.snapshot().gameSeconds)
    }
    @Test fun manualW10_restartRestoresInitialTimeAndClearsMembershipBattlesAndReturnRestrictions() {
        val session = SessionSimulation(rules)
        assertFails { session.restart() }
        session.join("owner", Power.EMPIRE, true); session.exclude("owner"); session.openBattle("old")
        session.advance(Long.MAX_VALUE); session.restart()
        assertEquals(SessionSnapshot(2, rules.startSeconds, 0, 0, emptyMap(), 0, 0, null), session.snapshot())
        assertEquals(Admission.ACCEPTED, session.join("owner", Power.ALLIANCE, true))
    }
}
