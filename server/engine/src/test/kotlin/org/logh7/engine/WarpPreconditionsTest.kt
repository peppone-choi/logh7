package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WarpPreconditionsTest {
    @Test fun t1MinimumCruiseAllowsNonAdjacentNonStarTarget() {
        assertEquals(WarpDecision.ALLOWED, WarpPreconditions.evaluate(WarpContext(100, false, false)))
    }

    @Test fun t2CruiseBelowMinimumIsRejected() {
        assertEquals(WarpDecision.INSUFFICIENT_CRUISE, WarpPreconditions.evaluate(WarpContext(99, false, false)))
    }

    @Test fun t3ZeroCruiseIsRejectedEvenWhenAdjacent() {
        assertEquals(WarpDecision.INSUFFICIENT_CRUISE, WarpPreconditions.evaluate(WarpContext(0, false, true)))
    }

    @Test fun t4MaximumIntCruiseHasNoUpperLimit() {
        assertEquals(WarpDecision.ALLOWED, WarpPreconditions.evaluate(WarpContext(Int.MAX_VALUE, false, false)))
    }

    @Test fun t5MinimumCruiseAllowsAdjacentStarTarget() {
        assertEquals(WarpDecision.ALLOWED, WarpPreconditions.evaluate(WarpContext(100, true, true)))
    }

    @Test fun t6MinimumCruiseRejectsNonAdjacentStarTarget() {
        assertEquals(WarpDecision.STAR_SYSTEM_NOT_ADJACENT, WarpPreconditions.evaluate(WarpContext(100, true, false)))
    }

    @Test fun t7InsufficientCruiseTakesPriorityOverStarAdjacency() {
        assertEquals(WarpDecision.INSUFFICIENT_CRUISE, WarpPreconditions.evaluate(WarpContext(99, true, false)))
    }

    @Test fun t8CruiseAboveMinimumStillRequiresStarAdjacency() {
        assertEquals(WarpDecision.STAR_SYSTEM_NOT_ADJACENT, WarpPreconditions.evaluate(WarpContext(101, true, false)))
    }

    @Test fun t9NonStarTargetAllowsAdjacentOrigin() {
        assertEquals(WarpDecision.ALLOWED, WarpPreconditions.evaluate(WarpContext(150, false, true)))
    }

    @Test fun t10NegativeCruiseIsInvalid() {
        assertFailsWith<IllegalArgumentException> {
            WarpPreconditions.evaluate(WarpContext(-1, false, false))
        }
    }

    @Test fun t11MinimumIntCruiseIsInvalidEvenForAdjacentStarTarget() {
        assertFailsWith<IllegalArgumentException> {
            WarpPreconditions.evaluate(WarpContext(Int.MIN_VALUE, true, true))
        }
    }

    @Test fun t12MinimumCruiseIsOneHundred() {
        assertEquals(100, WarpPreconditions.MIN_CRUISE)
    }
}
