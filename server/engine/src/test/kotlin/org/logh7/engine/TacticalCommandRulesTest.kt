package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TacticalCommandRulesTest {
    private fun c(id: String, online: Boolean = true, rank: Int = 0, eval: Long = 0, merit: Long = 0) =
        CommandCandidate(id, online, rank, eval, merit)

    private fun orderedIds(candidates: List<CommandCandidate>) =
        TacticalCommandRules.authorityOrder(candidates).map { it.characterId }

    @Test fun t1OnlinePrecedesRank() {
        assertEquals(listOf("b", "a"), orderedIds(listOf(c("a", false, 9, 99, 99), c("b"))))
    }

    @Test fun t2RankPrecedesEvaluation() {
        assertEquals(listOf("b", "a"), orderedIds(listOf(c("a", rank = 1, eval = 99), c("b", rank = 2))))
    }

    @Test fun t3EvaluationPrecedesMerit() {
        assertEquals(listOf("b", "a"), orderedIds(listOf(c("a", eval = 1, merit = 99), c("b", eval = 2))))
    }

    @Test fun t4MeritBreaksRemainingAuthorityTie() {
        assertEquals(listOf("b", "a"), orderedIds(listOf(c("a", merit = 1), c("b", merit = 2))))
    }

    @Test fun t5CompleteAuthorityTiesPreserveEitherInputOrder() {
        val candidates = listOf(c("z"), c("a"))
        assertEquals(listOf("z", "a"), orderedIds(candidates))
        assertEquals(listOf("a", "z"), orderedIds(candidates.reversed()))
    }

    @Test fun t6AuthorityHolderUsesAllPriorityKeys() {
        assertEquals(setOf("b"), TacticalCommandRules.authorityHolders(listOf(c("a", false, 9, 99, 99), c("b"))))
        val candidates = listOf(c("a", rank = 1), c("b", rank = 2, eval = 1),
            c("c", rank = 2, eval = 2), c("d", rank = 2, eval = 2, merit = 1))
        assertEquals(setOf("d"), TacticalCommandRules.authorityHolders(candidates))
    }

    @Test fun t7AuthorityHoldersReturnEveryTopTie() {
        val candidates = listOf(c("z", merit = 2), c("lower", merit = 1), c("a", merit = 2))
        assertEquals(setOf("z", "a"), TacticalCommandRules.authorityHolders(candidates))
        assertEquals(setOf("z", "a"), TacticalCommandRules.authorityHolders(candidates.reversed()))
    }

    @Test fun t8EmptyInputReturnsEmptyResults() {
        assertEquals(emptyList(), TacticalCommandRules.authorityOrder(emptyList()))
        assertEquals(emptySet(), TacticalCommandRules.authorityHolders(emptyList()))
        assertEquals(emptySet(), TacticalCommandRules.supremeCommanders(emptyList()))
    }

    @Test fun t9SupremeCommanderRankPrecedesMeritAndIgnoresOnline() {
        assertEquals(setOf("b"), TacticalCommandRules.supremeCommanders(listOf(c("a", true, 1, 0, 99), c("b", false, 2))))
    }

    @Test fun t10SupremeCommanderUsesMeritNotEvaluation() {
        assertEquals(setOf("b"), TacticalCommandRules.supremeCommanders(listOf(c("a", eval = 99, merit = 1), c("b", merit = 2))))
    }

    @Test fun t11SupremeCommandersReturnTiesRegardlessOfOnlineAndEvaluation() {
        val candidates = listOf(c("z", false, 2, -1, 3), c("a", true, 2, 99, 3), c("lower", rank = 2, merit = 2))
        assertEquals(setOf("z", "a"), TacticalCommandRules.supremeCommanders(candidates))
        assertEquals(setOf("z", "a"), TacticalCommandRules.supremeCommanders(candidates.reversed()))
    }

    @Test fun t12BlankIdsAreRejectedAtConstruction() {
        for (id in listOf("", " ", "\t\n")) {
            assertFailsWith<IllegalArgumentException> { c(id) }
        }
    }

    @Test fun t13NegativeRankIsRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> { c("a", rank = -1) }
    }

    @Test fun t14EveryFunctionRejectsDuplicateIds() {
        val candidates = listOf(c("a"), c("a", false, 2, 3, 4))
        assertFailsWith<IllegalArgumentException> { TacticalCommandRules.authorityOrder(candidates) }
        assertFailsWith<IllegalArgumentException> { TacticalCommandRules.authorityHolders(candidates) }
        assertFailsWith<IllegalArgumentException> { TacticalCommandRules.supremeCommanders(candidates) }
    }

    @Test fun t15SignedPointsAndExtremeValuesCompareWithoutOverflow() {
        val signed = listOf(c("negative", merit = -1), c("zero"))
        assertEquals(listOf("zero", "negative"), orderedIds(signed))
        assertEquals(setOf("zero"), TacticalCommandRules.supremeCommanders(signed))
        val merits = listOf(c("min", merit = Long.MIN_VALUE), c("max", merit = Long.MAX_VALUE))
        assertEquals(listOf("max", "min"), orderedIds(merits))
        assertEquals(setOf("max"), TacticalCommandRules.authorityHolders(merits))
        assertEquals(setOf("max"), TacticalCommandRules.supremeCommanders(merits))
        val evaluations = listOf(c("min", eval = Long.MIN_VALUE), c("max", eval = Long.MAX_VALUE))
        assertEquals(listOf("max", "min"), orderedIds(evaluations))
        assertEquals(setOf("max"), TacticalCommandRules.authorityHolders(evaluations))
        val ranks = listOf(c("min"), c("max", rank = Int.MAX_VALUE))
        assertEquals(listOf("max", "min"), orderedIds(ranks))
        assertEquals(setOf("max"), TacticalCommandRules.supremeCommanders(ranks))
    }

    @Test fun t16EveryFunctionLeavesInputOrderAndContentsUnchanged() {
        val candidates = mutableListOf(c("lower", false), c("z", merit = 1), c("a", merit = 1))
        val before = candidates.toList()
        val order = TacticalCommandRules.authorityOrder(candidates)
        assertEquals(before, candidates)
        val holders = TacticalCommandRules.authorityHolders(candidates)
        assertEquals(before, candidates)
        val commanders = TacticalCommandRules.supremeCommanders(candidates)
        assertEquals(before, candidates)
        candidates.clear()
        assertEquals(listOf("z", "a", "lower"), order.map { it.characterId })
        assertEquals(setOf("z", "a"), holders)
        assertEquals(setOf("z", "a"), commanders)
    }
}
