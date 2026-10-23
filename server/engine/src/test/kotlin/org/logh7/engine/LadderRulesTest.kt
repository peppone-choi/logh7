package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class LadderRulesTest {
    private fun entry(
        id: String,
        merit: Long = 0,
        peerage: Peerage? = null,
        influence: Long = 0,
        ability: Long = 0,
    ) = LadderEntry(id, merit, peerage, influence, ability)

    private fun ids(entries: List<LadderEntry>) = entries.map { it.characterId }

    @Test fun t1_peeragePreservesSixSourceNamesAndApprovedOrder() {
        assertEquals(
            listOf("公爵", "侯爵", "伯爵", "子爵", "男爵", "帝国騎士"),
            Peerage.entries.map { it.sourceName },
        )
        val entries = Peerage.entries.reversed().map { entry(it.name, peerage = it) }
        assertEquals(Peerage.entries.map { it.name }, ids(LadderRules.order(Power.EMPIRE, entries)))
    }

    @Test fun t2_meritDescends() {
        val entries = listOf(entry("ten", 10), entry("thirty", 30), entry("twenty", 20))
        assertEquals(listOf("thirty", "twenty", "ten"), ids(LadderRules.order(Power.EMPIRE, entries)))
    }

    @Test fun t3_peerageDescendsWithNoPeerageLast() {
        val entries = listOf(entry("baron", peerage = Peerage.BARON), entry("none"), entry("duke", peerage = Peerage.DUKE))
        assertEquals(listOf("duke", "baron", "none"), ids(LadderRules.order(Power.EMPIRE, entries)))
    }

    @Test fun t4_imperialKnightOutranksNoPeerage() {
        val entries = listOf(entry("none"), entry("knight", peerage = Peerage.IMPERIAL_KNIGHT))
        assertEquals(listOf("knight", "none"), ids(LadderRules.order(Power.EMPIRE, entries)))
    }

    @Test fun t5_meritPrecedesPeerage() {
        val entries = listOf(entry("duke", 1, Peerage.DUKE), entry("merit", 2))
        assertEquals(listOf("merit", "duke"), ids(LadderRules.order(Power.EMPIRE, entries)))
    }

    @Test fun t6_influenceDescendsAfterEqualMeritAndPeerage() {
        val entries = listOf(entry("five", influence = 5), entry("nine", influence = 9))
        assertEquals(listOf("nine", "five"), ids(LadderRules.order(Power.EMPIRE, entries)))
    }

    @Test fun t7_peeragePrecedesInfluence() {
        val entries = listOf(entry("baron", peerage = Peerage.BARON, influence = 9), entry("duke", peerage = Peerage.DUKE, influence = 1))
        assertEquals(listOf("duke", "baron"), ids(LadderRules.order(Power.EMPIRE, entries)))
    }

    @Test fun t8_abilityDescendsOnlyAfterOtherKeysTie() {
        val entries = listOf(entry("four", ability = 400), entry("five", ability = 500))
        assertEquals(listOf("five", "four"), ids(LadderRules.order(Power.EMPIRE, entries)))
        val influenceFirst = listOf(entry("ability", influence = 1, ability = 500), entry("influence", influence = 2, ability = 400))
        assertEquals(listOf("influence", "ability"), ids(LadderRules.order(Power.EMPIRE, influenceFirst)))
    }

    @Test fun t9_allianceOrdersWithoutPeerage() {
        val entries = listOf(entry("one", influence = 1), entry("two", influence = 2))
        assertEquals(listOf("two", "one"), ids(LadderRules.order(Power.ALLIANCE, entries)))
        assertEquals(setOf("two"), LadderRules.topIds(Power.ALLIANCE, entries))
    }

    @Test fun t10_bothApisRejectAnyAlliancePeerageIncludingLowerRankedEntry() {
        val entries = listOf(entry("valid", 100), entry("invalid", peerage = Peerage.BARON))
        assertFailsWith<IllegalArgumentException> { LadderRules.order(Power.ALLIANCE, entries) }
        assertFailsWith<IllegalArgumentException> { LadderRules.topIds(Power.ALLIANCE, entries) }
    }

    @Test fun t11_fullTiesAreStableAndAllTopIdsAreReturnedWithoutIdTieBreak() {
        for (power in Power.entries) {
            val entries = listOf(entry("Z"), entry("A"), entry("lower", -1))
            assertEquals(listOf("Z", "A", "lower"), ids(LadderRules.order(power, entries)))
            assertEquals(setOf("Z", "A"), LadderRules.topIds(power, entries))
            val reversed = entries.take(2).reversed()
            assertEquals(reversed, LadderRules.order(power, reversed))
            assertEquals(setOf("Z", "A"), LadderRules.topIds(power, reversed))
        }
    }

    @Test fun t12_topIdsUsesEveryComparisonKeyAndReturnsOnlyTheLeader() {
        for (entries in listOf(
            listOf(entry("lower", 1), entry("top", 2)),
            listOf(entry("lower"), entry("top", peerage = Peerage.IMPERIAL_KNIGHT)),
            listOf(entry("lower", influence = 1), entry("top", influence = 2)),
            listOf(entry("lower", ability = 1), entry("top", ability = 2)),
            listOf(entry("top")),
        )) {
            assertEquals(setOf("top"), LadderRules.topIds(Power.EMPIRE, entries))
        }
    }

    @Test fun t13_bothApisAcceptEmptyInputForBothPowers() {
        for (power in Power.entries) {
            assertEquals(emptyList(), LadderRules.order(power, emptyList()))
            assertEquals(emptySet(), LadderRules.topIds(power, emptyList()))
        }
    }

    @Test fun t14_bothApisRejectDuplicateIdsEvenWithDifferentValues() {
        val entries = listOf(entry("same", 1), entry("same", 2))
        for (power in Power.entries) {
            assertEquals("Duplicate characterId", assertFailsWith<IllegalArgumentException> { LadderRules.order(power, entries) }.message)
            assertEquals("Duplicate characterId", assertFailsWith<IllegalArgumentException> { LadderRules.topIds(power, entries) }.message)
        }
    }

    @Test fun t15_entryRejectsBlankIdsAndNegativeAbility() {
        for (id in listOf("", " ", "\t\n")) {
            assertFailsWith<IllegalArgumentException> { entry(id) }
        }
        assertFailsWith<IllegalArgumentException> { entry("negative", ability = -1) }
        assertFailsWith<IllegalArgumentException> { entry("minimum", ability = Long.MIN_VALUE) }
        assertEquals(0L, entry("zero").abilityTotal)
        assertEquals(" spaced ", entry(" spaced ").characterId)
    }

    @Test fun t16_longExtremesCompareWithoutSubtractionOrSignRestrictions() {
        for (power in Power.entries) {
            val merit = listOf(entry("min", Long.MIN_VALUE), entry("max", Long.MAX_VALUE), entry("negative", -1))
            assertEquals(listOf("max", "negative", "min"), ids(LadderRules.order(power, merit)))
            val influence = listOf(entry("min", influence = Long.MIN_VALUE), entry("max", influence = Long.MAX_VALUE), entry("negative", influence = -1))
            assertEquals(listOf("max", "negative", "min"), ids(LadderRules.order(power, influence)))
            val ability = listOf(entry("zero"), entry("max", ability = Long.MAX_VALUE))
            assertEquals(listOf("max", "zero"), ids(LadderRules.order(power, ability)))
            assertEquals(setOf("max"), LadderRules.topIds(power, influence))
        }
    }

    @Test fun t17_bothApisLeaveMutableInputUnchangedAndAreDeterministic() {
        for (power in Power.entries) {
            val entries = mutableListOf(entry("low", 1), entry("high", 2))
            val before = entries.toList()
            val ordered = LadderRules.order(power, entries)
            assertEquals(before, entries)
            assertEquals(listOf("high", "low"), ids(ordered))
            assertEquals(ordered, LadderRules.order(power, entries))
            assertEquals(setOf("high"), LadderRules.topIds(power, entries))
            assertEquals(before, entries)
            entries.clear()
            assertEquals(listOf("high", "low"), ids(ordered))
        }
    }
}
