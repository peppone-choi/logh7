package org.logh7.engine

/**
 * 일일 징세 설계값. 작성: Peppone Choi.
 * evidence:guess — 성인별 과세 소득과 중앙 납입률은 원작 수치가 없어 호출자가 명시한다.
 * 세율(taxRate)과 납입률(remittanceRate)은 서로 다른 비율이다.
 */
data class PlanetTaxRevenuePolicy(val incomePerAdultPerDay: Int, val remittanceRate: Int) {
    init {
        require(incomePerAdultPerDay >= 0)
        require(remittanceRate in 0..100)
    }
}

/** 한 거점의 하루 수입. 중앙 납입액 + 지방 잔류액은 항상 총 징세액이다. */
data class PlanetTaxRevenueAssessment(
    val taxableIncome: Long,
    val collected: Long,
    val central: Long,
    val local: Long,
) {
    init {
        require(taxableIncome >= 0 && collected in 0..taxableIncome)
        require(central in 0..collected && local == collected - central)
    }
}

/**
 * 저장·시간 진행·CP 결제를 수행하지 않는 계산 경계. 작성: Peppone Choi.
 * evidence:guess — 현재 정규 세력의 일반 진영·거주 가능 행성 성인을 과세 대상으로 삼는다.
 * 군수물자·함선 등의 자동 생산량을 별도 세금으로 차감하지 않는다.
 * 원작 경제 미구현: docs/manual/01-session-time-victory.md (W p.9).
 * 세율/납입률 구분: docs/manual/07-command-table.md, docs/protocol/planet-tax.md.
 */
internal object PlanetTaxRevenue {
    fun assess(stock: PlanetStock, base: BaseCondition, policy: PlanetTaxRevenuePolicy,
        remittanceRate:Int=policy.remittanceRate): PlanetTaxRevenueAssessment {
        require(remittanceRate in 0..100)
        require(stock.baseId == base.baseId) { "Tax condition belongs to a different base" }
        // Old rows must be initialized by PlanetProduction at their existing watermark first.
        // Falling back to seed population here would silently undo recruitment/starvation.
        val people = checkNotNull(stock.economy) { "Tax requires initialized planet economy" }
        if (stock.power !in 2..3 || stock.camp != 0 || base.habitability == 0) {
            return PlanetTaxRevenueAssessment(0, 0, 0, 0)
        }
        val income = people.adults.toLong() * policy.incomePerAdultPerDay
        val collected = percentage(income, PlanetTax.effectiveRate(stock, base))
        val central = percentage(collected, remittanceRate)
        // Keep rounding remainder local so splitting cannot create or discard collected money.
        return PlanetTaxRevenueAssessment(income, collected, central, collected - central)
    }

    // Divide first: income can approach Int.MAX_VALUE squared, making income * rate overflow Long.
    private fun percentage(amount: Long, rate: Int): Long =
        amount / 100 * rate + amount % 100 * rate / 100
}
