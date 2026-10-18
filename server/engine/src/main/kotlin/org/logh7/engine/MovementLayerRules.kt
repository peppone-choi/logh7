package org.logh7.engine

/**
 * 캐릭터 위치의 grid → planetOrFortress → facility → spot 경로.
 * ID는 불투명 문자열이며 하위 ID가 같아도 상위 경로가 다르면 다른 위치다.
 * 배에 탄 캐릭터의 위치 깊이는 근거가 부족하므로 호출자가 결정한다(W p.8).
 */
data class MoveLocation(val grid: String, val planetOrFortress: String? = null,
    val facility: String? = null, val spot: String? = null)

/** W p.33의 이동 계층. 목표 위치의 가장 깊은 단계로 정한다. */
enum class MovementLayer { GRID_WARP, INTRA_SYSTEM, INTER_FACILITY, INTRA_FACILITY }

/**
 * ALLOWED는 해당 계층의 목표 후보라는 뜻일 뿐 실제 이동 허가가 아니다.
 * 카드 권한·CP·대기·航続·진입 제한·워프 인접 조건은 보장하지 않는다.
 * UNDETERMINED는 근거 부족으로 허용/거절을 판정하지 않았다는 뜻이다.
 */
enum class MovementVerdict { ALLOWED, SAME_TARGET, NOT_SAME_PARENT, UNDETERMINED }

data class MovementDecision(val layer: MovementLayer, val verdict: MovementVerdict)

/**
 * W p.33의 한국어 요약(docs/manual/04-strategy-core.md)에 근거한 순수 계층 판정.
 * 일본어 원문은 미대조다. 寄港과 星系内航行의 관계, 「우주 공간에서만」,
 * 배에 탄 캐릭터 위치는 확정하지 않으며 커맨드 ID를 매핑하지 않는다.
 * 월드 데이터 조회나 실제 이동, 다른 이동 사전조건 검사는 수행하지 않는다.
 */
object MovementLayerRules {
    /** @throws IllegalArgumentException ID가 blank이거나 위치 계층을 건너뛴 경우. */
    fun evaluate(origin: MoveLocation, target: MoveLocation): MovementDecision {
        val originPath = validatedPath(origin)
        val targetPath = validatedPath(target)
        val depth = targetPath.indexOfLast { it != null } + 1
        val originDepth = originPath.indexOfLast { it != null } + 1
        val layer = when (depth) {
            4 -> MovementLayer.INTRA_FACILITY
            3 -> MovementLayer.INTER_FACILITY
            2 -> MovementLayer.INTRA_SYSTEM
            else -> MovementLayer.GRID_WARP
        }
        val verdict = when {
            (0 until depth - 1).any { originPath[it] != targetPath[it] } -> MovementVerdict.NOT_SAME_PARENT
            originPath[depth - 1] == targetPath[depth - 1] -> MovementVerdict.SAME_TARGET
            when (layer) {
                MovementLayer.INTRA_FACILITY -> originDepth == 4
                MovementLayer.INTER_FACILITY -> originDepth >= 3
                MovementLayer.INTRA_SYSTEM -> originDepth == 2
                MovementLayer.GRID_WARP -> originDepth == 1
            } -> MovementVerdict.ALLOWED
            else -> MovementVerdict.UNDETERMINED
        }
        return MovementDecision(layer, verdict)
    }

    private fun validatedPath(location: MoveLocation): List<String?> {
        val path = listOf(location.grid, location.planetOrFortress, location.facility, location.spot)
        require(path.all { it == null || it.isNotBlank() }) { "Location IDs must not be blank" }
        require(location.facility == null || location.planetOrFortress != null) { "Facility requires planetOrFortress" }
        require(location.spot == null || location.facility != null) { "Spot requires facility" }
        return path
    }
}
