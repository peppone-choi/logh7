package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TacticalMissionRulesTest {
    @Test fun t1ConstantsAndMissionKinds() {
        assertEquals(1_440L, TacticalMissionRules.CHECK_INTERVAL_GAME_SECONDS)
        assertEquals(14_400L, TacticalMissionRules.WITHDRAWAL_LIMIT_GAME_SECONDS)
        assertEquals(12_000_000.0, TacticalMissionRules.RECONNAISSANCE_RANGE_KM)
        assertEquals(3L, TacticalMissionRules.INTERCEPTION_MERIT_MULTIPLIER)
        assertEquals(setOf("DEFENSE", "OCCUPATION", "INTERCEPTION", "SKIRMISH", "RECONNAISSANCE", "WITHDRAWAL"),
            TacticalMission.entries.map { it.name }.toSet())
    }

    @Test fun t2TargetDeterminesMission() {
        assertEquals(TacticalMission.DEFENSE, TacticalMissionRules.missionFor(MissionTarget.OWN_PLANET_OR_FORTRESS))
        assertEquals(TacticalMission.OCCUPATION, TacticalMissionRules.missionFor(MissionTarget.OTHER_PLANET_OR_FORTRESS))
        assertEquals(TacticalMission.INTERCEPTION, TacticalMissionRules.missionFor(MissionTarget.OTHER_SQUADRON))
    }

    @Test fun t3DefaultMissionDependsOnGarrison() {
        assertEquals(TacticalMission.DEFENSE, TacticalMissionRules.defaultMission(true))
        assertEquals(TacticalMission.SKIRMISH, TacticalMissionRules.defaultMission(false))
    }

    @Test fun t4IntervalsUseExplicitOriginAndIncludeBoundary() {
        assertEquals(0L, TacticalMissionRules.completedCheckIntervals(100, 100))
        assertEquals(0L, TacticalMissionRules.completedCheckIntervals(100, 1_539))
        assertEquals(1L, TacticalMissionRules.completedCheckIntervals(100, 1_540))
    }

    @Test fun t5IntervalsPreserveCatchup() {
        assertEquals(1L, TacticalMissionRules.completedCheckIntervals(0, 2_879))
        assertEquals(2L, TacticalMissionRules.completedCheckIntervals(0, 2_880))
        assertEquals(10L, TacticalMissionRules.completedCheckIntervals(0, 14_400))
    }

    @Test fun t6IntervalsHandleLargestTimestamp() {
        assertEquals(Long.MAX_VALUE / 1_440, TacticalMissionRules.completedCheckIntervals(0, Long.MAX_VALUE))
        assertEquals(0L, TacticalMissionRules.completedCheckIntervals(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test fun t7IntervalsRejectNegativeTimesAndReversal() {
        assertFailsWith<IllegalArgumentException> { TacticalMissionRules.completedCheckIntervals(2, 1) }
        assertFailsWith<IllegalArgumentException> { TacticalMissionRules.completedCheckIntervals(-1, 0) }
        assertFailsWith<IllegalArgumentException> { TacticalMissionRules.completedCheckIntervals(0, -1) }
        assertFailsWith<IllegalArgumentException> { TacticalMissionRules.completedCheckIntervals(-2, -1) }
    }

    @Test fun t8ReconnaissanceIncludesZeroAndBoundary() {
        assertTrue(TacticalMissionRules.reconnaissanceInRange(0.0))
        assertTrue(TacticalMissionRules.reconnaissanceInRange(12_000_000.0))
    }

    @Test fun t9ReconnaissanceRejectsDistancesBeyondBoundary() {
        assertFalse(TacticalMissionRules.reconnaissanceInRange(Math.nextUp(12_000_000.0)))
        assertFalse(TacticalMissionRules.reconnaissanceInRange(12_000_001.0))
    }

    @Test fun t10ReconnaissanceRejectsInvalidDistances() {
        for (distance in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.5)) {
            assertFailsWith<IllegalArgumentException> { TacticalMissionRules.reconnaissanceInRange(distance) }
        }
    }

    @Test fun t11WithdrawalIncludesImmediateAndDeadline() {
        assertTrue(TacticalMissionRules.withdrawalInTime(1_000, 1_000))
        assertTrue(TacticalMissionRules.withdrawalInTime(1_000, 15_400))
        assertTrue(TacticalMissionRules.withdrawalInTime(Long.MAX_VALUE - 14_400, Long.MAX_VALUE))
    }

    @Test fun t12WithdrawalBeyondDeadlineIsLate() {
        assertFalse(TacticalMissionRules.withdrawalInTime(1_000, 15_401))
        assertFalse(TacticalMissionRules.withdrawalInTime(0, Long.MAX_VALUE))
    }

    @Test fun t13WithdrawalRejectsNegativeTimesAndReversal() {
        assertFailsWith<IllegalArgumentException> { TacticalMissionRules.withdrawalInTime(2, 1) }
        assertFailsWith<IllegalArgumentException> { TacticalMissionRules.withdrawalInTime(-1, 0) }
        assertFailsWith<IllegalArgumentException> { TacticalMissionRules.withdrawalInTime(0, -1) }
        assertFailsWith<IllegalArgumentException> { TacticalMissionRules.withdrawalInTime(-2, -1) }
    }

    @Test fun t14InterceptionMultipliesOnlyDesignatedUnitMerit() {
        assertEquals(300L, TacticalMissionRules.interceptionMerit(100, true))
        assertEquals(100L, TacticalMissionRules.interceptionMerit(100, false))
        assertEquals(0L, TacticalMissionRules.interceptionMerit(0, true))
    }

    @Test fun t15InterceptionDetectsOverflowWithoutLimitingOrdinaryMerit() {
        val largestSafeBase = Long.MAX_VALUE / 3
        assertEquals(largestSafeBase * 3, TacticalMissionRules.interceptionMerit(largestSafeBase, true))
        assertFailsWith<ArithmeticException> { TacticalMissionRules.interceptionMerit(largestSafeBase + 1, true) }
        assertEquals(Long.MAX_VALUE, TacticalMissionRules.interceptionMerit(Long.MAX_VALUE, false))
    }

    @Test fun t16InterceptionRejectsNegativeMeritRegardlessOfTarget() {
        assertFailsWith<IllegalArgumentException> { TacticalMissionRules.interceptionMerit(-1, true) }
        assertFailsWith<IllegalArgumentException> { TacticalMissionRules.interceptionMerit(-1, false) }
    }
}
