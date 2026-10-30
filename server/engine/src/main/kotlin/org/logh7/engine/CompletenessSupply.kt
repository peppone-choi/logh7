package org.logh7.engine

import org.logh7.engine.strategy.*

data class CompletenessSupplyRequest(val account: String, val connection: String, val characterId: Int,
    val power: Power, val generation: Long, val from: Int, val to: Int,
    val politics: Int = 50, val administration: Int = 50)

data class CompletenessSupplyResult(val player: Navigator?, val stocks: PlanetStocks?,
    val rejection: LogisticsRejection? = null, val transferred: Int = 0,
    val recovery: NavigationResult? = null) {
    val changed: Boolean get() = transferred > 0
}

/** Manual command table: MCP160, no wait/duration. Native 004b4da0 identifies two outfits.
 * Same-base bulk transfer and partial delivery when cargo runs out are designed policy until
 * moving-outfit logistics is connected. Individual flagship cargo is a separate pending task.
 * Author: Peppone Choi.
 */
internal object CompletenessSupply {
    fun evaluate(r: CompletenessSupplyRequest, session: SessionState, player: Navigator?,
        stocks: PlanetStocks): CompletenessSupplyResult {
        fun reject(reason: LogisticsRejection) = CompletenessSupplyResult(player, stocks, reason)
        val member = session.participants[r.account]
        if (session.ended != null || session.generation != r.generation || stocks.generation != r.generation ||
            member == null || !member.online || member.connection != r.connection) return reject(LogisticsRejection.SESSION)
        if (player == null || player.generation != r.generation || player.characterId != r.characterId ||
            player.power != r.power || member.power != r.power) return reject(LogisticsRejection.IDENTITY)
        val from = stocks.outfits[r.from] ?: return reject(LogisticsRejection.OUTFIT)
        val to = stocks.outfits[r.to] ?: return reject(LogisticsRejection.OUTFIT)
        if (from.id == to.id) return reject(LogisticsRejection.OUTFIT)
        val power = if (r.power == Power.EMPIRE) 2 else 3
        if (from.power != power || to.power != power || from.kind != OutfitKind.TRANSPORT || from.commanderId != r.characterId)
            return reject(LogisticsRejection.ACCESS)
        val base = stocks.bases.getValue(from.baseId)
        if (base.power != power || base.camp != 0 || from.baseId != to.baseId || player.baseId != from.baseId ||
            (from.position?.baseId ?: from.baseId) != from.baseId || (to.position?.baseId ?: to.baseId) != to.baseId)
            return reject(LogisticsRejection.LOCATION)
        if (player.cpPaused || player.fuelReadyAt != null || player.dockTarget != null)
            return reject(LogisticsRejection.BUSY)
        // Native result arrays have hard limits; never truncate a successful transaction.
        val sourceUnits = from.activeShips?.values ?: return reject(LogisticsRejection.STOCK)
        val targetUnits = to.activeShips?.values ?: return reject(LogisticsRejection.STOCK)
        if (sourceUnits.size > 26 || targetUnits.size > 70) return reject(LogisticsRejection.STOCK)
        val maximum = OutfitSupplies.capacity(to)
        if (to.supplies > maximum || from.supplies > OutfitSupplies.capacity(from))
            return reject(LogisticsRejection.STOCK)
        val amount = minOf(from.supplies, maximum - to.supplies)
        if (amount == 0) return CompletenessSupplyResult(player, stocks)
        val paid = CpPayment.charge(CpBalance(player.pcp.toLong(), player.mcp.toLong()), 160, CpPool.MCP)
        if (paid !is PaymentResult.Paid) return reject(LogisticsRejection.CP)
        val after = player.copy(pcp = paid.balance.pcp.toInt(), mcp = paid.balance.mcp.toInt())
        val next = stocks.copy(outfits = stocks.outfits + mapOf(
            from.id to from.copy(supplies = from.supplies - amount),
            to.id to to.copy(supplies = Math.addExact(to.supplies, amount))))
        return CompletenessSupplyResult(after, next, transferred = amount)
    }
}
