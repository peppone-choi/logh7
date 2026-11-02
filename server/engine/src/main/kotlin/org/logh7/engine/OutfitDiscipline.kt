package org.logh7.engine

/**
 * 軍紀維持 (manual PDF p.68: raises discipline and lowers the confusion rate). The value range,
 * +10 per command, the one-per-game-day decay to 50 and the morale factor are the design approved
 * 2026-10-30 (docs/server/design-discipline-docking-cancel.md, evidence:guess). Decay is computed
 * on read from the stored time, so no periodic job is needed. Author: Peppone Choi.
 */
data class OutfitDiscipline(val value: Int, val at: Long) {
    init { require(value in BASELINE..MAXIMUM && at in 0..0xffffffffL) }
    companion object {
        const val BASELINE = 50
        const val MAXIMUM = 100
        const val STEP = 10
        const val DAY = 86_400L
        fun value(stocks: PlanetStocks, outfit: Int, seconds: Long): Int = stocks.discipline[outfit]?.let {
            maxOf(BASELINE, it.value - ((seconds - it.at).coerceAtLeast(0) / DAY).toInt())
        } ?: BASELINE
        /** Morale lost from a hit: unchanged at 50, half at 100, at least 1 for a non-zero hit. */
        fun moraleLoss(loss: Int, discipline: Int): Int =
            if (loss <= 0) 0 else maxOf(1, loss * (150 - discipline) / 100)
    }
}
