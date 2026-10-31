package org.logh7.engine

/** Native allocation units, ordered engine/warp/sensor/beam/gun/six shield faces. Author: Peppone Choi. */
data class TacticalEnergy(val values: List<Int>) {
    // Retain legacy rows; command validation applies the per-slider limits below.
    init { require(values.size == 11 && values.all { it in 0..130 }) }
    val allocated get() = values.sum()
    fun reserve(output: Int): Int {
        require(output >= allocated)
        return output - allocated
    }
    companion object {
        /** Native 005123b0 creates the WARP slider with range 0..50. */
        const val WARP_MAX = 50
        val MAXIMUM = listOf(100,50,30,130,100) + List(6) {100}
        val ZERO = TacticalEnergy(List(11) { 0 })
    }
}
