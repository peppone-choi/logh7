package org.logh7.engine

/** Approved balance rule. The penalty is derived from actual active offices. */
object OfficeCpRecovery {
    fun penalty(offices: Int): Int = (offices - 3).coerceIn(0, 4) * 5

    fun effective(ability: Int, penalty: Int): Int = ability * (100 - penalty) / 100
}
