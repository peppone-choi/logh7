package org.logh7.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroundBattleRulesTest {
    @Test fun manualW51_normalPlanetAllowsAllThreeKinds() {
        assertEquals(GroundUnitKind.entries.toSet(), GroundBattleRules.eligibleKinds(GroundSite.NORMAL_PLANET))
    }

    @Test fun manualW51_specialPlanetAllowsGrenadiersAndMarines() {
        assertEquals(setOf(GroundUnitKind.ARMORED_GRENADIER, GroundUnitKind.LIGHT_MARINE),
            GroundBattleRules.eligibleKinds(GroundSite.SPECIAL_PLANET))
    }

    @Test fun manualW51_fortressAllowsGrenadiersAndMarines() {
        assertEquals(setOf(GroundUnitKind.ARMORED_GRENADIER, GroundUnitKind.LIGHT_MARINE),
            GroundBattleRules.eligibleKinds(GroundSite.FORTRESS))
    }

    @Test fun manualW51_participationMatchesEverySiteAndKind() {
        for (site in GroundSite.entries) for (kind in GroundUnitKind.entries) {
            assertEquals(site == GroundSite.NORMAL_PLANET || kind != GroundUnitKind.ARMORED_INFANTRY,
                GroundBattleRules.canParticipate(site, kind), "$site/$kind")
        }
    }

    @Test fun manualW51_returnedSetsCannotChangeLaterResults() {
        for (site in GroundSite.entries) {
            val expected = GroundBattleRules.eligibleKinds(site).toSet()
            val returned = GroundBattleRules.eligibleKinds(site)
            try {
                (returned as MutableSet<GroundUnitKind>).clear()
            } catch (_: UnsupportedOperationException) {
                // An unmodifiable set may reject mutation.
            } catch (_: ClassCastException) {
                // A read-only implementation may reject the mutable cast.
            }
            assertEquals(expected, GroundBattleRules.eligibleKinds(site))
            for (kind in GroundUnitKind.entries) {
                assertEquals(kind in expected, GroundBattleRules.canParticipate(site, kind))
            }
        }
    }

    @Test fun manualW51_anchoredShipDescends() {
        assertEquals(GroundLandingVerdict.DESCENT, GroundBattleRules.landingVerdict(ShipBerth.ANCHORED, 1, 0))
    }

    @Test fun manualW51_stationedShipSorties() {
        assertEquals(GroundLandingVerdict.SORTIE, GroundBattleRules.landingVerdict(ShipBerth.STATIONED, 1, 0))
    }

    @Test fun manualW51_unberthedShipCannotLand() {
        assertEquals(GroundLandingVerdict.NOT_BERTHED, GroundBattleRules.landingVerdict(ShipBerth.NONE, 1, 0))
    }

    @Test fun manualW51_noLoadedUnitsCannotLand() {
        assertEquals(GroundLandingVerdict.NO_GROUND_UNITS, GroundBattleRules.landingVerdict(ShipBerth.ANCHORED, 0, 0))
    }

    @Test fun manualW51_capacityBoundaryIsInclusiveForBothBerths() {
        assertEquals(30, GroundBattleRules.BOX_CAPACITY_PER_FACTION)
        for ((berth, allowed) in listOf(ShipBerth.ANCHORED to GroundLandingVerdict.DESCENT,
            ShipBerth.STATIONED to GroundLandingVerdict.SORTIE)) {
            assertEquals(allowed, GroundBattleRules.landingVerdict(berth, 1, 29))
            assertEquals(allowed, GroundBattleRules.landingVerdict(berth, 30, 0))
            for ((landing, own) in listOf(2 to 29, 31 to 0, 1 to 30)) {
                assertEquals(GroundLandingVerdict.BOX_CAPACITY_EXCEEDED,
                    GroundBattleRules.landingVerdict(berth, landing, own))
            }
        }
    }

    @Test fun manualW51_designVerdictPrecedence() {
        assertEquals(GroundLandingVerdict.NOT_BERTHED, GroundBattleRules.landingVerdict(ShipBerth.NONE, 0, 30))
        assertEquals(GroundLandingVerdict.NOT_BERTHED, GroundBattleRules.landingVerdict(ShipBerth.NONE, 31, 30))
        assertEquals(GroundLandingVerdict.NO_GROUND_UNITS, GroundBattleRules.landingVerdict(ShipBerth.ANCHORED, 0, 30))
        assertEquals(GroundLandingVerdict.NO_GROUND_UNITS, GroundBattleRules.landingVerdict(ShipBerth.STATIONED, 0, 30))
    }

    @Test fun manualW51_enemyPresenceStartsBattle() {
        assertFalse(GroundBattleRules.startsGroundBattle(0))
        assertTrue(GroundBattleRules.startsGroundBattle(1))
        assertTrue(GroundBattleRules.startsGroundBattle(30))
    }

    @Test fun manualW51_occupationRequiresOwnPresenceAndNoEnemyIncludingP06Regression() {
        assertTrue(GroundBattleRules.isOccupationComplete(1, 0))
        assertTrue(GroundBattleRules.isOccupationComplete(30, 0))
        assertFalse(GroundBattleRules.isOccupationComplete(1, 1))
        assertFalse(GroundBattleRules.isOccupationComplete(0, 0))
        assertFalse(GroundBattleRules.isOccupationComplete(0, 1))
        assertFalse(GroundBattleRules.isOccupationComplete(30, 1))
        assertFalse(GroundBattleRules.isOccupationComplete(30, 30))
    }

    @Test fun manualW51_invalidLandingCountsFailBeforeAnyVerdict() {
        for (berth in ShipBerth.entries) {
            for (landing in listOf(Int.MIN_VALUE, -1)) {
                assertFailsWith<IllegalArgumentException> { GroundBattleRules.landingVerdict(berth, landing, 0) }
            }
            for (own in listOf(Int.MIN_VALUE, -1, 31, Int.MAX_VALUE)) {
                for (landing in listOf(0, 1)) {
                    assertFailsWith<IllegalArgumentException> { GroundBattleRules.landingVerdict(berth, landing, own) }
                }
            }
        }
    }

    @Test fun manualW51_invalidBattleAndOccupationCountsFail() {
        for (invalid in listOf(Int.MIN_VALUE, -1, 31, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { GroundBattleRules.startsGroundBattle(invalid) }
            for (valid in listOf(0, 1, 30)) {
                assertFailsWith<IllegalArgumentException> { GroundBattleRules.isOccupationComplete(invalid, valid) }
                assertFailsWith<IllegalArgumentException> { GroundBattleRules.isOccupationComplete(valid, invalid) }
            }
        }
    }

    @Test fun manualW51_maxLandingCountExceedsCapacityWithoutOverflow() {
        for (berth in listOf(ShipBerth.ANCHORED, ShipBerth.STATIONED)) {
            for (own in listOf(0, 29, 30)) {
                assertEquals(GroundLandingVerdict.BOX_CAPACITY_EXCEEDED,
                    GroundBattleRules.landingVerdict(berth, Int.MAX_VALUE, own))
            }
        }
    }
}
