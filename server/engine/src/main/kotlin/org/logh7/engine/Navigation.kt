package org.logh7.engine

import org.logh7.engine.strategy.*
import kotlin.math.abs

/** Local navigation policy where original range, fuel and timing formulas remain unknown. */
data class NavigationPolicy(val initialCp: Int = 1000, val initialCruise: Int = 1000,
    val maximumRange: Int = 3, val warpCost: Int = 40, val cruiseCost: Int = 100,
    val maximumCp: Int = 1000, val recoveryBase: Int = 50,
    val maximumCruise: Int = 1000, val fuelSupplyCost: Int = 160) {
    init { require(initialCp in 0..maximumCp && initialCruise >= 100 && maximumRange in 1..10 &&
        warpCost >= 0 && cruiseCost >= 0 && maximumCp > 0 && recoveryBase >= 0 &&
        maximumCruise >= initialCruise && fuelSupplyCost == 160) }
}
data class Navigator(val generation: Long, val characterId: Int, val power: Power, val grid: Int,
    val pcp: Int, val mcp: Int, val cruise: Int, val moves: Long = 0,
    val baseId: Int = 0, val mode: Int = 5, val spotId: Int = 0, val spotOwner: Int = 0,
    val cpRecoveryAt: Long? = null, val cpPaused: Boolean = false,
    val fuelReadyAt: Long? = null, val strategy: StrategyCheckpoint? = null, val dockTarget: Int? = null,
    val returnBase: Int = 0, val energy: TacticalEnergy? = null, val motion: TacticalMotion? = null) {
    val unitId: Int get() = Math.addExact(characterId, 1_000_000)
    val dockReadyAt: Long? get() = if (dockTarget == null) null else strategy!!.jobs.single().completesAtGameMillis / 1000
    init { require(generation > 0 && characterId in 1..0x7fefffff && grid in 0..4999 &&
        pcp >= 0 && mcp >= 0 && cruise >= 0 && moves >= 0 && baseId in 0..65535 && mode in 0..5 &&
        spotId >= 0 && spotOwner >= 0 && returnBase in 0..65535 &&
        (motion == null || (motion.grid == grid && cpPaused && baseId == 0 && mode == 5 &&
            motion.velocity <= (energy ?: TacticalEnergy.ZERO).values[0] / 100f)) &&
        (cpRecoveryAt == null || cpRecoveryAt in 0..0xffffffffL) &&
        (fuelReadyAt == null || fuelReadyAt in 0..0xffffffffL) &&
        (dockTarget == null || dockTarget in 1..65535 && strategy != null && fuelReadyAt == null) &&
        (strategy == null || (strategy.jobs.size <= 1 && strategy.jobs.isEmpty() == (fuelReadyAt == null && dockTarget == null) && strategy.jobs.all {
            it.state != JobState.COMPLETED && it.completesAtGameMillis % 1000 == 0L &&
                it.completesAtGameMillis / 1000 in 0..0xffffffffL && when (it.command) {
                    NavigationWork.FUEL -> dockTarget == null && fuelReadyAt == it.completesAtGameMillis / 1000
                    NavigationWork.DOCK -> dockTarget != null && fuelReadyAt == null && it.startsAtGameMillis == it.completesAtGameMillis
                    else -> false
                } })) &&
        (baseId != 0 || (mode == 5 && spotId == 0 && spotOwner == 0))) }
}
sealed interface PlaceMove {
    data class Dock(val baseId: Int, val mode: Int) : PlaceMove
    data class Spot(val id: Int, val owner: Int) : PlaceMove
    data class SwitchMode(val mode: Int) : PlaceMove
}
interface NavigationStore : AutoCloseable {
    fun load(): Map<String, Navigator>
    /** Must finish durable replacement before an ACK or a new snapshot is published. */
    fun save(players: Map<String, Navigator>)
    override fun close() {}
}
data class NavigationRequest(val account: String, val characterId: Int, val power: Power,
    val connection: String, val generation: Long, val destination: Int? = null, val unitId: Int? = null,
    val captainCard: Int? = null, val place: PlaceMove? = null, val rank: Int = 20,
    val politics: Int = 50, val administration: Int = 50, val tactical: Boolean? = null,
    val supplyFuel: Boolean = false, val returnBase: Int? = null,
    val energy: TacticalEnergy? = null, val shipKind: Int? = null, val tacticalMove: TacticalMove? = null,
    val tacticalStop: TacticalStop? = null) {
    init { require(politics in 0..65535 && administration in 0..65535 &&
        (tactical == null || (destination == null && place == null && !supplyFuel)) &&
        (!supplyFuel || (destination == null && place == null)) &&
        (returnBase == null || (returnBase in 0..65535 && destination == null && place == null && !supplyFuel && tactical == null)) &&
        (energy == null || (destination == null && place == null && !supplyFuel && returnBase == null && tactical == null && shipKind != null)) &&
        (tacticalMove == null || (destination == null && place == null && !supplyFuel && returnBase == null && tactical == null && energy == null && shipKind != null)) &&
        (tacticalStop == null || (destination == null && place == null && !supplyFuel && returnBase == null && tactical == null && energy == null && tacticalMove == null && shipKind != null))) }
}
enum class NavigationRejection { DISABLED, SESSION, IDENTITY, UNIT, CARD, SAME_GRID, RANGE, GRID, CRUISE, CP, TACTICAL,
    BASE, SPOT, ACCESS, EMBARKED, SUPPLYING, FUEL_FULL, CLOCK, TRAVELLING, BUSY, ENERGY }
