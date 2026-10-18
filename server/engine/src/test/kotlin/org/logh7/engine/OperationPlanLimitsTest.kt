package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class OperationPlanLimitsTest {
    @Test fun manualW39_emptyCardAllowsFirstUnit() {
        assertEquals(OperationPlanVerdict.ALLOWED, OperationPlanLimits.canPlan(emptySet(), "A", 1, 0, 1))
    }

    @Test fun manualW39_sameCardCannotRepeatTarget() {
        assertEquals(OperationPlanVerdict.DUPLICATE_TARGET_FOR_CARD,
            OperationPlanLimits.canPlan(setOf("A"), "A", 1, 0, 10))
    }

    @Test fun manualW39_otherTargetsDoNotBlockPlanning() {
        assertEquals(OperationPlanVerdict.ALLOWED, OperationPlanLimits.canPlan(setOf("B"), "A", 1, 0, 10))
    }

    @Test fun manualW39_totalEqualToEffectiveIsAllowed() {
        assertEquals(OperationPlanVerdict.ALLOWED, OperationPlanLimits.canPlan(emptySet(), "A", 3, 7, 10))
    }

    @Test fun manualW39_totalOneAboveEffectiveIsRejected() {
        assertEquals(OperationPlanVerdict.UNIT_TOTAL_EXCEEDED,
            OperationPlanLimits.canPlan(emptySet(), "A", 4, 7, 10))
    }

    @Test fun manualW39_zeroEffectiveUnitsCannotSupportNewPlan() {
        assertEquals(OperationPlanVerdict.UNIT_TOTAL_EXCEEDED,
            OperationPlanLimits.canPlan(emptySet(), "A", 1, 0, 0))
    }

    @Test fun manualW39_equalExistingTotalIsNotShortage() {
        assertEquals(OperationPlanVerdict.UNIT_TOTAL_EXCEEDED,
            OperationPlanLimits.canPlan(emptySet(), "A", 1, 10, 10))
    }

    @Test fun manualW39_existingShortageBlocksNewPlan() {
        assertEquals(OperationPlanVerdict.EXISTING_PLANS_UNSUSTAINABLE,
            OperationPlanLimits.canPlan(emptySet(), "A", 1, 11, 10))
    }

    @Test fun manualW39_approvedShortagePriorityPrecedesDuplicate() {
        assertEquals(OperationPlanVerdict.EXISTING_PLANS_UNSUSTAINABLE,
            OperationPlanLimits.canPlan(setOf("A"), "A", 1, 11, 10))
    }

    @Test fun manualW39_approvedDuplicatePriorityPrecedesTotalExcess() {
        assertEquals(OperationPlanVerdict.DUPLICATE_TARGET_FOR_CARD,
            OperationPlanLimits.canPlan(setOf("A"), "A", 1, 10, 10))
    }

    @Test fun manualW39_shortageBoundaryUsesStrictExcess() {
        assertFalse(OperationPlanLimits.isUnsustainable(10, 10))
        assertTrue(OperationPlanLimits.isUnsustainable(11, 10))
        assertFalse(OperationPlanLimits.isUnsustainable(0, 0))
    }

    @Test fun manualW39_assignmentIncludesActivationInstant() {
        assertFalse(OperationPlanLimits.mayAssignForces(9, 10))
        assertTrue(OperationPlanLimits.mayAssignForces(10, 10))
        assertTrue(OperationPlanLimits.mayAssignForces(11, 10))
        assertTrue(OperationPlanLimits.mayAssignForces(0, 0))
        assertTrue(OperationPlanLimits.mayAssignForces(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test fun manualW39_invalidInputsAreRejectedBeforeVerdicts() {
        for (blank in listOf("", " \t\n")) {
            assertFailsWith<IllegalArgumentException> { OperationPlanLimits.canPlan(emptySet(), blank, 1, 0, 1) }
            assertFailsWith<IllegalArgumentException> { OperationPlanLimits.canPlan(setOf("A", blank), "A", 1, 2, 1) }
        }
        for (requested in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> { OperationPlanLimits.canPlan(setOf("A"), "A", requested, 2, 1) }
        }
        assertFailsWith<IllegalArgumentException> { OperationPlanLimits.canPlan(setOf("A"), "A", 1, -1, 1) }
        assertFailsWith<IllegalArgumentException> { OperationPlanLimits.canPlan(setOf("A"), "A", 1, 0, -1) }
        assertFailsWith<IllegalArgumentException> { OperationPlanLimits.mayAssignForces(-1, 0) }
        assertFailsWith<IllegalArgumentException> { OperationPlanLimits.mayAssignForces(0, -1) }
    }

    @Test fun manualW39_totalOverflowIsNotWrapped() {
        assertFailsWith<ArithmeticException> {
            OperationPlanLimits.canPlan(emptySet(), "A", 1, Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    @Test fun manualW39_inputTargetsRemainUnchangedForEveryVerdict() {
        val targets = mutableSetOf("A", "B")
        val before = targets.toSet()
        for ((target, committed, effective) in listOf(
            Triple("C", 0, 10), Triple("A", 0, 10), Triple("C", 10, 10), Triple("C", 11, 10),
        )) {
            OperationPlanLimits.canPlan(targets, target, 1, committed, effective)
            assertEquals(before, targets)
            assertEquals(before.size, targets.size)
        }
    }

    @Test fun manualW39_shortageHelperRejectsNegativeTotalsIndependently() {
        assertFailsWith<IllegalArgumentException> { OperationPlanLimits.isUnsustainable(-1, 0) }
        assertFailsWith<IllegalArgumentException> { OperationPlanLimits.isUnsustainable(0, -1) }
        assertFailsWith<IllegalArgumentException> { OperationPlanLimits.isUnsustainable(-1, -1) }
    }
}
