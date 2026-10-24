package org.logh7.engine

import org.logh7.engine.strategy.CpBalance
import org.logh7.engine.strategy.CpPayment
import org.logh7.engine.strategy.CpPool
import org.logh7.engine.strategy.PaymentResult
import kotlin.math.abs

/** Local navigation policy where original range, fuel and timing formulas remain unknown. */
data class NavigationPolicy(val initialCp: Int = 1000, val initialCruise: Int = 1000,
    val maximumRange: Int = 3, val warpCost: Int = 40, val cruiseCost: Int = 100) {
    init { require(initialCp >= 0 && initialCruise >= 100 && maximumRange in 1..10 && warpCost >= 0 && cruiseCost >= 0) }
}
data class Navigator(val generation: Long, val characterId: Int, val power: Power, val grid: Int,
    val pcp: Int, val mcp: Int, val cruise: Int, val moves: Long = 0) {
    val unitId: Int get() = Math.addExact(characterId, 1_000_000)
    init { require(generation > 0 && characterId in 1..0x7fefffff && grid in 0..4999 &&
        pcp >= 0 && mcp >= 0 && cruise >= 0 && moves >= 0) }
}
interface NavigationStore : AutoCloseable {
    fun load(): Map<String, Navigator>
    /** Must finish durable replacement before an ACK or a new snapshot is published. */
    fun save(players: Map<String, Navigator>)
    override fun close() {}
}
data class NavigationRequest(val account: String, val characterId: Int, val power: Power,
    val connection: String, val generation: Long, val destination: Int? = null, val unitId: Int? = null,
    val captainCard: Int? = null)
enum class NavigationRejection { DISABLED, SESSION, IDENTITY, UNIT, CARD, SAME_GRID, RANGE, GRID, CRUISE, CP, TACTICAL }
data class NavigationResult(val player: Navigator?, val previous: Navigator? = null, val rejection: NavigationRejection? = null)

/** Called only on the world actor. No input can choose its own identity, balance or origin. */
internal class Navigation(private val galaxy: Galaxy, private val store: NavigationStore?,
    private val policy: NavigationPolicy) {
    private var players = store?.load().orEmpty().toMap()
    fun snapshot(generation: Long) = players.filterValues { it.generation == generation }.toMap()
    fun apply(request: NavigationRequest, session: SessionState): NavigationResult {
        fun reject(reason: NavigationRejection) = NavigationResult(null, rejection = reason)
        val member = session.participants[request.account] ?: return reject(NavigationRejection.SESSION)
        if (session.ended != null || session.generation != request.generation || !member.online || member.connection != request.connection)
            return reject(NavigationRejection.SESSION)
        if (member.power != request.power) return reject(NavigationRejection.IDENTITY)
        val existing = players[request.account]?.takeIf { it.generation == request.generation }
        if (existing != null && (existing.characterId != request.characterId || existing.power != request.power))
            return reject(NavigationRejection.IDENTITY)
        val before = existing ?: Navigator(request.generation, request.characterId, request.power,
            galaxy.starts.getValue(request.power), policy.initialCp, policy.initialCp, policy.initialCruise)
        val destination = request.destination
        if (destination == null) {
            if (existing == null) commit(request.account, before, session.generation)
            return NavigationResult(before)
        }
        if (session.battles.isNotEmpty()) return reject(NavigationRejection.TACTICAL) // No tactical navigation integration yet.
        if (request.unitId != before.unitId) return reject(NavigationRejection.UNIT)
        if (request.captainCard != if (before.power == Power.EMPIRE) 59 else 195) return reject(NavigationRejection.CARD)
        if (destination !in 0..4999) return reject(NavigationRejection.GRID)
        if (destination == before.grid) return reject(NavigationRejection.SAME_GRID)
        val dx = abs(destination % 100 - before.grid % 100)
        val dy = abs(destination / 100 - before.grid / 100)
        if (dx * dx + dy * dy > policy.maximumRange * policy.maximumRange) return reject(NavigationRejection.RANGE)
        val warp = WarpPreconditions.evaluate(WarpContext(before.cruise, galaxy.kind(destination) == GridKind.STAR_SYSTEM,
            maxOf(dx, dy) == 1))
        if (warp != WarpDecision.ALLOWED || before.cruise < policy.cruiseCost) return reject(NavigationRejection.CRUISE)
        val present = snapshot(session.generation).values.filter { it.grid == destination }.groupBy { it.power }
        val entry = GridEntryRules.evaluate(GridState(galaxy.kind(destination), GridTerrain.NONE, false,
            present.map { (power, units) -> CampPresence(Camp(power, false), units.size, false, true, false) }),
            GridEntry(Camp(before.power, false), 1, true))
        if (entry != GridEntryVerdict.ALLOWED) return reject(NavigationRejection.GRID)
        val payment = CpPayment.charge(CpBalance(before.pcp.toLong(), before.mcp.toLong()), policy.warpCost.toLong(), CpPool.MCP)
        if (payment !is PaymentResult.Paid) return reject(NavigationRejection.CP)
        val after = before.copy(grid = destination, pcp = Math.toIntExact(payment.balance.pcp), mcp = Math.toIntExact(payment.balance.mcp),
            cruise = before.cruise - policy.cruiseCost, moves = Math.addExact(before.moves, 1))
        commit(request.account, after, session.generation)
        return NavigationResult(after, before)
    }
    private fun commit(account: String, player: Navigator, generation: Long) {
        val next = snapshot(generation) + (account to player)
        store?.save(next)
        players = next
    }
}
