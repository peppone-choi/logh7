package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MovementLayerRulesTest {
    private fun assertDecision(origin: MoveLocation, target: MoveLocation,
        layer: MovementLayer, verdict: MovementVerdict) {
        assertEquals(MovementDecision(layer, verdict), MovementLayerRules.evaluate(origin, target))
    }

    @Test fun t1DifferentSpotInSameFacility() {
        assertDecision(MoveLocation("G1", "P1", "F1", "S1"), MoveLocation("G1", "P1", "F1", "S2"),
            MovementLayer.INTRA_FACILITY, MovementVerdict.ALLOWED)
    }

    @Test fun t2SameSpot() {
        assertDecision(MoveLocation("G1", "P1", "F1", "S1"), MoveLocation("G1", "P1", "F1", "S1"),
            MovementLayer.INTRA_FACILITY, MovementVerdict.SAME_TARGET)
    }

    @Test fun t3SpotInDifferentFacility() {
        assertDecision(MoveLocation("G1", "P1", "F1", "S1"), MoveLocation("G1", "P1", "F2", "S9"),
            MovementLayer.INTRA_FACILITY, MovementVerdict.NOT_SAME_PARENT)
    }

    @Test fun t4FacilityWithoutOriginSpot() {
        assertDecision(MoveLocation("G1", "P1", "F1"), MoveLocation("G1", "P1", "F1", "S2"),
            MovementLayer.INTRA_FACILITY, MovementVerdict.UNDETERMINED)
    }

    @Test fun t5SpotToDifferentFacility() {
        assertDecision(MoveLocation("G1", "P1", "F1", "S1"), MoveLocation("G1", "P1", "F2"),
            MovementLayer.INTER_FACILITY, MovementVerdict.ALLOWED)
    }

    @Test fun t6FacilityToDifferentFacility() {
        assertDecision(MoveLocation("G1", "P1", "F1"), MoveLocation("G1", "P1", "F2"),
            MovementLayer.INTER_FACILITY, MovementVerdict.ALLOWED)
    }

    @Test fun t7SpotToContainingFacility() {
        assertDecision(MoveLocation("G1", "P1", "F1", "S1"), MoveLocation("G1", "P1", "F1"),
            MovementLayer.INTER_FACILITY, MovementVerdict.SAME_TARGET)
    }

    @Test fun t8SameFacilityIdOnDifferentPlanet() {
        assertDecision(MoveLocation("G1", "P1", "F1", "S1"), MoveLocation("G1", "P2", "F1"),
            MovementLayer.INTER_FACILITY, MovementVerdict.NOT_SAME_PARENT)
    }

    @Test fun t9PlanetWithoutOriginFacility() {
        assertDecision(MoveLocation("G1", "P1"), MoveLocation("G1", "P1", "F2"),
            MovementLayer.INTER_FACILITY, MovementVerdict.UNDETERMINED)
    }

    @Test fun t10GridWithoutOriginPlanet() {
        assertDecision(MoveLocation("G1"), MoveLocation("G1", "P1", "F1"),
            MovementLayer.INTER_FACILITY, MovementVerdict.NOT_SAME_PARENT)
    }

    @Test fun t11DifferentPlanetInSameGrid() {
        assertDecision(MoveLocation("G1", "P1"), MoveLocation("G1", "P2"),
            MovementLayer.INTRA_SYSTEM, MovementVerdict.ALLOWED)
    }

    @Test fun t12SamePlanet() {
        assertDecision(MoveLocation("G1", "P1"), MoveLocation("G1", "P1"),
            MovementLayer.INTRA_SYSTEM, MovementVerdict.SAME_TARGET)
    }

    @Test fun t13PlanetInDifferentGrid() {
        assertDecision(MoveLocation("G1", "P1"), MoveLocation("G2", "P2"),
            MovementLayer.INTRA_SYSTEM, MovementVerdict.NOT_SAME_PARENT)
    }

    @Test fun t14GridSpaceToPlanet() {
        assertDecision(MoveLocation("G1"), MoveLocation("G1", "P1"),
            MovementLayer.INTRA_SYSTEM, MovementVerdict.UNDETERMINED)
    }

    @Test fun t15SpotToDifferentPlanet() {
        assertDecision(MoveLocation("G1", "P1", "F1", "S1"), MoveLocation("G1", "P2"),
            MovementLayer.INTRA_SYSTEM, MovementVerdict.UNDETERMINED)
    }

    @Test fun t16DifferentGrid() {
        assertDecision(MoveLocation("G1"), MoveLocation("G2"),
            MovementLayer.GRID_WARP, MovementVerdict.ALLOWED)
    }

    @Test fun t17SameGrid() {
        assertDecision(MoveLocation("G1"), MoveLocation("G1"),
            MovementLayer.GRID_WARP, MovementVerdict.SAME_TARGET)
    }

    @Test fun t18PlanetToDifferentGrid() {
        assertDecision(MoveLocation("G1", "P1"), MoveLocation("G2"),
            MovementLayer.GRID_WARP, MovementVerdict.UNDETERMINED)
    }

    @Test fun t19SpotToContainingGrid() {
        assertDecision(MoveLocation("G1", "P1", "F1", "S1"), MoveLocation("G1"),
            MovementLayer.GRID_WARP, MovementVerdict.SAME_TARGET)
    }

    private val skippedLevels = listOf(
        MoveLocation("G1", facility = "F1"),
        MoveLocation("G1", "P1", spot = "S1"),
        MoveLocation("G1", spot = "S1"),
        MoveLocation("G1", facility = "F1", spot = "S1"))

    @Test fun t20RejectSkippedOriginLevelsBeforeSameTarget() {
        skippedLevels.forEach { origin ->
            assertFailsWith<IllegalArgumentException> {
                MovementLayerRules.evaluate(origin, MoveLocation("G1"))
            }
        }
    }

    @Test fun t21RejectSkippedTargetLevelsBeforeParentComparison() {
        skippedLevels.forEach { target ->
            assertFailsWith<IllegalArgumentException> {
                MovementLayerRules.evaluate(MoveLocation("G2"), target)
            }
        }
    }

    @Test fun t22RejectEveryBlankIdInOriginAndTarget() {
        val valid = MoveLocation("G1", "P1", "F1", "S1")
        listOf("", " ", "\t\n").forEach { blank ->
            listOf(valid.copy(grid = blank), valid.copy(planetOrFortress = blank),
                valid.copy(facility = blank), valid.copy(spot = blank)).forEach { invalid ->
                assertFailsWith<IllegalArgumentException> { MovementLayerRules.evaluate(invalid, valid) }
                assertFailsWith<IllegalArgumentException> { MovementLayerRules.evaluate(valid, invalid) }
                assertFailsWith<IllegalArgumentException> { MovementLayerRules.evaluate(invalid, invalid) }
            }
        }
    }

    @Test fun t23DeterministicWithoutMutatingInputs() {
        val origin = MoveLocation("G1", "P1", "F1", "S1")
        val target = MoveLocation("G1", "P1", "F1", "S2")
        val beforeOrigin = origin.copy()
        val beforeTarget = target.copy()
        val first = MovementLayerRules.evaluate(origin, target)
        assertEquals(MovementDecision(MovementLayer.INTRA_FACILITY, MovementVerdict.ALLOWED), first)
        assertEquals(first, MovementLayerRules.evaluate(origin, target))
        assertEquals(beforeOrigin, origin)
        assertEquals(beforeTarget, target)
    }

    @Test fun repeatedLeafIdsNeverHideDifferentAncestors() {
        val origin = MoveLocation("G1", "P1", "F1", "S1")
        listOf(MoveLocation("G2", "P1", "F1", "S1"), MoveLocation("G1", "P2", "F1", "S1"),
            MoveLocation("G1", "P1", "F2", "S1")).forEach { target ->
            assertDecision(origin, target, MovementLayer.INTRA_FACILITY, MovementVerdict.NOT_SAME_PARENT)
        }
        assertDecision(origin, MoveLocation("G2", "P1", "F1"),
            MovementLayer.INTER_FACILITY, MovementVerdict.NOT_SAME_PARENT)
        assertDecision(origin, MoveLocation("G2", "P1"),
            MovementLayer.INTRA_SYSTEM, MovementVerdict.NOT_SAME_PARENT)
    }

    @Test fun missingAncestorsAndDeeperOriginsFollowTargetDepth() {
        assertDecision(MoveLocation("G1", "P1"), MoveLocation("G1", "P1", "F1", "S1"),
            MovementLayer.INTRA_FACILITY, MovementVerdict.NOT_SAME_PARENT)
        assertDecision(MoveLocation("G1", "P1", "F1"), MoveLocation("G1", "P2"),
            MovementLayer.INTRA_SYSTEM, MovementVerdict.UNDETERMINED)
        assertDecision(MoveLocation("G1", "P1", "F1"), MoveLocation("G1", "P1"),
            MovementLayer.INTRA_SYSTEM, MovementVerdict.SAME_TARGET)
        assertDecision(MoveLocation("G1", "P1", "F1", "S1"), MoveLocation("G2"),
            MovementLayer.GRID_WARP, MovementVerdict.UNDETERMINED)
    }

    @Test fun opaqueIdsAreNotTrimmedOrCaseFolded() {
        assertDecision(MoveLocation("G1", "P1", "F1", "S1"), MoveLocation("G1", "P1", "F1", " S1 "),
            MovementLayer.INTRA_FACILITY, MovementVerdict.ALLOWED)
        assertDecision(MoveLocation("G1"), MoveLocation("g1"),
            MovementLayer.GRID_WARP, MovementVerdict.ALLOWED)
    }
}
