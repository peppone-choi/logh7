package org.logh7.engine

/**
 * W p.35-36 계급 정원과 승진·강등 효과의 순수 판정(evidence:manual).
 * 근거는 한국어 요약 docs/manual/05-personnel-operations-logistics.md:29-39,52-53이며 원문 PDF 미대조.
 * 래더 정렬·인사권자·자동 승진/강등·임면·프로토콜 rank 번호는 다루지 않는다.
 */
enum class LimitedRank(val sourceName: String) {
    MARSHAL("元帥"), SENIOR_ADMIRAL("上級大将"), ADMIRAL("大将"),
    VICE_ADMIRAL("中将"), REAR_ADMIRAL("少将"), COMMODORE("准将"),
}

enum class RankAdmission { ADMISSIBLE, QUOTA_FULL, RANK_NOT_IN_POWER }

object RankRules {
    const val PROMOTED_MERIT = 0
    const val DEMOTED_MERIT = 100
    val RETAINED_CARD_NAMES: Set<String> = java.util.Collections.unmodifiableSet(setOf("個人", "艦長", "封土"))

    /** 정원. 동맹 上級大将은 존재하지 않아 null. 정원 없는 大佐 이하는 enum에서 제외한다. */
    fun capacity(power: Power, rank: LimitedRank): Int? = when (rank) {
        LimitedRank.MARSHAL -> 5
        LimitedRank.SENIOR_ADMIRAL -> if (power == Power.EMPIRE) 5 else null
        LimitedRank.ADMIRAL -> 10
        LimitedRank.VICE_ADMIRAL -> 20
        LimitedRank.REAR_ADMIRAL -> 40
        LimitedRank.COMMODORE -> 80
    }

    /** 현재 보유자 수 기준으로 한 명을 더 둘 수 있는가. 권한·래더 순위는 호출자 책임이다. */
    fun admission(power: Power, rank: LimitedRank, currentHolders: Int): RankAdmission {
        val capacity = capacity(power, rank)
        if (capacity == null) {
            require(currentHolders == 0) { "An absent rank cannot have holders" }
            return RankAdmission.RANK_NOT_IN_POWER
        }
        require(currentHolders in 0..capacity) { "currentHolders must be within 0..$capacity" }
        return if (currentHolders < capacity) RankAdmission.ADMISSIBLE else RankAdmission.QUOTA_FULL
    }

    /** 승진·강등 뒤에도 남는 카드인가. 원본 표기 정확 일치만 허용한다. */
    fun retainsCardOnRankChange(cardName: String): Boolean {
        require(cardName.isNotBlank()) { "cardName must not be blank" }
        return cardName in RETAINED_CARD_NAMES
    }
}
