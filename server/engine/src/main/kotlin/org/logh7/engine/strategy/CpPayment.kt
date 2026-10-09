package org.logh7.engine.strategy

enum class CpPool { PCP, MCP }
data class CpBalance(val pcp: Long, val mcp: Long) {
    init { require(pcp >= 0 && mcp >= 0) }
    fun inPool(pool: CpPool) = if (pool == CpPool.PCP) pcp else mcp
}
enum class StrategyRejection {
    UNKNOWN_COMMAND, DISABLED, UNAUTHORIZED, UNRESOLVED_CP, UNRESOLVED_TIMING,
    INVALID_RESOLUTION, INSUFFICIENT_CP, UNRESOLVED_MIXED_PAYMENT, OVERFLOW, TIME_REVERSED,
}
sealed interface PaymentResult {
    data class Paid(val balance: CpBalance, val chargedPool: CpPool?, val spent: Long,
        val experienceEligibleCp: Long, val substituted: Boolean) : PaymentResult
    data class Rejected(val reason: StrategyRejection) : PaymentResult
}
object CpPayment {
    /** W27 full other-pool substitution only; no cap, recovery or ability formula is inferred. */
    fun charge(balance: CpBalance, cost: Long, pool: CpPool?): PaymentResult {
        if (cost < 0) return PaymentResult.Rejected(StrategyRejection.INVALID_RESOLUTION)
        if (cost == 0L) return PaymentResult.Paid(balance, null, 0, 0, false)
        if (pool == null) return PaymentResult.Rejected(StrategyRejection.UNRESOLVED_CP)
        val primary = balance.inPool(pool)
        fun paid(charged: CpPool, amount: Long, substituted: Boolean) = PaymentResult.Paid(
            if (charged == CpPool.PCP) balance.copy(pcp = balance.pcp - amount) else balance.copy(mcp = balance.mcp - amount),
            charged, amount, if (substituted) 0 else cost, substituted)
        if (primary >= cost) return paid(pool, cost, false)
        val other = if (pool == CpPool.PCP) CpPool.MCP else CpPool.PCP
        val secondary = balance.inPool(other)
        val full = try { Math.multiplyExact(cost, 2) }
            catch (_: ArithmeticException) { return PaymentResult.Rejected(StrategyRejection.OVERFLOW) }
        if (secondary >= full) return paid(other, full, true)
        // A combination could cover the remainder, but that policy is unconfirmed.
        val missing = cost - primary
        val mixedCouldPay = primary > 0 && secondary / 2 >= missing
        return PaymentResult.Rejected(if (mixedCouldPay) StrategyRejection.UNRESOLVED_MIXED_PAYMENT else StrategyRejection.INSUFFICIENT_CP)
    }
}
