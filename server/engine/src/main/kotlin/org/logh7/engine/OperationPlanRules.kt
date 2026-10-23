package org.logh7.engine

/** 작전 결과 등급. 防衛는 조건 상실 철회가 우선하므로 FAILURE를 반환하지 않는다. */
enum class OperationOutcome { FULL_SUCCESS, PARTIAL_SUCCESS, FAILURE }

/**
 * 占領/防衛 목표 조건, 防衛 조건 상실, 30일 기간 및 결과의 순수 판정.
 * evidence:manual — 한국어 요약 docs/manual/05-personnel-operations-logistics.md:68-88
 * (W p.38-40) 기준. 일본어 원문 PDF 직접 대조는 NOT_RUN이며 완전 재현을 주장하지 않는다.
 *
 * facilities는 목표 성계 안 행성/요새 ID 집합이다. 중립(페잔) 목표 제외는 호출자 책임이며,
 * 자기 소유가 없다는 사실만으로 적 진영을 판별하는 일반 소유권 API가 아니다.
 * owned 계열은 입안자 진영 지배 시설이며 다른 성계 시설은 교집합에서 제외한다.
 * 掃討·보너스 값·CP·유닛 한도·발령·占領 조건 상실·타이머는 다루지 않는다.
 * 모든 집합의 blank ID는 IllegalArgumentException이며 입력 집합은 변경하지 않는다.
 */
object OperationPlanRules {
    /** 발령 시점부터 게임 30일. */
    const val DURATION_GAME_SECONDS: Long = 30L * 86_400

    /** 占領 적격: 시설이 존재하고 입안자 진영 지배 시설이 없다. */
    fun isOccupationTarget(facilities: Set<String>, owned: Set<String>): Boolean {
        validateIds(facilities, owned)
        return facilities.isNotEmpty() && facilities.none { it in owned }
    }

    /** 防衛 적격: 입안자 진영 지배 시설이 하나 이상이다. 빈 성계는 false. */
    fun isDefenseTarget(facilities: Set<String>, owned: Set<String>): Boolean {
        validateIds(facilities, owned)
        return facilities.any { it in owned }
    }

    /** 防衛 조건 상실 = 즉시 자동 철회 대상. 빈 성계도 true. */
    fun isDefenseConditionLost(facilities: Set<String>, owned: Set<String>): Boolean =
        !isDefenseTarget(facilities, owned)

    /** 음수 발령 시각은 IllegalArgumentException, 합산 오버플로는 ArithmeticException. */
    fun endGameSeconds(issuedGameSeconds: Long): Long {
        require(issuedGameSeconds >= 0) { "Issuance game seconds must be nonnegative" }
        return Math.addExact(issuedGameSeconds, DURATION_GAME_SECONDS)
    }

    /** 占領 결과: 전부 지배 / 일부 지배 / 지배 없음. 빈 목표는 예외. */
    fun occupationOutcome(facilities: Set<String>, ownedAtEnd: Set<String>): OperationOutcome {
        validateIds(facilities, ownedAtEnd)
        require(facilities.isNotEmpty()) { "Outcome requires target facilities" }
        val ownedCount = facilities.count { it in ownedAtEnd }
        return when (ownedCount) {
            facilities.size -> OperationOutcome.FULL_SUCCESS
            0 -> OperationOutcome.FAILURE
            else -> OperationOutcome.PARTIAL_SUCCESS
        }
    }

    /**
     * defended는 호출자가 기준 시점을 확정한 방위 시설 집합(비어 있지 않은 facilities의 부분집합).
     * 기준 시점은 여기서 추정하지 않는다. 새로 얻은 시설은 기존 시설 상실을 상쇄하지 않는다.
     * 종료 시 목표 지배가 0이면 철회 우선이므로 IllegalArgumentException.
     */
    fun defenseOutcome(facilities: Set<String>, defended: Set<String>, ownedAtEnd: Set<String>): OperationOutcome {
        validateIds(facilities, defended, ownedAtEnd)
        require(facilities.isNotEmpty()) { "Outcome requires target facilities" }
        require(defended.isNotEmpty() && facilities.containsAll(defended)) {
            "Defended facilities must be a nonempty subset of target facilities"
        }
        require(facilities.any { it in ownedAtEnd }) { "Defense condition lost: withdraw before outcome evaluation" }
        return if (ownedAtEnd.containsAll(defended)) OperationOutcome.FULL_SUCCESS else OperationOutcome.PARTIAL_SUCCESS
    }

    private fun validateIds(vararg sets: Set<String>) {
        require(sets.all { ids -> ids.all { it.isNotBlank() } }) { "Facility IDs must not be blank" }
    }
}
