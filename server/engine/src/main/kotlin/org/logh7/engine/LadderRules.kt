package org.logh7.engine

/**
 * 帝国 爵位. 웹판 매뉴얼 PDF p.14(인쇄 13)는 "5種類"라 쓰면서 여섯 이름을 나열한다.
 * U2(evidence:guess): 나열 순서를 높은 순으로 채택하고, 작위 없음은 帝国騎士 아래로 둔다.
 * 이 서열은 책임자 승인 결정이며 검증된 원작 서열이 아니다. 원본 출처는 [LadderRules] 참고.
 */
enum class Peerage(val sourceName: String) {
    DUKE("公爵"), MARQUIS("侯爵"), COUNT("伯爵"), VISCOUNT("子爵"), BARON("男爵"), IMPERIAL_KNIGHT("帝国騎士"),
}

/**
 * 같은 계급·래더 종류(군인/정치가)·진영 후보 한 명의 비교 값. 후보 필터링은 호출자 책임이다.
 * peerage null은 작위 없음. 영향력의 정의·범위 및 능력 합계 계산은 호출자 책임이며 기본값은 없다.
 * 공적·영향력은 부호 제한 없이 비교한다.
 */
data class LadderEntry(
    val characterId: String,
    val meritPoints: Long,
    val peerage: Peerage?,
    val influence: Long,
    val abilityTotal: Long,
) {
    init {
        require(characterId.isNotBlank()) { "Blank characterId" }
        require(abilityTotal >= 0) { "Negative abilityTotal" }
    }
}

/**
 * 階級ラダー 순위(evidence:manual): 웹판 매뉴얼 PDF p.35(인쇄 34)의 다섯 법칙.
 * 출처: http://web.archive.org/web/20050504172714/http://www.gineiden.com:80/gin7manual.pdf
 * SHA-256: ff9b7b638582febba723413d9956f4166aecbc20746cb35bb4afddcef9515080
 *
 * 공적 많은 순 → 작위 높은 순(EMPIRE만) → 훈장 → 영향력 높은 순 → 전 능력치 합계.
 * 제3법칙은 원문 "現在叙勲は未実装"에 따라 건너뛰며 훈장 키를 만들지 않는다.
 * U1(evidence:guess): 원문에 방향 없는 능력 합계는 높은 순으로 승인됐다.
 * U2(evidence:guess): 작위 서열·작위 없음 위치는 [Peerage]의 승인 추정이다.
 * 이 두 결정을 검증된 원작 사실로 간주하지 않는다.
 *
 * U5: 완전 동률은 안정 정렬·최상위 동률 전원 반환으로 처리하며 임의 선택은 하지 않는다.
 * 공적 변경 시 재호출·자동 승진 주기(Q-M1)·정치가 특칙·권한·정원·영속화는 범위 밖이다.
 */
object LadderRules {
    /** 동률이면 입력 순서를 유지한다. 입력 목록은 변경하지 않는다. */
    fun order(power: Power, entries: List<LadderEntry>): List<LadderEntry> {
        val ids = HashSet<String>()
        for (entry in entries) {
            require(ids.add(entry.characterId)) { "Duplicate characterId" }
            require(power != Power.ALLIANCE || entry.peerage == null) { "ALLIANCE peerage must be null" }
        }
        return entries.sortedWith(comparator(power))
    }

    /** 최상위와 모든 비교 키가 같은 ID 전체. 빈 입력은 빈 집합. 최종 선택은 호출자 책임이다. */
    fun topIds(power: Power, entries: List<LadderEntry>): Set<String> {
        val ordered = order(power, entries)
        val top = ordered.firstOrNull() ?: return emptySet()
        val comparator = comparator(power)
        return ordered.takeWhile { comparator.compare(it, top) == 0 }.map { it.characterId }.toSet()
    }

    private fun comparator(power: Power): Comparator<LadderEntry> =
        compareByDescending<LadderEntry> { it.meritPoints }
            .thenBy { if (power == Power.EMPIRE) it.peerage?.ordinal ?: Int.MAX_VALUE else 0 }
            .thenByDescending { it.influence }
            .thenByDescending { it.abilityTotal }
}
