package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class RankAuthorityRulesTest {
    @Test fun t1_empireMarshalRequiresEmperor() {
        assertEquals(RankAuthority.EMPEROR, RankAuthorityRules.authority(Power.EMPIRE, RankChangeTier.MARSHAL))
    }

    @Test fun t2_empireAdmiralsRequireMilitaryAffairsSecretary() {
        assertEquals(RankAuthority.MILITARY_AFFAIRS_SECRETARY,
            RankAuthorityRules.authority(Power.EMPIRE, RankChangeTier.SENIOR_ADMIRAL_TO_COMMODORE))
    }

    @Test fun t3_empireCaptainAndBelowRequiresMilitaryPersonnelBureauChief() {
        assertEquals(RankAuthority.MILITARY_PERSONNEL_BUREAU_CHIEF,
            RankAuthorityRules.authority(Power.EMPIRE, RankChangeTier.CAPTAIN_AND_BELOW))
    }

    @Test fun t4_allianceMarshalAuthorityIsUnresolved() {
        assertNull(RankAuthorityRules.authority(Power.ALLIANCE, RankChangeTier.MARSHAL))
    }

    @Test fun t5_allianceAdmiralsRequireDefenseCommitteeChair() {
        assertEquals(RankAuthority.DEFENSE_COMMITTEE_CHAIR,
            RankAuthorityRules.authority(Power.ALLIANCE, RankChangeTier.SENIOR_ADMIRAL_TO_COMMODORE))
    }

    @Test fun t6_allianceCaptainAndBelowRequiresDefensePersonnelBureauChief() {
        assertEquals(RankAuthority.DEFENSE_PERSONNEL_BUREAU_CHIEF,
            RankAuthorityRules.authority(Power.ALLIANCE, RankChangeTier.CAPTAIN_AND_BELOW))
    }

    @Test fun t7_allSixCombinationsPreservePowerWhenAuthorityIsKnown() {
        val combinations = Power.entries.flatMap { power -> RankChangeTier.entries.map { power to it } }
        assertEquals(setOf(
            Power.EMPIRE to RankChangeTier.MARSHAL,
            Power.EMPIRE to RankChangeTier.SENIOR_ADMIRAL_TO_COMMODORE,
            Power.EMPIRE to RankChangeTier.CAPTAIN_AND_BELOW,
            Power.ALLIANCE to RankChangeTier.MARSHAL,
            Power.ALLIANCE to RankChangeTier.SENIOR_ADMIRAL_TO_COMMODORE,
            Power.ALLIANCE to RankChangeTier.CAPTAIN_AND_BELOW,
        ), combinations.toSet())
        assertEquals(6, combinations.size)
        var known = 0
        combinations.forEach { (power, tier) ->
            val result = RankAuthorityRules.authority(power, tier)
            if (result == null) {
                assertEquals(Power.ALLIANCE to RankChangeTier.MARSHAL, power to tier)
            } else {
                assertEquals(power, result.power)
                known++
            }
        }
        assertEquals(5, known)
    }

    @Test fun t8_sourceNamesPreserveOriginalSpellingAndFullwidthTilde() {
        assertEquals(listOf("元帥", "上級大将\uFF5E准将", "大佐以下"), RankChangeTier.entries.map { it.sourceName })
        assertEquals(listOf("皇帝", "軍務尚書", "軍務省人事局長", "国防委員長", "国防委員会人事局長"),
            RankAuthority.entries.map { it.sourceName })
    }

    @Test fun t9_emperorDoesNotInheritCaptainAuthority() {
        assertFalse(RankAuthorityRules.mayChangeRank(Power.EMPIRE, RankChangeTier.CAPTAIN_AND_BELOW,
            setOf(RankAuthority.EMPEROR)))
    }

    @Test fun t10_exactAuthorityAmongMultiplePostsAllowsChange() {
        assertTrue(RankAuthorityRules.mayChangeRank(Power.EMPIRE, RankChangeTier.CAPTAIN_AND_BELOW,
            setOf(RankAuthority.EMPEROR, RankAuthority.MILITARY_PERSONNEL_BUREAU_CHIEF)))
    }

    @Test fun t11_emptyAuthoritiesRejectEveryCombination() {
        Power.entries.forEach { power -> RankChangeTier.entries.forEach { tier ->
            assertFalse(RankAuthorityRules.mayChangeRank(power, tier, emptySet()), "$power/$tier")
        } }
    }

    @Test fun t12_otherPowerAuthorityIsIgnored() {
        assertFalse(RankAuthorityRules.mayChangeRank(Power.ALLIANCE, RankChangeTier.SENIOR_ADMIRAL_TO_COMMODORE,
            setOf(RankAuthority.MILITARY_AFFAIRS_SECRETARY)))
        assertTrue(RankAuthorityRules.mayChangeRank(Power.ALLIANCE, RankChangeTier.SENIOR_ADMIRAL_TO_COMMODORE,
            setOf(RankAuthority.MILITARY_AFFAIRS_SECRETARY, RankAuthority.DEFENSE_COMMITTEE_CHAIR)))
    }

    @Test fun t13_defenseCommitteeChairDoesNotInheritCaptainAuthority() {
        assertFalse(RankAuthorityRules.mayChangeRank(Power.ALLIANCE, RankChangeTier.CAPTAIN_AND_BELOW,
            setOf(RankAuthority.DEFENSE_COMMITTEE_CHAIR)))
    }

    @Test fun t14_inputAuthoritiesRemainUnchangedOnEveryPath() {
        val actors = RankAuthority.entries.toMutableSet()
        val before = actors.toSet()
        Power.entries.forEach { power -> RankChangeTier.entries.forEach { tier ->
            RankAuthorityRules.mayChangeRank(power, tier, actors)
            assertEquals(before, actors, "$power/$tier")
        } }
    }

    @Test fun t15_unresolvedMarshalRejectsChairAndEvenAllAuthorities() {
        assertFalse(RankAuthorityRules.mayChangeRank(Power.ALLIANCE, RankChangeTier.MARSHAL,
            setOf(RankAuthority.DEFENSE_COMMITTEE_CHAIR)))
        assertFalse(RankAuthorityRules.mayChangeRank(Power.ALLIANCE, RankChangeTier.MARSHAL,
            RankAuthority.entries.toSet()))
    }

    @Test fun t16_onlyExactAuthorityPermitsEachKnownCombination() {
        val required = mapOf(
            (Power.EMPIRE to RankChangeTier.MARSHAL) to RankAuthority.EMPEROR,
            (Power.EMPIRE to RankChangeTier.SENIOR_ADMIRAL_TO_COMMODORE) to RankAuthority.MILITARY_AFFAIRS_SECRETARY,
            (Power.EMPIRE to RankChangeTier.CAPTAIN_AND_BELOW) to RankAuthority.MILITARY_PERSONNEL_BUREAU_CHIEF,
            (Power.ALLIANCE to RankChangeTier.SENIOR_ADMIRAL_TO_COMMODORE) to RankAuthority.DEFENSE_COMMITTEE_CHAIR,
            (Power.ALLIANCE to RankChangeTier.CAPTAIN_AND_BELOW) to RankAuthority.DEFENSE_PERSONNEL_BUREAU_CHIEF,
        )
        required.forEach { (combination, expected) ->
            RankAuthority.entries.forEach { actor ->
                assertEquals(actor == expected,
                    RankAuthorityRules.mayChangeRank(combination.first, combination.second, setOf(actor)),
                    "$combination/$actor")
            }
        }
    }
}
