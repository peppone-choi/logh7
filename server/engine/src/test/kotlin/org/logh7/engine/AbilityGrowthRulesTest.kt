package org.logh7.engine

import org.junit.jupiter.api.Test
import org.logh7.engine.strategy.CpPool
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AbilityGrowthRulesTest {
    @Test fun abilityTablePreservesAllEightNamesPoolsAndOrder() {
        assertEquals(
            listOf(
                Triple("LEADERSHIP", "統率", CpPool.PCP),
                Triple("POLITICS", "政治", CpPool.PCP),
                Triple("ADMINISTRATION", "運営", CpPool.PCP),
                Triple("INTELLIGENCE", "情報", CpPool.PCP),
                Triple("COMMAND", "指揮", CpPool.MCP),
                Triple("MOBILITY", "機動", CpPool.MCP),
                Triple("ATTACK", "攻撃", CpPool.MCP),
                Triple("DEFENSE", "防御", CpPool.MCP),
            ),
            Ability.entries.map { Triple(it.name, it.sourceName, it.pool) },
        )
    }

    @Test fun pcpAbilitiesFollowDeclarationOrder() {
        assertEquals(
            listOf(Ability.LEADERSHIP, Ability.POLITICS, Ability.ADMINISTRATION, Ability.INTELLIGENCE),
            AbilityGrowthRules.abilitiesFor(CpPool.PCP),
        )
    }

    @Test fun mcpAbilitiesFollowDeclarationOrder() {
        assertEquals(
            listOf(Ability.COMMAND, Ability.MOBILITY, Ability.ATTACK, Ability.DEFENSE),
            AbilityGrowthRules.abilitiesFor(CpPool.MCP),
        )
    }

    @Test fun poolsPartitionAllAbilitiesWithoutOverlap() {
        val groups = CpPool.entries.map { AbilityGrowthRules.abilitiesFor(it) }
        val combined = groups.flatten()
        assertEquals(Ability.entries.toSet(), combined.toSet())
        assertEquals(combined.size, combined.toSet().size)
        for (i in groups.indices) for (j in i + 1 until groups.size) {
            assertTrue(groups[i].toSet().intersect(groups[j].toSet()).isEmpty())
        }
    }

    @Test fun firstExperiencePointPreservesValueAndInput() {
        val progress = AbilityProgress(50, 0)
        assertEquals(AbilityProgress(50, 1), AbilityGrowthRules.addExperiencePoint(progress))
        assertEquals(AbilityProgress(50, 0), progress)
    }

    @Test fun experienceNinetyEightDoesNotGrowValue() {
        assertEquals(AbilityProgress(50, 99), AbilityGrowthRules.addExperiencePoint(AbilityProgress(50, 98)))
    }

    @Test fun experienceNinetyNineGrowsValueAndResetsExperience() {
        val progress = AbilityProgress(50, 99)
        assertEquals(AbilityProgress(51, 0), AbilityGrowthRules.addExperiencePoint(progress))
        assertEquals(AbilityProgress(50, 99), progress)
    }

    @Test fun repeatedPointsGrowOncePerHundred() {
        var progress = AbilityProgress(50, 0)
        repeat(100) { progress = AbilityGrowthRules.addExperiencePoint(progress) }
        assertEquals(AbilityProgress(51, 0), progress)
        repeat(100) { progress = AbilityGrowthRules.addExperiencePoint(progress) }
        assertEquals(AbilityProgress(52, 0), progress)
    }

    @Test fun invalidExperienceIsRejectedInsteadOfCarriedOrClamped() {
        for (experience in listOf(Int.MIN_VALUE, -1, 100, 101, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { AbilityProgress(50, experience) }
            assertFailsWith<IllegalArgumentException> { AbilityProgress(50, 0).copy(experience = experience) }
        }
    }

    @Test fun valueOverflowOnGrowthFailsWithoutChangingInput() {
        val progress = AbilityProgress(Int.MAX_VALUE, 99)
        assertFailsWith<ArithmeticException> { AbilityGrowthRules.addExperiencePoint(progress) }
        assertEquals(AbilityProgress(Int.MAX_VALUE, 99), progress)
    }

    @Test fun valueBoundsAreNotInferred() {
        for (value in listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE)) {
            assertEquals(AbilityProgress(value, 1), AbilityGrowthRules.addExperiencePoint(AbilityProgress(value, 0)))
        }
        assertEquals(AbilityProgress(0, 0), AbilityGrowthRules.addExperiencePoint(AbilityProgress(-1, 99)))
        assertEquals(
            AbilityProgress(Int.MAX_VALUE, 0),
            AbilityGrowthRules.addExperiencePoint(AbilityProgress(Int.MAX_VALUE - 1, 99)),
        )
    }

    @Test fun experienceThresholdIsOneHundred() {
        assertEquals(100, AbilityGrowthRules.EXPERIENCE_PER_POINT)
    }
}
