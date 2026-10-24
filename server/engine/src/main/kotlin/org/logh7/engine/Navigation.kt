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
    val pcp: Int, val mcp: Int, val cruise: Int, val moves: Long = 0,
    val baseId: Int = 0, val mode: Int = 5, val spotId: Int = 0, val spotOwner: Int = 0) {
    val unitId: Int get() = Math.addExact(characterId, 1_000_000)
    init { require(generation > 0 && characterId in 1..0x7fefffff && grid in 0..4999 &&
        pcp >= 0 && mcp >= 0 && cruise >= 0 && moves >= 0 && baseId in 0..65535 && mode in 0..5 &&
        spotId >= 0 && spotOwner >= 0 && (baseId != 0 || (mode == 5 && spotId == 0 && spotOwner == 0))) }
}
sealed interface PlaceMove {
    data class Dock(val baseId: Int, val mode: Int) : PlaceMove
    data class Spot(val id: Int, val owner: Int) : PlaceMove
}
interface NavigationStore : AutoCloseable {
    fun load(): Map<String, Navigator>
    /** Must finish durable replacement before an ACK or a new snapshot is published. */
    fun save(players: Map<String, Navigator>)
    override fun close() {}
}
data class NavigationRequest(val account: String, val characterId: Int, val power: Power,
    val connection: String, val generation: Long, val destination: Int? = null, val unitId: Int? = null,
    val captainCard: Int? = null, val place: PlaceMove? = null, val rank: Int = 20)
enum class NavigationRejection { DISABLED, SESSION, IDENTITY, UNIT, CARD, SAME_GRID, RANGE, GRID, CRUISE, CP, TACTICAL,
    BASE, SPOT, ACCESS, EMBARKED }
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
        if (destination == null && request.place == null) {
            if (existing == null) commit(request.account, before, session.generation)
            return NavigationResult(before)
        }
        if (session.battles.isNotEmpty()) return reject(NavigationRejection.TACTICAL) // No tactical navigation integration yet.
        if (request.place != null) {
            if (destination != null) return reject(NavigationRejection.BASE)
            val result = movePlace(request, before)
            result.player?.let { commit(request.account, it, session.generation) }
            return result
        }
        if (before.baseId != 0 || before.spotId != 0) return reject(NavigationRejection.EMBARKED)
        if (request.unitId != before.unitId) return reject(NavigationRejection.UNIT)
        if (request.captainCard != if (before.power == Power.EMPIRE) 59 else 195) return reject(NavigationRejection.CARD)
        if (destination == null || destination !in 0..4999) return reject(NavigationRejection.GRID)
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
    private fun movePlace(request: NavigationRequest, before: Navigator): NavigationResult {
        fun reject(reason: NavigationRejection) = NavigationResult(null, rejection = reason)
        val ownPower = if (before.power == Power.EMPIRE) 2 else 3
        val after: Navigator
        val cost: Long
        val pool: CpPool
        when (val move = checkNotNull(request.place)) {
            is PlaceMove.Dock -> {
                if (request.unitId != before.unitId) return reject(NavigationRejection.UNIT)
                if (request.captainCard != if (ownPower == 2) 59 else 195) return reject(NavigationRejection.CARD)
                if (before.spotId != 0) return reject(NavigationRejection.EMBARKED)
                if (move.baseId == 0) {
                    if (move.mode != 5 || before.baseId == 0) return reject(NavigationRejection.BASE)
                } else {
                    val base = galaxy.conditions[move.baseId] ?: return reject(NavigationRejection.BASE)
                    if (base.grid != before.grid || move.mode != 4) return reject(NavigationRejection.BASE)
                    if (base.power != ownPower || base.camp != 0) return reject(NavigationRejection.ACCESS)
                    if (before.baseId == move.baseId && before.mode == move.mode) return reject(NavigationRejection.BASE)
                }
                after = before.copy(baseId = move.baseId, mode = move.mode, spotId = 0, spotOwner = 0)
                cost = if (move.baseId == 0) 0 else 160 // W p.68 intra-system navigation; timing integration is pending.
                pool = CpPool.MCP
            }
            is PlaceMove.Spot -> {
                if (before.baseId == 0) return reject(NavigationRejection.BASE)
                val facilities = galaxy.facilities.getValue(before.baseId)
                val target = facilities.singleOrNull { f -> f.spots.any { it.id == move.id } }
                if (move.id == 0) {
                    if (move.owner != before.characterId || before.spotId == 0) return reject(NavigationRejection.SPOT)
                } else {
                    val spot = target?.spots?.single { it.id == move.id } ?: return reject(NavigationRejection.SPOT)
                    if (move.id == before.spotId && move.owner == before.spotOwner) return reject(NavigationRejection.SPOT)
                    val allowed = when (spot.access) {
                        SpotAccess.CLOSED -> false
                        SpotAccess.PUBLIC -> move.owner == 0
                        SpotAccess.GUARDED -> move.owner == 0 && galaxy.conditions.getValue(before.baseId).power == ownPower
                        SpotAccess.RANK -> move.owner == 0 && request.rank <= if (spot.kind == 10) 8 else 13
                        SpotAccess.PRIVATE -> spot.kind in setOf(6, 13) && move.owner == before.characterId
                    }
                    if (!allowed) return reject(NavigationRejection.ACCESS)
                }
                val origin = facilities.singleOrNull { f -> f.spots.any { it.id == before.spotId } }
                cost = if (target != null && origin?.id == target.id) 5 else 10 // W p.69
                pool = CpPool.PCP
                after = before.copy(spotId = move.id, spotOwner = if (move.id == 0) 0 else move.owner)
            }
        }
        val payment = CpPayment.charge(CpBalance(before.pcp.toLong(), before.mcp.toLong()), cost, pool)
        if (payment !is PaymentResult.Paid) return reject(NavigationRejection.CP)
        return NavigationResult(after.copy(pcp = Math.toIntExact(payment.balance.pcp), mcp = Math.toIntExact(payment.balance.mcp),
            moves = Math.addExact(before.moves, 1)), before)
    }
    private fun commit(account: String, player: Navigator, generation: Long) {
        val next = snapshot(generation) + (account to player)
        store?.save(next)
        players = next
    }
}
