package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationPlanRulesTest {
    private val facilities = setOf("A", "B", "C")

    @Test fun t1DurationIsThirtyGameDays() {
        assertEquals(2_592_000L, OperationPlanRules.DURATION_GAME_SECONDS)
    }

    @Test fun t2OccupationAllowsNoLocalOwnership() {
        for (owned in listOf(emptySet(), setOf("X"))) {
            assertTrue(OperationPlanRules.isOccupationTarget(facilities, owned))
        }
    }

    @Test fun t3OccupationRejectsAnyLocalOwnership() {
        assertFalse(OperationPlanRules.isOccupationTarget(facilities, setOf("A")))
    }

    @Test fun t4EmptyTargetIsIneligibleAndDefenseConditionIsLost() {
        for (owned in listOf(emptySet(), setOf("X"))) {
            assertFalse(OperationPlanRules.isOccupationTarget(emptySet(), owned))
            assertFalse(OperationPlanRules.isDefenseTarget(emptySet(), owned))
            assertTrue(OperationPlanRules.isDefenseConditionLost(emptySet(), owned))
        }
    }

    @Test fun t5DefenseRequiresLocalOwnership() {
        assertTrue(OperationPlanRules.isDefenseTarget(facilities, setOf("B")))
        assertFalse(OperationPlanRules.isDefenseTarget(facilities, emptySet()))
        assertFalse(OperationPlanRules.isDefenseTarget(facilities, setOf("X")))
    }

    @Test fun t6DefenseConditionLostIsAlwaysTheOppositeOfEligibility() {
        for (owned in listOf(emptySet(), setOf("A"), facilities, setOf("X"))) {
            assertEquals(!OperationPlanRules.isDefenseTarget(facilities, owned),
                OperationPlanRules.isDefenseConditionLost(facilities, owned))
        }
    }

    @Test fun t7EndTimeStartsAtIssuanceAndAcceptsLastSafeLong() {
        assertEquals(2_592_000L, OperationPlanRules.endGameSeconds(0))
        assertEquals(2_592_100L, OperationPlanRules.endGameSeconds(100))
        assertEquals(Long.MAX_VALUE,
            OperationPlanRules.endGameSeconds(Long.MAX_VALUE - OperationPlanRules.DURATION_GAME_SECONDS))
    }

    @Test fun t8NegativeTimeAndOverflowAreRejected() {
        assertFailsWith<IllegalArgumentException> { OperationPlanRules.endGameSeconds(-1) }
        assertFailsWith<ArithmeticException> { OperationPlanRules.endGameSeconds(Long.MAX_VALUE) }
        assertFailsWith<ArithmeticException> {
            OperationPlanRules.endGameSeconds(Long.MAX_VALUE - OperationPlanRules.DURATION_GAME_SECONDS + 1)
        }
    }

    @Test fun t9OccupationResultsUseOnlyTargetFacilities() {
        assertEquals(OperationOutcome.FULL_SUCCESS,
            OperationPlanRules.occupationOutcome(facilities, facilities + "X"))
        assertEquals(OperationOutcome.PARTIAL_SUCCESS,
            OperationPlanRules.occupationOutcome(facilities, setOf("A")))
        for (owned in listOf(emptySet(), setOf("X"))) {
            assertEquals(OperationOutcome.FAILURE, OperationPlanRules.occupationOutcome(facilities, owned))
        }
    }

    @Test fun t10OccupationResultRejectsEmptyFacilities() {
        assertFailsWith<IllegalArgumentException> {
            OperationPlanRules.occupationOutcome(emptySet(), emptySet())
        }
    }

    @Test fun t11DefenseFullSuccessIgnoresNewAcquisitions() {
        for (owned in listOf(setOf("A", "B"), facilities + "X")) {
            assertEquals(OperationOutcome.FULL_SUCCESS,
                OperationPlanRules.defenseOutcome(facilities, setOf("A", "B"), owned))
        }
    }

    @Test fun t12DefenseLossIsPartialEvenWithNewAcquisitions() {
        for (owned in listOf(setOf("A"), setOf("A", "C"), setOf("C", "X"))) {
            assertEquals(OperationOutcome.PARTIAL_SUCCESS,
                OperationPlanRules.defenseOutcome(facilities, setOf("A", "B"), owned))
        }
    }

    @Test fun t13DefenseConditionLossTakesPrecedenceOverResults() {
        for (owned in listOf(emptySet(), setOf("X"))) {
            assertFailsWith<IllegalArgumentException> {
                OperationPlanRules.defenseOutcome(facilities, setOf("A"), owned)
            }
        }
    }

    @Test fun t14DefenseResultRejectsInvalidBaselineAndEmptyFacilities() {
        for (defended in listOf(emptySet(), setOf("Z"), setOf("A", "Z"))) {
            assertFailsWith<IllegalArgumentException> {
                OperationPlanRules.defenseOutcome(facilities, defended, setOf("A"))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            OperationPlanRules.defenseOutcome(emptySet(), setOf("A"), setOf("A"))
        }
    }

    @Test fun t15EverySetArgumentRejectsBlankElementsBeforeShortCircuiting() {
        for (blank in listOf("", " ", "\t\n")) {
            val invalid = setOf("A", blank)
            for (target in listOf(facilities, emptySet())) {
                assertFailsWith<IllegalArgumentException> { OperationPlanRules.isOccupationTarget(target, invalid) }
                assertFailsWith<IllegalArgumentException> { OperationPlanRules.isDefenseTarget(target, invalid) }
                assertFailsWith<IllegalArgumentException> { OperationPlanRules.isDefenseConditionLost(target, invalid) }
                assertFailsWith<IllegalArgumentException> { OperationPlanRules.occupationOutcome(target, invalid) }
                assertFailsWith<IllegalArgumentException> {
                    OperationPlanRules.defenseOutcome(target, setOf("A"), invalid)
                }
                assertFailsWith<IllegalArgumentException> {
                    OperationPlanRules.defenseOutcome(target, invalid, setOf("A"))
                }
            }
            assertFailsWith<IllegalArgumentException> { OperationPlanRules.isOccupationTarget(invalid, emptySet()) }
            assertFailsWith<IllegalArgumentException> { OperationPlanRules.isDefenseTarget(invalid, setOf("A")) }
            assertFailsWith<IllegalArgumentException> { OperationPlanRules.isDefenseConditionLost(invalid, setOf("A")) }
            assertFailsWith<IllegalArgumentException> { OperationPlanRules.occupationOutcome(invalid, setOf("A")) }
            assertFailsWith<IllegalArgumentException> {
                OperationPlanRules.defenseOutcome(invalid, setOf("A"), setOf("A"))
            }
        }
    }

    @Test fun t16InputSetsAreNotModifiedOnSuccessOrFailure() {
        val target = facilities.toMutableSet()
        val defended = mutableSetOf("A", "B")
        val owned = mutableSetOf("A", "C", "X")
        val before = listOf(target.toSet(), defended.toSet(), owned.toSet())
        OperationPlanRules.isOccupationTarget(target, owned)
        OperationPlanRules.isDefenseTarget(target, owned)
        OperationPlanRules.isDefenseConditionLost(target, owned)
        OperationPlanRules.occupationOutcome(target, owned)
        OperationPlanRules.defenseOutcome(target, defended, owned)
        assertEquals(before, listOf(target, defended, owned))

        defended += "Z"
        val invalidBefore = defended.toSet()
        assertFailsWith<IllegalArgumentException> { OperationPlanRules.defenseOutcome(target, defended, owned) }
        assertEquals(invalidBefore, defended)
        assertEquals(before[0], target)
        assertEquals(before[2], owned)
    }
}
