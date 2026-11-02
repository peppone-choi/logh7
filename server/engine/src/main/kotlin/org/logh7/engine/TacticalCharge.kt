package org.logh7.engine

/** Parametric flagship charge; rate/capacity are replacement-server design. Author: Peppone Choi.
 * Work is allocation * elapsed G-seconds, preserving fractions across allocation changes.
 * At allocation 100 a full charge takes ten real seconds at the session clock scale.
 */
data class TacticalCharge(val at: Long, val beam: Long = 0, val gun: Long = 0, val clockScale: Long = 24) {
    init { require(at in 0..0xffffffffL && clockScale in 1..1000 && beam in 0..1000*clockScale && gun in 0..1000*clockScale) }
    fun settle(seconds: Long, energy: TacticalEnergy): TacticalCharge {
        require(seconds in at..0xffffffffL)
        val capacity = 1000 * clockScale
        val elapsed = seconds - at
        return TacticalCharge(seconds, minOf(capacity, beam + elapsed * energy.values[3]),
            minOf(capacity, gun + elapsed * energy.values[4]), clockScale)
    }
    fun levels(seconds: Long, energy: TacticalEnergy): Pair<Int, Int> =
        settle(seconds, energy).let {
            (it.beam * 100 / (1000 * clockScale)).toInt() to (it.gun * 100 / (1000 * clockScale)).toInt()
        }
}
