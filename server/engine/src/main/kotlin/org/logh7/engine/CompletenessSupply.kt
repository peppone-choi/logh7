package org.logh7.engine

import org.logh7.engine.strategy.*

data class CompletenessSupplyRequest(val account: String, val connection: String, val characterId: Int,
    val country: CountryId?, val generation: Long, val from: Int, val to: Int,
    val politics: Int = 50, val administration: Int = 50, val expected: SupplyCost? = null) {
    constructor(account:String,connection:String,characterId:Int,power:Power,generation:Long,from:Int,to:Int,
        politics:Int=50,administration:Int=50):this(account,connection,characterId,power.country,generation,from,to,politics,administration)
}

data class SupplyCost(val amount: Int, val pool: CpPool)

data class CompletenessSupplyQuote(val amount: Int = 0, val payment: PaymentResult.Paid? = null,
    val rejection: LogisticsRejection? = null) {
    val cost: SupplyCost? get() = payment?.chargedPool?.let { SupplyCost(amount, it) }
}

data class CompletenessSupplyResult(val player: Navigator?, val stocks: PlanetStocks?,
    val rejection: LogisticsRejection? = null, val transferred: Int = 0,
    val recovery: NavigationResult? = null, val payment: PaymentResult.Paid? = null, val source: Int = 0) {
    val changed: Boolean get() = transferred > 0
}

/** Manual command table: MCP160, no wait/duration. Native 004b4da0 identifies two outfits.
 * Same-base bulk transfer and partial delivery when cargo runs out are designed policy until
 * moving-outfit logistics is connected. Individual flagship cargo is a separate pending task.
 * Author: Peppone Choi.
 */
object CompletenessSupply {
    /** Cargo and CP rules shared by the authorized directory and by the committing world actor. */
    fun quote(player: Navigator, from: OutfitStock, to: OutfitStock, stocks: PlanetStocks): CompletenessSupplyQuote {
        if (player.cpPaused || player.navigationPending || stocks.social.justice.detained(player.characterId))
            return CompletenessSupplyQuote(rejection = LogisticsRejection.BUSY)
        val sourceUnits = from.activeShips?.values
        val targetUnits = to.activeShips?.values
        val maximum = OutfitSupplies.capacity(to)
        // A successful transaction must fit the original result arrays.
        if (sourceUnits == null || targetUnits == null || sourceUnits.size > 26 || targetUnits.size > 70 ||
            to.supplies > maximum || from.supplies > OutfitSupplies.capacity(from))
            return CompletenessSupplyQuote(rejection = LogisticsRejection.STOCK)
        val cargo = TransportActions.carriedCargo(to, stocks.strategic.transports).sumOf { it.first.toLong() }
        val amount = minOf(from.supplies.toLong(), maximum - to.supplies - cargo).coerceAtLeast(0).toInt()
        if (amount == 0) return CompletenessSupplyQuote()
        val paid = CpPayment.charge(CpBalance(player.pcp.toLong(), player.mcp.toLong()), 160, CpPool.MCP)
        return if (paid is PaymentResult.Paid) CompletenessSupplyQuote(amount, paid)
            else CompletenessSupplyQuote(amount, rejection = LogisticsRejection.CP)
    }

    fun evaluate(r: CompletenessSupplyRequest, session: SessionState, player: Navigator?,
        stocks: PlanetStocks): CompletenessSupplyResult {
        fun reject(reason: LogisticsRejection) = CompletenessSupplyResult(player, stocks, reason)
        val member = session.participants[r.account]
        if (session.ended != null || r.country in session.eliminated || session.generation != r.generation || stocks.generation != r.generation ||
            member == null || !member.online || member.connection != r.connection) return reject(LogisticsRejection.SESSION)
        if (player == null || player.generation != r.generation || player.characterId != r.characterId ||
            player.country != r.country || member.country != r.country) return reject(LogisticsRejection.IDENTITY)
        if (r.country == null) return reject(LogisticsRejection.ACCESS)
        val camp = TacticalAuthority.camp(player, stocks)
        // The CD sends zero if the actor's current outfit is ambiguous among their offices.
        val from = if (r.from != 0) stocks.outfits[r.from] else stocks.outfits.values.singleOrNull {
            TacticalAuthority.camp(it, stocks) == camp && it.kind == OutfitKind.TRANSPORT && it.commanderId == r.characterId &&
                (it.position?.baseId ?: it.baseId) == player.baseId
        }
        if (from == null) return reject(LogisticsRejection.OUTFIT)
        val to = stocks.outfits[r.to] ?: return reject(LogisticsRejection.OUTFIT)
        if (from.id == to.id) return reject(LogisticsRejection.OUTFIT)
        if (TacticalAuthority.camp(from, stocks) != camp || TacticalAuthority.camp(to, stocks) != camp || from.kind != OutfitKind.TRANSPORT || from.commanderId != r.characterId)
            return reject(LogisticsRejection.ACCESS)
        val base = stocks.bases[player.baseId]
        if (base?.let(TacticalAuthority::camp) != camp ||
            (from.position?.baseId ?: from.baseId) != player.baseId || (to.position?.baseId ?: to.baseId) != player.baseId)
            return reject(LogisticsRejection.LOCATION)
        val quote = quote(player, from, to, stocks)
        quote.rejection?.let { return reject(it) }
        if (r.expected != null && r.expected != quote.cost) return reject(LogisticsRejection.QUOTE_CHANGED)
        val paid = quote.payment ?: return CompletenessSupplyResult(player, stocks, source = from.id)
        val amount = quote.amount
        val after = player.paidWith(paid.balance)
        val next = stocks.copy(outfits = stocks.outfits + mapOf(
            from.id to from.copy(supplies = from.supplies - amount),
            to.id to to.copy(supplies = Math.addExact(to.supplies, amount))))
        return CompletenessSupplyResult(after, next, transferred = amount, payment = paid, source = from.id)
    }
}
