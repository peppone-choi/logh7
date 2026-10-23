package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RankRulesTest {
    @Test fun t1CapacitiesMatchAllTwelveManualCells() {
        val expected = mapOf(
            LimitedRank.MARSHAL to (5 to 5),
            LimitedRank.SENIOR_ADMIRAL to (5 to null),
            LimitedRank.ADMIRAL to (10 to 10),
            LimitedRank.VICE_ADMIRAL to (20 to 20),
            LimitedRank.REAR_ADMIRAL to (40 to 40),
            LimitedRank.COMMODORE to (80 to 80),
        )
        assertEquals(LimitedRank.entries.toSet(), expected.keys)
        expected.forEach { (rank, capacities) ->
            assertEquals(capacities.first, RankRules.capacity(Power.EMPIRE, rank), "EMPIRE $rank")
            assertEquals(capacities.second, RankRules.capacity(Power.ALLIANCE, rank), "ALLIANCE $rank")
        }
    }

    @Test fun t2EmptyEmpireMarshalHasRoom() {
        assertEquals(RankAdmission.ADMISSIBLE, RankRules.admission(Power.EMPIRE, LimitedRank.MARSHAL, 0))
    }

    @Test fun t3EmpireMarshalHasOnePlaceLeft() {
        assertEquals(RankAdmission.ADMISSIBLE, RankRules.admission(Power.EMPIRE, LimitedRank.MARSHAL, 4))
    }

    @Test fun t4EmpireMarshalQuotaIsFull() {
        assertEquals(RankAdmission.QUOTA_FULL, RankRules.admission(Power.EMPIRE, LimitedRank.MARSHAL, 5))
    }

    @Test fun t5AllianceCommodoreBoundary() {
        assertEquals(RankAdmission.ADMISSIBLE, RankRules.admission(Power.ALLIANCE, LimitedRank.COMMODORE, 79))
        assertEquals(RankAdmission.QUOTA_FULL, RankRules.admission(Power.ALLIANCE, LimitedRank.COMMODORE, 80))
    }

    @Test fun t6EmpireSeniorAdmiralQuotaIsFull() {
        assertEquals(RankAdmission.QUOTA_FULL, RankRules.admission(Power.EMPIRE, LimitedRank.SENIOR_ADMIRAL, 5))
    }

    @Test fun t7AllianceHasNoSeniorAdmiral() {
        assertEquals(RankAdmission.RANK_NOT_IN_POWER, RankRules.admission(Power.ALLIANCE, LimitedRank.SENIOR_ADMIRAL, 0))
    }

    @Test fun t8AbsentRankCannotHaveHolders() {
        listOf(-1, 1, Int.MAX_VALUE).forEach { holders ->
            assertFailsWith<IllegalArgumentException> {
                RankRules.admission(Power.ALLIANCE, LimitedRank.SENIOR_ADMIRAL, holders)
            }
        }
    }

    @Test fun t9InvalidHolderCountsAreRejected() {
        listOf(-1, 11, Int.MIN_VALUE, Int.MAX_VALUE).forEach { holders ->
            assertFailsWith<IllegalArgumentException> {
                RankRules.admission(Power.EMPIRE, LimitedRank.ADMIRAL, holders)
            }
        }
    }

    @Test fun t10RankChangeConstantsMatchManual() {
        assertEquals(0, RankRules.PROMOTED_MERIT)
        assertEquals(100, RankRules.DEMOTED_MERIT)
        assertEquals(setOf("個人", "艦長", "封土"), RankRules.RETAINED_CARD_NAMES)
    }

    @Test fun t11OnlyRetainedCardsSurviveRankChange() {
        listOf("個人", "艦長", "封土").forEach { assertTrue(RankRules.retainsCardOnRankChange(it), it) }
    }

    @Test fun t12CardNamesRequireExactMatch() {
        listOf("帝国軍最高司令官", "艦長カード", " 艦長", "艦長 ").forEach {
            assertFalse(RankRules.retainsCardOnRankChange(it), it)
        }
    }

    @Test fun t13BlankCardNamesAreRejected() {
        listOf("", "  ", "\t\n").forEach {
            assertFailsWith<IllegalArgumentException> { RankRules.retainsCardOnRankChange(it) }
        }
    }

    @Test fun t14SourceNamesPreserveManualOrder() {
        assertEquals(listOf("元帥", "上級大将", "大将", "中将", "少将", "准将"), LimitedRank.entries.map { it.sourceName })
    }
}
