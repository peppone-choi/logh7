package org.logh7.engine

/** Native allocation units, ordered engine/warp/sensor/beam/gun/six shield faces. Author: 최병호. */
data class TacticalEnergy(val values: List<Int>) {
    init { require(values.size == 11 && values.all { it in 0..100 }) }
    val allocated get() = values.sum()
    fun reserve(output: Int): Int {
        require(output >= allocated)
        return output - allocated
    }
    companion object {
        /** Native 005123b0 creates the WARP slider with range 0..50. */
        const val WARP_MAX = 50
        val ZERO = TacticalEnergy(List(11) { 0 })
    }
}
