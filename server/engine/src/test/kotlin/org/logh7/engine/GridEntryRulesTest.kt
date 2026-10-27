package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GridEntryRulesTest {
    private val empire = Camp(Power.EMPIRE, false)
    private val alliance = Camp(Power.ALLIANCE, false)
    private val rebels = Camp(Power.EMPIRE, true)

    private fun presence(camp: Camp, units: Int = 10, nonSolo: Boolean = true,
        ship: Boolean = true, planet: Boolean = false) = CampPresence(camp, units, nonSolo, ship, planet)

    private fun grid(vararg presences: CampPresence, kind: GridKind = GridKind.SPACE,
        terrain: GridTerrain = GridTerrain.NONE, tactics: Boolean = false) =
        GridState(kind, terrain, tactics, presences.toList())

    private fun entry(units: Int = 1, camp: Camp = empire, solo: Boolean = false) = GridEntry(camp, units, solo)

    // SPEC §6과 저장된 초과 병력의 경계 조건. 추가 단언은 동일 계약의 경계/우선순위다.
    @Test fun emptySpaceAllowsThreeHundredUnits() {
        assertEquals(GridEntryVerdict.ALLOWED, GridEntryRules.evaluate(grid(), entry(300)))
    }

    @Test fun emptyGridRejectsThreeHundredAndOneUnits() {
        assertEquals(GridEntryVerdict.UNIT_LIMIT, GridEntryRules.evaluate(grid(), entry(301)))
    }

    @Test fun existingTwoHundredNinetyNineAllowsOne() {
        assertEquals(GridEntryVerdict.ALLOWED, GridEntryRules.evaluate(grid(presence(empire, 299)), entry()))
    }

    @Test fun fullCampRejectsOneMore() {
        assertEquals(GridEntryVerdict.UNIT_LIMIT, GridEntryRules.evaluate(grid(presence(empire, 300)), entry()))
    }

    @Test fun enteringForceCannotBePartiallyAdmitted() {
        assertEquals(GridEntryVerdict.UNIT_LIMIT, GridEntryRules.evaluate(grid(presence(empire, 299)), entry(2)))
    }

    @Test fun unitLimitIsPerCamp() {
        assertEquals(GridEntryVerdict.ALLOWED, GridEntryRules.evaluate(grid(presence(alliance, 300)), entry(300)))
    }

    @Test fun maximumIntegerEntryCannotOverflowCapacityCheck() {
        for (ownUnits in listOf(0, 1, 300)) {
            assertEquals(GridEntryVerdict.UNIT_LIMIT,
                GridEntryRules.evaluate(grid(presence(empire, ownUnits, false, false)), entry(Int.MAX_VALUE)))
        }
    }

    @Test fun insurgentsAreAThirdCampEvenWithTheSamePower() {
        assertEquals(GridEntryVerdict.CAMP_LIMIT,
            GridEntryRules.evaluate(grid(presence(empire), presence(alliance)), entry(camp = rebels)))
    }

    @Test fun existingCampCanEnterATwoCampGrid() {
        assertEquals(GridEntryVerdict.ALLOWED,
            GridEntryRules.evaluate(grid(presence(empire), presence(alliance)), entry(camp = alliance)))
    }

    @Test fun insurgentsCanEnterAsTheSecondCamp() {
        assertEquals(GridEntryVerdict.ALLOWED,
            GridEntryRules.evaluate(grid(presence(alliance)), entry(camp = rebels)))
    }

    @Test fun impassableGridRejectsEntry() {
        assertEquals(GridEntryVerdict.IMPASSABLE_GRID,
            GridEntryRules.evaluate(grid(kind = GridKind.IMPASSABLE), entry()))
    }

    @Test fun plasmaStormRejectsEntry() {
        assertEquals(GridEntryVerdict.TERRAIN_BLOCKED,
            GridEntryRules.evaluate(grid(terrain = GridTerrain.PLASMA_STORM), entry()))
    }

    @Test fun sargassoSpaceRejectsSoloShip() {
        assertEquals(GridEntryVerdict.TERRAIN_BLOCKED,
            GridEntryRules.evaluate(grid(terrain = GridTerrain.SARGASSO_SPACE), entry(solo = true)))
    }

    @Test fun verdictPriorityIsStaticConditionsThenCampThenCapacityThenSoloRule() {
        val occupied = grid(presence(empire), presence(alliance), kind = GridKind.IMPASSABLE,
            terrain = GridTerrain.PLASMA_STORM)
        val newcomer = entry(301, rebels, true)
        assertEquals(GridEntryVerdict.IMPASSABLE_GRID, GridEntryRules.evaluate(occupied, newcomer))
        val storm = occupied.copy(kind = GridKind.SPACE)
        assertEquals(GridEntryVerdict.TERRAIN_BLOCKED, GridEntryRules.evaluate(storm, newcomer))
        val space = storm.copy(terrain = GridTerrain.NONE)
        assertEquals(GridEntryVerdict.CAMP_LIMIT, GridEntryRules.evaluate(space, newcomer))
        assertEquals(GridEntryVerdict.UNIT_LIMIT, GridEntryRules.evaluate(space, entry(301, solo = true)))
        // 전술 예외는 독행함 제한만 해제하며 앞선 제한을 우회하지 않는다.
        val exempt = space.copy(inTactics = true)
        assertEquals(GridEntryVerdict.UNIT_LIMIT, GridEntryRules.evaluate(exempt, entry(301, solo = true)))
        assertEquals(GridEntryVerdict.CAMP_LIMIT, GridEntryRules.evaluate(exempt, newcomer))
        assertEquals(GridEntryVerdict.TERRAIN_BLOCKED,
            GridEntryRules.evaluate(exempt.copy(terrain = GridTerrain.SARGASSO_SPACE), entry(solo = true)))
    }

    @Test fun enemyNonSoloUnitBlocksSoloShipEvenOutsideStarSystem() {
        assertEquals(GridEntryVerdict.SOLO_SHIP_BLOCKED,
            GridEntryRules.evaluate(grid(presence(alliance, 1, true, false)), entry(solo = true)))
        assertEquals(GridEntryVerdict.SOLO_SHIP_BLOCKED,
            GridEntryRules.evaluate(grid(presence(rebels, 1, true, false)), entry(solo = true)))
    }

    @Test fun enemySoloShipsAloneDoNotBlockEntry() {
        assertEquals(GridEntryVerdict.ALLOWED,
            GridEntryRules.evaluate(grid(presence(alliance, 1, false, false)), entry(solo = true)))
    }

    @Test fun enemyPlanetInStarSystemBlocksSoloShipEvenWithoutUnits() {
        assertEquals(GridEntryVerdict.SOLO_SHIP_BLOCKED,
            GridEntryRules.evaluate(grid(presence(alliance, 0, false, false, true), kind = GridKind.STAR_SYSTEM),
                entry(solo = true)))
    }

    @Test fun enemyPlanetOutsideStarSystemDoesNotBlockSoloShip() {
        assertEquals(GridEntryVerdict.ALLOWED,
            GridEntryRules.evaluate(grid(presence(alliance, 0, false, false, true)), entry(solo = true)))
    }

    private fun contestedSystem(tactics: Boolean = true, ownShip: Boolean = true) =
        grid(presence(alliance, 5, true, true, true), presence(empire, 5, true, ownShip),
            kind = GridKind.STAR_SYSTEM, tactics = tactics)

    @Test fun friendlyShipDuringTacticsExemptsSoloShip() {
        assertEquals(GridEntryVerdict.ALLOWED, GridEntryRules.evaluate(contestedSystem(), entry(solo = true)))
    }

    @Test fun friendlyShipWithoutTacticsDoesNotExemptSoloShip() {
        assertEquals(GridEntryVerdict.SOLO_SHIP_BLOCKED,
            GridEntryRules.evaluate(contestedSystem(tactics = false), entry(solo = true)))
    }

    @Test fun tacticsWithoutFriendlyShipDoesNotExemptSoloShip() {
        assertEquals(GridEntryVerdict.SOLO_SHIP_BLOCKED,
            GridEntryRules.evaluate(contestedSystem(ownShip = false), entry(solo = true)))
        assertEquals(GridEntryVerdict.SOLO_SHIP_BLOCKED,
            GridEntryRules.evaluate(grid(presence(alliance, 5, true, true, true),
                kind = GridKind.STAR_SYSTEM, tactics = true), entry(solo = true)))
    }

    @Test fun nonSoloForceIgnoresSoloRestrictions() {
        assertEquals(GridEntryVerdict.ALLOWED,
            GridEntryRules.evaluate(grid(presence(alliance, 5, true, true, true), kind = GridKind.STAR_SYSTEM), entry()))
    }

    @Test fun ownPlanetDoesNotBlockSoloShip() {
        assertEquals(GridEntryVerdict.ALLOWED,
            GridEntryRules.evaluate(grid(presence(empire, 0, false, false, true), kind = GridKind.STAR_SYSTEM),
                entry(solo = true)))
    }

    @Test fun entryUnitsMustBePositiveBeforeAnyVerdict() {
        for (units in listOf(0, -1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { GridEntryRules.evaluate(grid(), entry(units)) }
            assertFailsWith<IllegalArgumentException> {
                GridEntryRules.evaluate(grid(kind = GridKind.IMPASSABLE), entry(units))
            }
        }
    }

    @Test fun presenceUnitsMustBeNonnegativeBeforeAnyVerdict() {
        for (units in listOf(-1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> {
                GridEntryRules.evaluate(grid(presence(empire, units)), entry())
            }
            assertFailsWith<IllegalArgumentException> {
                GridEntryRules.evaluate(grid(presence(alliance, units), kind = GridKind.IMPASSABLE), entry())
            }
        }
    }

    @Test fun duplicateCampsAreInvalidEvenWithDifferentPresenceFlags() {
        val duplicate = grid(presence(empire), presence(empire, 0, false, false))
        assertFailsWith<IllegalArgumentException> { GridEntryRules.evaluate(duplicate, entry()) }
        assertFailsWith<IllegalArgumentException> {
            GridEntryRules.evaluate(duplicate.copy(kind = GridKind.IMPASSABLE), entry())
        }
    }

    @Test fun overfullStoredCampRejectsEntryWithoutThrowing() {
        for (units in listOf(301, Int.MAX_VALUE)) {
            assertEquals(GridEntryVerdict.UNIT_LIMIT,
                GridEntryRules.evaluate(grid(presence(empire, units)), entry()))
        }
    }

    @Test fun moreThanTwoStoredCampsRejectsEntryWithoutThrowing() {
        val threeCamps = grid(presence(empire), presence(alliance), presence(rebels))
        assertEquals(GridEntryVerdict.CAMP_LIMIT, GridEntryRules.evaluate(threeCamps, entry()))
        assertEquals(GridEntryVerdict.IMPASSABLE_GRID,
            GridEntryRules.evaluate(threeCamps.copy(kind = GridKind.IMPASSABLE), entry()))
    }

    @Test fun repeatedEvaluationIsDeterministicAndDoesNotMutateInputs() {
        val presences = mutableListOf(presence(alliance, 5, true, true, true), presence(empire, 5))
        val original = presences.toList()
        val state = GridState(GridKind.STAR_SYSTEM, GridTerrain.NONE, true, presences)
        val entering = entry(solo = true)
        val originalState = state.copy(presences = original)
        val originalEntry = entering.copy()
        assertEquals(GridEntryVerdict.ALLOWED, GridEntryRules.evaluate(state, entering))
        assertEquals(GridEntryVerdict.ALLOWED, GridEntryRules.evaluate(state, entering))
        assertEquals(original, presences)
        assertEquals(originalState, state)
        assertEquals(originalEntry, entering)
        assertEquals(GridEntryRules.evaluate(state, entering),
            GridEntryRules.evaluate(state.copy(presences = presences.reversed()), entering))
    }
}
