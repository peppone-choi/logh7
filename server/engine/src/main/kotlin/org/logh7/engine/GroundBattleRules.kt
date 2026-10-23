package org.logh7.engine

/**
 * W 매뉴얼 PDF p.51의 장소 분류(evidence:manual).
 * SPECIAL_PLANET은 가스 행성 등이며 원문은 당시 행성 타입 미구현을 명시한다.
 * 이 enum은 실제 지도 데이터나 원본 서비스의 특수 행성 지원을 의미하지 않는다.
 */
enum class GroundSite { NORMAL_PLANET, SPECIAL_PLANET, FORTRESS }

/** W PDF p.51의 세 추상 분류. 특수 육전대의 매핑은 근거 없이 추정하지 않는다. */
enum class GroundUnitKind {
    ARMORED_INFANTRY, // 装甲兵
    ARMORED_GRENADIER, // 装甲擲弾兵
    LIGHT_MARINE // 軽装陸戦兵
}

/** 함정의 행성/요새 계류 상태. W PDF p.54(evidence:manual). */
enum class ShipBerth { NONE, ANCHORED, STATIONED }

enum class GroundLandingVerdict { DESCENT, SORTIE, NOT_BERTHED, NO_GROUND_UNITS, BOX_CAPACITY_EXCEEDED }

/**
 * W PDF p.51/p.54/p.69의 로컬 추출과 대조한 순수 지상전 판정(evidence:manual).
 * 엔티티, 상태 변경, 그리드 300 상한, 타이머, 전투 계산과 점령 효과는 다루지 않는다.
 * 아래 설계 해석(evidence:guess)은 실제 클라이언트 동작 검증 결과가 아니다.
 */
object GroundBattleRules {
    const val BOX_CAPACITY_PER_FACTION = 30

    /**
     * p.51의 장소별 참가 가능 종류. 매번 새 집합을 반환하므로 외부 변경은 후속 조회에 영향이 없다.
     * 참가 조회와 강하 허용을 분리하는 것은 이번 API의 설계 정책(U4, evidence:guess)이다.
     * 참가 불가 종류가 섞인 편성의 실제 게임 처리를 검증했다는 뜻이 아니다.
     */
    fun eligibleKinds(site: GroundSite): Set<GroundUnitKind> = when (site) {
        GroundSite.NORMAL_PLANET -> setOf(
            GroundUnitKind.ARMORED_INFANTRY, GroundUnitKind.ARMORED_GRENADIER, GroundUnitKind.LIGHT_MARINE)
        GroundSite.SPECIAL_PLANET, GroundSite.FORTRESS -> setOf(
            GroundUnitKind.ARMORED_GRENADIER, GroundUnitKind.LIGHT_MARINE)
    }

    fun canParticipate(site: GroundSite, kind: GroundUnitKind): Boolean = kind in eligibleKinds(site)

    /**
     * p.54: 정박 시 강하, 주류 시 출격. p.51: 탑재 육전대 필요, 진영당 박스 상한 30(수비대 포함).
     * landingUnits는 이번 명령으로 내릴 전 육전대 수이며 Int.MAX_VALUE까지 비음수 입력을 허용한다.
     * ownUnitsInBox는 호출자가 집계한 같은 진영의 기존 박스 유닛 수다.
     *
     * 전부 강하 또는 전부 불가(U3)는 p.69의 전 육전대 명령에서 선택한 설계 해석(evidence:guess)이다.
     * 검증 → NOT_BERTHED → NO_GROUND_UNITS → BOX_CAPACITY_EXCEEDED → DESCENT/SORTIE
     * 우선순위(U8)도 설계 선택이며 원문에 명시된 판정 순서가 아니다.
     * 참가 종류는 여기서 거부하지 않는 API 정책(U4)이며 실제 게임의 강하 동작 확인이 아니다.
     *
     * @throws IllegalArgumentException 음수 강하 수 또는 0..30 밖의 기존 박스 수.
     */
    fun landingVerdict(berth: ShipBerth, landingUnits: Int, ownUnitsInBox: Int): GroundLandingVerdict {
        require(landingUnits >= 0) { "landingUnits must be nonnegative" }
        requireBoxCount(ownUnitsInBox)
        if (berth == ShipBerth.NONE) return GroundLandingVerdict.NOT_BERTHED
        if (landingUnits == 0) return GroundLandingVerdict.NO_GROUND_UNITS
        if (landingUnits > BOX_CAPACITY_PER_FACTION - ownUnitsInBox) {
            return GroundLandingVerdict.BOX_CAPACITY_EXCEEDED
        }
        return when (berth) {
            ShipBerth.ANCHORED -> GroundLandingVerdict.DESCENT
            ShipBerth.STATIONED -> GroundLandingVerdict.SORTIE
            ShipBerth.NONE -> GroundLandingVerdict.NOT_BERTHED
        }
    }

    /** p.51: 강하 후 적 육전대가 있으면 지상전 개시. 호출자가 강하 이후 수를 제공한다. */
    fun startsGroundBattle(enemyUnitsInBox: Int): Boolean {
        requireBoxCount(enemyUnitsInBox)
        return enemyUnitsInBox > 0
    }

    /**
     * p.51의 적 육전대 완전 제거에 따른 점령 판정.
     * 아군 1 이상 조건과 (0,0) 제외(U5)는 설계 해석(evidence:guess)이며 원본 실행 검증 사실이 아니다.
     */
    fun isOccupationComplete(ownUnitsInBox: Int, enemyUnitsInBox: Int): Boolean {
        requireBoxCount(ownUnitsInBox)
        requireBoxCount(enemyUnitsInBox)
        return ownUnitsInBox > 0 && enemyUnitsInBox == 0
    }

    private fun requireBoxCount(units: Int) {
        require(units in 0..BOX_CAPACITY_PER_FACTION) { "Box units must be in 0..$BOX_CAPACITY_PER_FACTION" }
    }
}
