package org.logh7.engine

import org.logh7.engine.strategy.CpPool

/**
 * W p.14–15 능력치 8종과 CP 경험치 성장의 순수 판정(evidence:manual).
 * 한국어 요약 docs/manual/02-character.md와 2004-05-20 패치노트 기준이며 원문 PDF 미대조.
 * CP 사용량 환산·능력치 선택·상하한·나이 효과·격침 감소는 다루지 않는다.
 */
enum class Ability(val sourceName: String, val pool: CpPool) {
    LEADERSHIP("統率", CpPool.PCP),
    POLITICS("政治", CpPool.PCP),
    ADMINISTRATION("運営", CpPool.PCP),
    INTELLIGENCE("情報", CpPool.PCP),
    COMMAND("指揮", CpPool.MCP),
    MOBILITY("機動", CpPool.MCP),
    ATTACK("攻撃", CpPool.MCP),
    DEFENSE("防御", CpPool.MCP),
}

/** 한 능력치의 값과 고유 경험치(0..99). 값의 범위는 미정이라 검사하지 않는다. */
data class AbilityProgress(val value: Int, val experience: Int) {
    init { require(experience in 0 until AbilityGrowthRules.EXPERIENCE_PER_POINT) }
}

object AbilityGrowthRules {
    const val EXPERIENCE_PER_POINT = 100

    /** 그 CP 사용으로 경험치를 받을 수 있는 능력치, Ability.entries 순서. */
    fun abilitiesFor(pool: CpPool): List<Ability> = Ability.entries.filter { it.pool == pool }

    /** 경험치 1 가산. 100이면 능력치 +1·경험치 0. 값 오버플로는 ArithmeticException. */
    fun addExperiencePoint(progress: AbilityProgress): AbilityProgress =
        if (progress.experience == EXPERIENCE_PER_POINT - 1) {
            AbilityProgress(Math.addExact(progress.value, 1), 0)
        } else {
            progress.copy(experience = progress.experience + 1)
        }
}