data class NavigationResult(val player: Navigator?, val previous: Navigator? = null, val rejection: NavigationRejection? = null,
    val completedFuel: Boolean = false, val completedDock: Boolean = false)

/** Called only on the world actor. No input can choose its own identity, balance or origin. */
internal class Navigation(private val galaxy: Galaxy, private val store: NavigationStore?,
    private val policy: NavigationPolicy, private val cpIntervalSeconds: Long = 7200) {
    init { require(cpIntervalSeconds in 1..86400) }
    private var players = store?.load().orEmpty().toMap()
    fun snapshot(generation: Long) = players.filterValues { it.generation == generation }.toMap()
    fun apply(request: NavigationRequest, session: SessionState, stocks: PlanetStocks? = null,
        persist: Boolean = true, movingUnits: Int = 1, soloShip: Boolean = true): NavigationResult {
        require(stocks == null || stocks.generation == session.generation)
        var current: Navigator? = null
        var completedFuel = false
        var completedDock = false
        fun reject(reason: NavigationRejection) = NavigationResult(null, previous = current, rejection = reason,
            completedFuel = completedFuel, completedDock = completedDock)
        val member = session.participants[request.account] ?: return reject(NavigationRejection.SESSION)
        if (session.ended != null || session.generation != request.generation || !member.online || member.connection != request.connection)
            return reject(NavigationRejection.SESSION)
        if (member.power != request.power) return reject(NavigationRejection.IDENTITY)
        val existing = players[request.account]?.takeIf { it.generation == request.generation }
        if (existing != null && (existing.characterId != request.characterId || existing.power != request.power))
            return reject(NavigationRejection.IDENTITY)
        val initial = existing ?: Navigator(request.generation, request.characterId, request.power,
            galaxy.starts.getValue(request.power), policy.initialCp, policy.initialCp, policy.initialCruise)
        val now = session.gameMillis / 1000
        val tactical = request.tactical ?: if(stocks == null) initial.cpPaused else
            TacticalContact.active(galaxy,initial,snapshot(session.generation).values,stocks)
        val ready = if(initial.cpPaused == tactical) initial else initial.copy(cpPaused=tactical,cpRecoveryAt=now,
            motion=if(tactical)initial.motion else null)
        val fueled = settleWork(ready, now)
        completedFuel = initial.fuelReadyAt != null && fueled.fuelReadyAt == null
        completedDock = initial.dockTarget != null && fueled.dockTarget == null
        val recovered = recover(fueled, request, now)
        val before = recovered
        fun contact(player: Navigator): Navigator {
            val active = if(stocks == null) player.cpPaused else
                TacticalContact.active(galaxy,player,snapshot(session.generation).values,stocks)
            return if(active == player.cpPaused) player else player.copy(cpPaused=active,cpRecoveryAt=now,
                motion=if(active)player.motion else null)
        }
        if (persist && existing != before) commit(request.account, before, session.generation)
        current = before
        request.tacticalStop?.let { stop ->
            if (!before.cpPaused) return reject(NavigationRejection.TACTICAL)
            // Fleet/base authority is not yet implemented. Never silently stop another commander's units.
            if (stop.units != listOf(before.unitId) || stop.bases.isNotEmpty()) return reject(NavigationRejection.UNIT)
            if (request.captainCard != if (before.power == Power.EMPIRE) 59 else 195) return reject(NavigationRejection.CARD)
            val ship = Flagships.find(checkNotNull(request.shipKind)) ?: return reject(NavigationRejection.UNIT)
            if (ship.power != if (before.power == Power.EMPIRE) 2 else 3) return reject(NavigationRejection.UNIT)
            if (now > 0xffffffffL - TacticalMotion.WAIT_SECONDS) return reject(NavigationRejection.CLOCK)
            val pose = before.motion?.pose(now) ?: TacticalDeployment.initial(before.unitId, before.power, before.grid,
                snapshot(session.generation).values + before, stocks, galaxy)
            // Retain an authoritative stationary pose; null would restore the initial deployment on reconnect.
            val after = before.copy(motion = TacticalMotion(before.grid, now, pose, 0f, pose.direction, listOf(pose.position)))
            if (persist) commit(request.account, after, session.generation)
            return NavigationResult(after, before)
        }
        request.tacticalMove?.let { move ->
            if (!before.cpPaused) return reject(NavigationRejection.TACTICAL)
            if (move.units != listOf(before.unitId)) return reject(NavigationRejection.UNIT)
            if (request.captainCard != if (before.power == Power.EMPIRE) 59 else 195) return reject(NavigationRejection.CARD)
            val ship = Flagships.find(checkNotNull(request.shipKind)) ?: return reject(NavigationRejection.UNIT)
            if (ship.power != if (before.power == Power.EMPIRE) 2 else 3) return reject(NavigationRejection.UNIT)
            val maximum = (before.energy ?: TacticalEnergy.ZERO).values[0] / 100f
            if (move.velocity > maximum) return reject(NavigationRejection.ENERGY)
            if (now > 0xffffffffL - TacticalMotion.WAIT_SECONDS) return reject(NavigationRejection.CLOCK)
            val origin = before.motion?.takeIf { it.grid == before.grid }?.pose(now) ?:
                TacticalDeployment.initial(before.unitId, before.power, before.grid,
                    snapshot(session.generation).values + before, stocks, galaxy)
            val after = before.copy(motion = TacticalMotion(before.grid, now, origin, move.velocity, move.direction, move.waypoints))
            if (persist) commit(request.account, after, session.generation)
            return NavigationResult(after, before)
        }
        request.energy?.let { energy ->
            if (!before.cpPaused) return reject(NavigationRejection.TACTICAL)
            if (request.unitId != before.unitId) return reject(NavigationRejection.UNIT)
            if (request.captainCard != if (before.power == Power.EMPIRE) 59 else 195) return reject(NavigationRejection.CARD)
            val ship = Flagships.find(checkNotNull(request.shipKind)) ?: return reject(NavigationRejection.UNIT)
            if (ship.power != if (before.power == Power.EMPIRE) 2 else 3) return reject(NavigationRejection.UNIT)
            if (energy.allocated > ship.number("出力:出力")) return reject(NavigationRejection.ENERGY)
            val motion = before.motion?.let { motion ->
                val maximum = energy.values[0] / 100f
                if (motion.grid != before.grid || motion.velocity <= maximum) motion else motion.copy(
                    acceptedAt = maxOf(now, motion.startsAt) - TacticalMotion.WAIT_SECONDS,
                    origin = motion.pose(now), velocity = maximum, waypoints = motion.remaining(now))
            }
            val after = before.copy(energy = energy, motion = motion)
            if (persist && after != before) commit(request.account, after, session.generation)
            return NavigationResult(after, before)
        }
        request.returnBase?.let { target ->
            if (target != 0) {
                val condition = galaxy.conditions[target] ?: return reject(NavigationRejection.BASE)
                if (condition.habitability == 0) return reject(NavigationRejection.BASE)
                val stock = stocks?.bases?.get(target)
                if (stocks != null && stock == null) return reject(NavigationRejection.BASE)
                val power = stock?.power ?: condition.power
                val camp = stock?.camp ?: condition.camp
                if (power != (if (before.power == Power.EMPIRE) 2 else 3) || camp != 0)
                    return reject(NavigationRejection.ACCESS)
            }
            val after = before.copy(returnBase = target)
            if (persist && after != before) commit(request.account, after, session.generation)
            return NavigationResult(after, before, completedFuel = completedFuel, completedDock = completedDock)
        }
        val destination = request.destination
        if (destination == null && request.place == null && !request.supplyFuel) {
            return NavigationResult(before, existing, completedFuel = completedFuel, completedDock = completedDock)
        }
        if (before.cpPaused) return reject(NavigationRejection.TACTICAL)
        if (before.fuelReadyAt != null) return reject(NavigationRejection.SUPPLYING)
        if (before.dockTarget != null) return reject(NavigationRejection.TRAVELLING)
        if (request.supplyFuel) {
            if (request.unitId != before.unitId) return reject(NavigationRejection.UNIT)
            if (request.captainCard != if (before.power == Power.EMPIRE) 59 else 195) return reject(NavigationRejection.CARD)
            if (before.baseId != 0 || before.spotId != 0 || before.mode != 5) return reject(NavigationRejection.EMBARKED)
            if (before.cruise >= policy.maximumCruise) return reject(NavigationRejection.FUEL_FULL)
            // W68: 48..960 hours. The missing-fuel interpolation is a local policy.
            val hours = maxOf(48L, ((policy.maximumCruise - before.cruise).toLong() * 960 + policy.maximumCruise - 1) / policy.maximumCruise)
            val pipeline = NavigationWork.pipeline(before, policy, hours)
            val submitted = pipeline.submit(NavigationWork.FUEL, listOf(NavigationWork.CAPTAIN), now * 1000, before.cpPaused)
            if (submitted is SubmissionResult.Rejected) return reject(if (submitted.reason == StrategyRejection.TIME_REVERSED)
                NavigationRejection.CLOCK else NavigationRejection.CP)
            val job = (submitted as SubmissionResult.Accepted).job
            if (job.completesAtGameMillis / 1000 > 0xffffffffL) return reject(NavigationRejection.CLOCK)
            val after = before.copy(pcp = Math.toIntExact(pipeline.balance.pcp), mcp = Math.toIntExact(pipeline.balance.mcp),
                fuelReadyAt = job.completesAtGameMillis / 1000, strategy = pipeline.checkpoint())
            if (persist) commit(request.account, after, session.generation)
            return NavigationResult(after, before, completedFuel = completedFuel, completedDock = completedDock)
        }
        if (request.place != null) {
            if (destination != null) return reject(NavigationRejection.BASE)
            val moved = movePlace(request, before, now)
            val result = moved.copy(player=moved.player?.let(::contact))
            if (persist) result.player?.let { commit(request.account, it, session.generation) }
            return result.copy(completedFuel = completedFuel, completedDock = completedDock)
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
        val forces = GridForces.collect(galaxy, session.generation, snapshot(session.generation).values, stocks)
        val entry = GridEntryRules.evaluate(GridForces.state(galaxy, destination, forces),
            GridEntry(Camp(before.power, false), movingUnits, soloShip))
        if (entry != GridEntryVerdict.ALLOWED) return reject(NavigationRejection.GRID)
        val payment = CpPayment.charge(CpBalance(before.pcp.toLong(), before.mcp.toLong()), policy.warpCost.toLong(), CpPool.MCP)
        if (payment !is PaymentResult.Paid) return reject(NavigationRejection.CP)
        val after = contact(before.copy(grid = destination, pcp = Math.toIntExact(payment.balance.pcp), mcp = Math.toIntExact(payment.balance.mcp),
            cruise = before.cruise - policy.cruiseCost, moves = Math.addExact(before.moves, 1), motion = null))
        if (persist) commit(request.account, after, session.generation)
        return NavigationResult(after, before, completedFuel = completedFuel, completedDock = completedDock)
    }
    private fun settleWork(player: Navigator, now: Long): Navigator {
        if (player.cpPaused) return player
        val checkpoint = player.strategy
        if (checkpoint == null) { // Preserve deadlines already acknowledged by pre-job servers.
            return if (player.fuelReadyAt?.let { now >= it } == true)
                player.copy(cruise = maxOf(player.cruise, policy.maximumCruise), fuelReadyAt = null) else player
        }
        if (checkpoint.jobs.isEmpty() || now * 1000 < checkpoint.lastGameMillis) return player
        val pipeline = NavigationWork.pipeline(player, policy)
        val events = pipeline.advance(now * 1000)
        if (events.isEmpty()) return player
        val completed = events.any { it.kind == JobEventKind.COMPLETED }
        return player.copy(strategy = pipeline.checkpoint(dropCompleted = true),
            cruise = if (completed && player.fuelReadyAt != null) maxOf(player.cruise, policy.maximumCruise) else player.cruise,
            fuelReadyAt = if (completed) null else player.fuelReadyAt,
            baseId = if (completed && player.dockTarget != null) player.dockTarget else player.baseId,
            mode = if (completed && player.dockTarget != null) 4 else player.mode,
            moves = if (completed && player.dockTarget != null) Math.addExact(player.moves, 1) else player.moves,
            dockTarget = if (completed) null else player.dockTarget)
    }
    /** W27 fixes the interval and offline recovery. Cap and ability formula are provisional. */
    private fun recover(player: Navigator, request: NavigationRequest, now: Long): Navigator {
        val last = player.cpRecoveryAt
        // Legacy files have no trustworthy recovery time. Start a clock without inventing a backlog.
        if (last == null || now < last || player.cpPaused) return player.copy(cpRecoveryAt = now)
        val periods = (now - last) / cpIntervalSeconds
        if (periods == 0L) return player
        fun balance(current: Int, ability: Int) = if (current >= policy.maximumCp) current else
            minOf(policy.maximumCp.toLong(), current.toLong() + periods * (policy.recoveryBase.toLong() + ability / 2)).toInt()
        return player.copy(pcp = balance(player.pcp, request.politics), mcp = balance(player.mcp, request.administration),
            cpRecoveryAt = last + periods * cpIntervalSeconds)
    }
    private fun movePlace(request: NavigationRequest, before: Navigator, now: Long): NavigationResult {
        fun reject(reason: NavigationRejection) = NavigationResult(null, previous = before, rejection = reason)
        val ownPower = if (before.power == Power.EMPIRE) 2 else 3
        val after: Navigator
        val cost: Long
        val pool: CpPool
        when (val move = checkNotNull(request.place)) {
            is PlaceMove.SwitchMode -> {
                if (request.unitId != before.unitId) return reject(NavigationRejection.UNIT)
                if (request.captainCard != if (ownPower == 2) 59 else 195) return reject(NavigationRejection.CARD)
                if (move.mode != 5 || before.baseId == 0 || before.mode != 4) return reject(NavigationRejection.BASE)
                val base = galaxy.conditions[before.baseId] ?: return reject(NavigationRejection.BASE)
                if (base.grid != before.grid || base.power != ownPower || base.camp != 0) return reject(NavigationRejection.ACCESS)
                // The CD departs from its flagship quay. A public lobby or another
                // person's private room must never move this character's ship.
                if (before.spotId != 0 && (before.spotOwner != 0 ||
                    galaxy.facilities.getValue(before.baseId).none { facility -> facility.kind == 4 &&
                        facility.spots.any { it.id == before.spotId && it.kind == 6 } }))
                    return reject(NavigationRejection.EMBARKED)
                after = before.copy(baseId = 0, mode = 5, spotId = 0, spotOwner = 0)
                cost = 0; pool = CpPool.MCP // Same provisional departure policy as the existing Dock(0, 5).
            }
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
                    val pipeline = NavigationWork.pipeline(before, policy)
                    val submitted = pipeline.submit(NavigationWork.DOCK, listOf(NavigationWork.CAPTAIN), now * 1000, before.cpPaused)
                    if (submitted is SubmissionResult.Rejected) return reject(if (submitted.reason == StrategyRejection.TIME_REVERSED)
                        NavigationRejection.CLOCK else NavigationRejection.CP)
                    val job = (submitted as SubmissionResult.Accepted).job
                    if (job.completesAtGameMillis / 1000 > 0xffffffffL) return reject(NavigationRejection.CLOCK)
                    return NavigationResult(before.copy(dockTarget = move.baseId, strategy = pipeline.checkpoint(),
                        pcp = Math.toIntExact(pipeline.balance.pcp), mcp = Math.toIntExact(pipeline.balance.mcp)), before)
                }
                after = before.copy(baseId = move.baseId, mode = move.mode, spotId = 0, spotOwner = 0)
                cost = 0 // Departure retains its existing explicit local policy.
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
                        SpotAccess.PRIVATE -> when (spot.kind) {
                            // CD room picker sends owner 0 for the quay template. Its owner is
                            // resolved from the authenticated flagship, never from a client ID.
                            6 -> move.owner == 0 && request.unitId == before.unitId &&
                                galaxy.conditions.getValue(before.baseId).power == ownPower
                            13 -> move.owner == before.characterId
                            else -> false
                        }
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
    fun publishCommitted(account: String, expected: Navigator, next: Navigator) {
        check(players[account] == expected)
        players = snapshot(next.generation) + (account to next)
    }
    fun publishCommitted(expected: Map<String, Navigator>, next: Map<String, Navigator>, generation: Long) {
        check(snapshot(generation) == expected)
        require(next.values.all { it.generation == generation })
        players = next.toMap()
    }
}
