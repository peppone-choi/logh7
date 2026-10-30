package org.logh7.engine

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 워프 항속 소비와 오차 도착점. Author: 최병호.
 *
 * 그리드 한 변 100광년(W 전략 화면 설명)과 완충 항속 1,000광년(CD p.31)에 맞춰 이동 거리(광년)만큼 항속을 소비한다.
 * 장거리 워프는 목표에 인접한 공간 그리드에 도착할 수 있다(W p.32). 거리 계산·오차 확률·후보 선택은
 * 원래 공식을 복원하지 못한 설계값이다. 난수 상태를 저장하지 않고 같은 입력에서 같은 결과를 낸다.
 */
object WarpCourse {
    const val LIGHT_YEARS_PER_GRID = 100

    /** 유클리드 거리의 광년 값을 올림한다. 인접 대각선은 142다. */
    fun cruiseCost(origin: Int, destination: Int): Int {
        val dx = destination % 100 - origin % 100
        val dy = destination / 100 - origin / 100
        return ceil(LIGHT_YEARS_PER_GRID * sqrt((dx * dx + dy * dy).toDouble()) - 1e-9).toInt()
    }

    /** 체비셰프 거리 2 이상에서 한 칸마다 10%. 인접 워프와 성계 목표에는 오차가 없다. */
    fun errorPercent(origin: Int, destination: Int, starSystem: Boolean): Int {
        if (starSystem) return 0
        val reach = maxOf(kotlin.math.abs(destination % 100 - origin % 100), kotlin.math.abs(destination / 100 - origin / 100))
        return ((reach - 1) * 10).coerceIn(0, 100)
    }

    /**
     * 오차가 나면 목표의 8방향 인접 공간 그리드 중 [enterable]을 통과한 하나를 고른다. 후보가 없으면 목표에 도착한다.
     * [salt]는 같은 인물의 같은 이동 차례를 구분하는 값이며 클라이언트 입력으로 고를 수 없어야 한다.
     */
    fun arrival(galaxy: Galaxy, origin: Int, destination: Int, salt: Long, enterable: (Int) -> Boolean): Int {
        val percent = errorPercent(origin, destination, galaxy.kind(destination) == GridKind.STAR_SYSTEM)
        if (percent == 0) return destination
        val roll = mix(salt xor (origin.toLong() shl 32) xor destination.toLong())
        if (java.lang.Long.remainderUnsigned(roll, 100) >= percent) return destination
        val x = destination % 100
        val y = destination / 100
        val candidates = (-1..1).flatMap { dy -> (-1..1).map { dx -> dx to dy } }
            .filter { (dx, dy) -> (dx != 0 || dy != 0) && x + dx in 0 until galaxy.width && y + dy in 0 until galaxy.height }
            .map { (dx, dy) -> (y + dy) * 100 + x + dx }
            .filter { it != origin && galaxy.kind(it) == GridKind.SPACE && enterable(it) }
        if (candidates.isEmpty()) return destination
        return candidates[java.lang.Long.remainderUnsigned(mix(roll), candidates.size.toLong()).toInt()]
    }

    private fun mix(value: Long): Long { // SplitMix64 finalizer
        var z = value + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
