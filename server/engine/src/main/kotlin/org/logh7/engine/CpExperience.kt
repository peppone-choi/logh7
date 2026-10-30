package org.logh7.engine

import org.logh7.engine.strategy.CpBalance

/**
 * W p.15: CP spent on strategic commands accumulates, and each fixed amount adds one experience
 * point to an ability of the paying pool (PCP: leadership/politics/administration/intelligence,
 * MCP: command/mobility/attack/defense). 100 experience = +1 ability. The amount (10 CP), the
 * pool actually charged (a PCP substitute counts as PCP), the lowest-experience choice and the
 * 100 cap are the design approved 2026-10-30 (docs/server/design-cp-experience.md, evidence:guess).
 * Author: Peppone Choi.
 */
object CpExperience {
    const val CP_PER_POINT = 10
    const val USED_LIMIT = 1_000_000
    val PCP_ABILITIES = 0..3
    val MCP_ABILITIES = 4..7

    /** Converts accumulated CP into ability experience; null when nothing is due. Navigators keep only the remainder. */
    fun settle(players: Map<String, Navigator>, stocks: PlanetStocks,
        profile: (String) -> StrategicPerson?): Pair<Map<String, Navigator>, PlanetStocks>? {
        var people = stocks.strategic.people
        var experience = stocks.social.academy.experience
        val next = players.toMutableMap()
        for ((account, p) in players.toSortedMap()) {
            if (p.pcpUsed < CP_PER_POINT && p.mcpUsed < CP_PER_POINT) continue
            val known = people[p.characterId]
            val fallback = profile(account)
            val person = known ?: fallback ?: continue
            val values = (person.abilities ?: fallback?.abilities ?: continue).toMutableList()
            if (known == null && people.size >= 4096 || p.characterId !in experience && experience.size >= 4096) continue
            val points = (experience[p.characterId] ?: List(8) { 0 }).toMutableList()
            for ((used, abilities) in listOf(p.pcpUsed to PCP_ABILITIES, p.mcpUsed to MCP_ABILITIES)) repeat(used / CP_PER_POINT) {
                val k = abilities.filter { values[it] < AcademyActions.ABILITY_CAP }.minByOrNull { points[it] } ?: return@repeat
                if (points[k] + 1 >= AbilityGrowthRules.EXPERIENCE_PER_POINT) {
                    values[k] = minOf(AcademyActions.ABILITY_CAP, values[k] + 1); points[k] = 0
                } else points[k] += 1
            }
            people = people + (p.characterId to person.copy(abilities = values.toList()))
            experience = experience + (p.characterId to points.toList())
            next[account] = p.copy(pcpUsed = p.pcpUsed % CP_PER_POINT, mcpUsed = p.mcpUsed % CP_PER_POINT)
        }
        if (next == players) return null
        return next to stocks.copy(strategic = stocks.strategic.copy(people = people),
            social = stocks.social.copy(academy = stocks.social.academy.copy(experience = experience)))
    }
}

/** Applies a strategic CP payment and records the amount actually spent from each pool. */
fun Navigator.paidWith(balance: CpBalance): Navigator {
    val nextPcp = Math.toIntExact(balance.pcp); val nextMcp = Math.toIntExact(balance.mcp)
    return copy(pcp = nextPcp, mcp = nextMcp,
        pcpUsed = minOf(CpExperience.USED_LIMIT, pcpUsed + maxOf(0, pcp - nextPcp)),
        mcpUsed = minOf(CpExperience.USED_LIMIT, mcpUsed + maxOf(0, mcp - nextMcp)))
}
