package org.logh7.engine

import org.logh7.engine.strategy.*

data class TaxRequest(val account: String, val connection: String, val characterId: Int, val power: Power,
    val generation: Long, val base: Int, val rate: Int, val card: Int, val mode: Int = 0,
    val politics: Int = 50, val administration: Int = 50) {
    init { require(characterId > 0 && generation > 0 && base in 1..65535 && rate in 0..100 && card in 0..65535 &&
        mode in 0..255 && politics in 0..65535 && administration in 0..65535) }
}
enum class TaxRejection { SESSION, IDENTITY, ACCESS, CARD, BASE, LOCATION, BUSY, CP, MODE, STORAGE }
data class TaxResult(val player: Navigator?, val stocks: PlanetStocks?, val rejection: TaxRejection? = null,
    val changed: Boolean = false, val recovery: NavigationResult? = null)

/** Civil planetary offices; capital administrator tax authority is restoration policy. Author: 최병호. */
internal object PlanetTax {
    const val COST = 160 // Original constmsg group 0, entry 97: MCP 160, no wait or duration.
    fun effectiveRate(stock: PlanetStock, base: BaseCondition): Int {
        require(stock.baseId == base.baseId)
        return stock.taxRate ?: base.tax
    }
    fun evaluate(galaxy: Galaxy, request: TaxRequest, session: SessionState, player: Navigator?, stocks: PlanetStocks): TaxResult {
        fun reject(reason: TaxRejection) = TaxResult(player, stocks, reason)
        val participant = session.participants[request.account] ?: return reject(TaxRejection.SESSION)
        if (session.ended != null || session.generation != request.generation || stocks.generation != request.generation ||
            !participant.online || participant.connection != request.connection) return reject(TaxRejection.SESSION)
        if (player == null || player.generation != request.generation || player.characterId != request.characterId ||
            player.power != request.power || participant.power != request.power) return reject(TaxRejection.IDENTITY)
        if (request.mode != 0) return reject(TaxRejection.MODE)
        val power = if (request.power == Power.EMPIRE) 2 else 3
        if (request.card !in if (power == 2) setOf(54) else setOf(190,192)) return reject(TaxRejection.CARD)
        val base = stocks.bases[request.base] ?: return reject(TaxRejection.BASE)
        if (base.power != power || base.camp != 0 || BaseOfficePolicy(galaxy).holders(base)[request.card] != request.characterId)
            return reject(TaxRejection.ACCESS)
        val seed = galaxy.conditions[base.baseId] ?: return reject(TaxRejection.BASE)
        if (seed.habitability == 0) return reject(TaxRejection.BASE)
        if (player.baseId != base.baseId) return reject(TaxRejection.LOCATION)
        if (player.cpPaused || player.fuelReadyAt != null || player.dockTarget != null) return reject(TaxRejection.BUSY)
        if (request.rate == effectiveRate(base, seed)) return TaxResult(player, stocks)
        val payment = CpPayment.charge(CpBalance(player.pcp.toLong(), player.mcp.toLong()), COST.toLong(), CpPool.MCP)
        if (payment !is PaymentResult.Paid) return reject(TaxRejection.CP)
        return TaxResult(player.copy(pcp = Math.toIntExact(payment.balance.pcp), mcp = Math.toIntExact(payment.balance.mcp)),
            stocks.copy(bases = stocks.bases + (base.baseId to base.copy(taxRate = request.rate))), changed = true)
    }
}
