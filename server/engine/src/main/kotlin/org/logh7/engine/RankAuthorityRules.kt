package org.logh7.engine

/**
 * W gin7manual.pdf p.36 「階級に関する人事権」 표의 행 구분.
 * 계급 → 구간 변환은 호출자 책임이다. 변경 전/후 어느 계급을 사용할지는 근거 미정이며,
 * 두 계급 중 높은 쪽을 택한다는 추정을 여기서 구현하지 않는다.
 */
enum class RankChangeTier(val sourceName: String) {
    MARSHAL("元帥"),
    SENIOR_ADMIRAL_TO_COMMODORE("上級大将～准将"),
    CAPTAIN_AND_BELOW("大佐以下"),
}

/**
 * W gin7manual.pdf p.36 표의 인사권 직책과 원문 명칭(evidence:manual).
 * 国防委員会人事局長과 org-posts.csv의 人事部長이 같은 직책인지는 미확정이다.
 */
enum class RankAuthority(val power: Power, val sourceName: String) {
    EMPEROR(Power.EMPIRE, "皇帝"),
    MILITARY_AFFAIRS_SECRETARY(Power.EMPIRE, "軍務尚書"),
    MILITARY_PERSONNEL_BUREAU_CHIEF(Power.EMPIRE, "軍務省人事局長"),
    DEFENSE_COMMITTEE_CHAIR(Power.ALLIANCE, "国防委員長"),
    DEFENSE_PERSONNEL_BUREAU_CHIEF(Power.ALLIANCE, "国防委員会人事局長"),
}

/**
 * 승진·강등 인사권자 순수 조회(W gin7manual.pdf p.36).
 * 동맹 元帥 행은 글자 좌표상 国防委員長의 세로 병합 칸이라는 추정과
 * docs/manual/05-personnel-operations-logistics.md:47의 명시 없음 해석이 상충한다.
 * null은 역사적으로 권한이 없었다는 확정이 아니라, 근거 미확정일 때 권한을 부여하지 않는
 * 승인된 보수 정책(evidence:guess)이다. 원문 자료 정정을 확정하지 않는다.
 * 카드 소유·CP·대기·커맨드 연결·제안 경로·임명 제한은 다루지 않는다.
 */
object RankAuthorityRules {
    /** 해당 진영·구간의 인사권 직책. 미확정인 동맹 [RankChangeTier.MARSHAL]은 null. */
    fun authority(power: Power, tier: RankChangeTier): RankAuthority? = when (power) {
        Power.EMPIRE -> when (tier) {
            RankChangeTier.MARSHAL -> RankAuthority.EMPEROR
            RankChangeTier.SENIOR_ADMIRAL_TO_COMMODORE -> RankAuthority.MILITARY_AFFAIRS_SECRETARY
            RankChangeTier.CAPTAIN_AND_BELOW -> RankAuthority.MILITARY_PERSONNEL_BUREAU_CHIEF
        }
        Power.ALLIANCE -> when (tier) {
            RankChangeTier.MARSHAL -> null
            RankChangeTier.SENIOR_ADMIRAL_TO_COMMODORE -> RankAuthority.DEFENSE_COMMITTEE_CHAIR
            RankChangeTier.CAPTAIN_AND_BELOW -> RankAuthority.DEFENSE_PERSONNEL_BUREAU_CHIEF
        }
    }

    /**
     * [authority]가 확정되어 있고 [actorAuthorities]에 정확히 일치하는 직책이 있으면 true.
     * 상위 직책 권한 상속 없음은 승인된 정책(evidence:guess)이며 원문 확정 사실이 아니다.
     * 다른 진영 직책은 무시하며 빈 집합·미확정 구간은 false. 입력 집합을 바꾸지 않는다.
     */
    fun mayChangeRank(power: Power, tier: RankChangeTier, actorAuthorities: Set<RankAuthority>): Boolean {
        val required = authority(power, tier) ?: return false
        return required in actorAuthorities
    }
}
