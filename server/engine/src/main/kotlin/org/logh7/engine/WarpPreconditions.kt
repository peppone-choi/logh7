package org.logh7.engine

/**
 * @property cruise 호출자가 제공하는 현재 航続 값. 최대치·소비·회복은 다루지 않는다.
 * @property targetIsStarSystem 목표 그리드가 성계인지 여부.
 * @property originAdjacentToTarget 출발 그리드가 목표에 인접한지 여부. 대각선 포함 등 기하는 호출자 책임이다.
 */
data class WarpContext(val cruise: Int, val targetIsStarSystem: Boolean, val originAdjacentToTarget: Boolean)

enum class WarpDecision { ALLOWED, INSUFFICIENT_CRUISE, STAR_SYSTEM_NOT_ADJACENT }

/**
 * W p.32–33의 한국어 매뉴얼 요약(docs/manual/04-strategy-core.md)에 따른 순수 사전조건 판정.
 * 일본어 원문 및 실제 클라이언트 동작과의 대조는 NOT_RUN이다.
 *
 * ALLOWED는 항속과 성계 인접의 두 사전조건만 통과했다는 뜻이다.
 * 거리 범위·오차 없음·CP 충분·목표 진입 가능을 보장하지 않으며, 항속 소비나 이동을 수행하지 않는다.
 */
object WarpPreconditions {
    const val MIN_CRUISE = 100

    /** 음수 항속은 잘못된 입력이며, 유효한 입력에서는 항속 부족을 성계 인접 조건보다 먼저 판정한다. */
    fun evaluate(context: WarpContext): WarpDecision {
        require(context.cruise >= 0) { "cruise must be non-negative" }
        if (context.cruise < MIN_CRUISE) return WarpDecision.INSUFFICIENT_CRUISE
        if (context.targetIsStarSystem && !context.originAdjacentToTarget) return WarpDecision.STAR_SYSTEM_NOT_ADJACENT
        return WarpDecision.ALLOWED
    }
}
