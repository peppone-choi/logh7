package org.logh7.engine

/** 전술 최고사령관 임무 6종(update04 任務コマンド一覧, evidence:manual). */
enum class TacticalMission { DEFENSE, OCCUPATION, INTERCEPTION, SKIRMISH, RECONNAISSANCE, WITHDRAWAL }

/** update04에 임무 결정 규칙이 명시된 선택 대상. */
enum class MissionTarget { OWN_PLANET_OR_FORTRESS, OTHER_PLANET_OR_FORTRESS, OTHER_SQUADRON }

/**
 * 최고사령관 임무의 순수 판정(evidence:manual).
 * 원문: https://web.archive.org/web/20050204043916/http://gineiden.com/update04.html
 * 저장소 근거: docs/manual/06-tactics.md:115-125, 00-editions.md:73.
 *
 * 이 API의 시간 계약은 0 이상인 게임 초(Long)다. G = 게임 시간 해석은
 * docs/manual/01-session-time-victory.md:48의 evidence:guess이며 원작 확정 사실이 아니다.
 * CD판 철수 10분 × 24 = 4G시간은 교차 일치 참고이며 원본 PDF 직접 대조는 NOT_RUN이다.
 * 보상량·간격 기준 시점·지정 유닛 여부·기본 공적·거리 계산은 호출자가 제공한다.
 * 상태·I/O·wire·보너스 합산 순서·임무 재설정 간격은 다루지 않는다.
 */
object TacticalMissionRules {
    const val CHECK_INTERVAL_GAME_SECONDS: Long = 24L * 60
    const val WITHDRAWAL_LIMIT_GAME_SECONDS: Long = 4L * 60 * 60
    const val RECONNAISSANCE_RANGE_KM: Double = 12_000_000.0
    const val INTERCEPTION_MERIT_MULTIPLIER: Long = 3L

    /** 자기 행성/요새 → 防衛, 타 세력 행성/요새 → 占領, 타 세력 전대 → 迎撃. */
    fun missionFor(target: MissionTarget): TacticalMission = when (target) {
        MissionTarget.OWN_PLANET_OR_FORTRESS -> TacticalMission.DEFENSE
        MissionTarget.OTHER_PLANET_OR_FORTRESS -> TacticalMission.OCCUPATION
        MissionTarget.OTHER_SQUADRON -> TacticalMission.INTERCEPTION
    }

    /** 수비대 기본은 防衛, 그 외 기본은 遊撃. */
    fun defaultMission(isGarrison: Boolean): TacticalMission =
        if (isGarrison) TacticalMission.DEFENSE else TacticalMission.SKIRMISH

    /** 호출자가 명시한 기준 시점부터 완료된 24G분 간격 수(내림). 음수·시간 역행은 거부한다. */
    fun completedCheckIntervals(sinceGameSeconds: Long, nowGameSeconds: Long): Long {
        require(sinceGameSeconds >= 0) { "sinceGameSeconds must be non-negative" }
        require(nowGameSeconds >= 0) { "nowGameSeconds must be non-negative" }
        require(nowGameSeconds >= sinceGameSeconds) { "nowGameSeconds must not precede sinceGameSeconds" }
        return (nowGameSeconds - sinceGameSeconds) / CHECK_INTERVAL_GAME_SECONDS
    }

    /** 偵察 점검 시점의 1200만 km 이내(경계 포함). 음수·NaN·무한대는 거부한다. */
    fun reconnaissanceInRange(distanceKm: Double): Boolean {
        require(distanceKm.isFinite() && distanceKm >= 0.0) { "distanceKm must be finite and non-negative" }
        return distanceKm <= RECONNAISSANCE_RANGE_KM
    }

    /** 撤退는 부여 시각부터 4G시간 이내(경계 포함). 음수·시간 역행은 거부한다. */
    fun withdrawalInTime(assignedGameSeconds: Long, withdrewGameSeconds: Long): Boolean {
        require(assignedGameSeconds >= 0) { "assignedGameSeconds must be non-negative" }
        require(withdrewGameSeconds >= 0) { "withdrewGameSeconds must be non-negative" }
        require(withdrewGameSeconds >= assignedGameSeconds) { "withdrewGameSeconds must not precede assignedGameSeconds" }
        return withdrewGameSeconds - assignedGameSeconds <= WITHDRAWAL_LIMIT_GAME_SECONDS
    }

    /** 迎撃 지정 유닛 격파 공적만 3배. 음수는 거부하고 곱 overflow는 ArithmeticException으로 전파한다. */
    fun interceptionMerit(baseMerit: Long, destroyedDesignatedUnit: Boolean): Long {
        require(baseMerit >= 0) { "baseMerit must be non-negative" }
        return if (destroyedDesignatedUnit) Math.multiplyExact(baseMerit, INTERCEPTION_MERIT_MULTIPLIER) else baseMerit
    }
}
