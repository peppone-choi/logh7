package org.logh7.engine

/**
 * 전술 지휘권 자동 배정(W p.47)·최고사령관 선정(update04)의 후보 값.
 * evidence:manual — 한국어 요약 docs/manual/06-tactics.md:28,114 기준, 원문 PDF 대조 NOT_RUN.
 * 같은 부대 / 같은 진영의 해당 전술 그리드 후보 필터링은 호출자 책임이다.
 * rankOrder는 클수록 높은 계급이며 계급표·이양·임무·통지 wire는 다루지 않는다.
 */
data class CommandCandidate(
    val characterId: String,
    val online: Boolean,
    val rankOrder: Int,
    val evaluationPoints: Long,
    val meritPoints: Long,
) {
    init {
        require(characterId.isNotBlank()) { "characterId must not be blank" }
        require(rankOrder >= 0) { "rankOrder must be non-negative" }
    }
}

object TacticalCommandRules {
    private val authorityComparator = compareByDescending<CommandCandidate> { it.online }
        .thenByDescending { it.rankOrder }
        .thenByDescending { it.evaluationPoints }
        .thenByDescending { it.meritPoints }

    private val supremeComparator = compareBy<CommandCandidate> { it.rankOrder }
        .thenBy { it.meritPoints }

    /**
     * 온라인 → 계급 → 평가 → 공적 내림차순. 완전 동률은 입력 순서를 유지한다.
     * 이 안정성은 최종 선정 규칙이 아니며 첫 항목만으로 동률의 승자를 결정하면 안 된다.
     * 중복 ID는 거부하며 입력 목록은 변경하지 않는다.
     */
    fun authorityOrder(candidates: List<CommandCandidate>): List<CommandCandidate> {
        requireUniqueIds(candidates)
        return candidates.sortedWith(authorityComparator)
    }

    /** 네 우선순위 키가 모두 같은 최상위 ID 전체. 빈 입력은 빈 집합, 동률의 최종 선택은 호출자 책임. */
    fun authorityHolders(candidates: List<CommandCandidate>): Set<String> {
        val ordered = authorityOrder(candidates)
        val first = ordered.firstOrNull() ?: return emptySet()
        return ordered.takeWhile { authorityComparator.compare(it, first) == 0 }
            .map { it.characterId }.toSet()
    }

    /** 계급 → 공적만 비교한다. online·평가는 무시하는 문언 기반 임시 규칙이며 완전 동률은 모두 반환한다. */
    fun supremeCommanders(candidates: List<CommandCandidate>): Set<String> {
        requireUniqueIds(candidates)
        val highest = candidates.maxWithOrNull(supremeComparator) ?: return emptySet()
        return candidates.filter { supremeComparator.compare(it, highest) == 0 }
            .map { it.characterId }.toSet()
    }

    private fun requireUniqueIds(candidates: List<CommandCandidate>) {
        val ids = HashSet<String>()
        require(candidates.all { ids.add(it.characterId) }) { "Duplicate characterId" }
    }
}
