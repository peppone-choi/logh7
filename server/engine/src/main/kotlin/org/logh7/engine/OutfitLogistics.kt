package org.logh7.engine

enum class LogisticsMode { ALLOCATION, REORGANIZATION }
enum class LogisticsPhase { PREPARE, COMMIT, CANCEL }
enum class LogisticsRejection { SESSION, IDENTITY, OUTFIT, ACCESS, LOCATION, BUSY, PREPARATION, STOCK, CREW }
data class LogisticsRequest(val account: String, val connection: String, val characterId: Int,
    val power: Power, val generation: Long, val outfitId: Int, val mode: LogisticsMode,
    val phase: LogisticsPhase, val transfer: UnitTransfer = UnitTransfer(), val supplies: Int = 0,
    val reservation: Long? = null) {
    init { require(characterId > 0 && outfitId > 0 && generation > 0 && supplies != Int.MIN_VALUE &&
        (if (phase == LogisticsPhase.PREPARE) reservation == null else reservation != null && reservation > 0)) }
}
data class LogisticsResult(val stocks: PlanetStocks? = null, val rejection: LogisticsRejection? = null,
    val reservation: Long? = null)

/** World-actor-owned reservations. Only COMMIT changes durable stock. Author: 최병호. */
internal class OutfitLogistics(private val clock: () -> Long, private val leaseMillis: Long = 120_000) {
    private data class Reservation(val id: Long, val request: LogisticsRequest, val warehouse: Int, val base: Int, val expires: Long)
    private val reservations = mutableListOf<Reservation>()
    private var sequence = 0L
    init { require(leaseMillis in 1..3_600_000) }

    fun apply(request: LogisticsRequest, session: SessionState, player: Navigator?, production: PlanetProduction): LogisticsResult {
        fun reject(reason: LogisticsRejection) = LogisticsResult(rejection = reason)
        val now = clock()
        reservations.removeAll { lock -> lock.expires <= now || lock.request.generation != session.generation ||
            session.participants[lock.request.account]?.let { !it.online || it.connection != lock.request.connection } != false }
        val member = session.participants[request.account]
        if (session.ended != null || request.generation != session.generation || member == null || !member.online ||
            member.connection != request.connection) return reject(LogisticsRejection.SESSION)
        if (member.power != request.power || player == null || player.generation != session.generation ||
            player.characterId != request.characterId || player.power != request.power) return reject(LogisticsRejection.IDENTITY)
        val existing = reservations.singleOrNull { it.request.account == request.account && it.request.connection == request.connection }
        if (request.phase == LogisticsPhase.CANCEL) {
            if (existing == null || existing.id != request.reservation || existing.request.outfitId != request.outfitId || existing.request.mode != request.mode)
                return reject(LogisticsRejection.PREPARATION)
            reservations.remove(existing)
            return LogisticsResult(production.snapshot(session.generation))
        }
        val stocks = production.settle(session.generation, session.gameMillis / 1000)
        val outfit = stocks.outfits[request.outfitId] ?: return reject(LogisticsRejection.OUTFIT)
        val base = stocks.bases.getValue(outfit.baseId)
        val power = if (request.power == Power.EMPIRE) 2 else 3
        if (outfit.power != power || base.power != power || base.camp != 0) return reject(LogisticsRejection.ACCESS)
        val warehouse = stocks.warehouses.getValue(outfit.warehouseId)
        if (request.mode == LogisticsMode.ALLOCATION) {
            val office = stocks.logisticsOfficers[request.characterId]
            val allowed = office?.permits(power, outfit.kind) == true
            if (!allowed) return reject(LogisticsRejection.ACCESS)
        } else {
            if (outfit.commanderId != request.characterId) return reject(LogisticsRejection.ACCESS)
            if (player.baseId != outfit.baseId || player.dockTarget != null || player.fuelReadyAt != null || player.cpPaused)
                return reject(LogisticsRejection.LOCATION)
            if (request.supplies != 0) return reject(LogisticsRejection.STOCK)
        }
        if (request.phase == LogisticsPhase.PREPARE) {
            if (existing != null) return if (existing.request.outfitId == request.outfitId && existing.request.mode == request.mode)
                LogisticsResult(stocks, reservation = existing.id) else reject(LogisticsRejection.BUSY)
            // Allocation is a global writer; reorganization also excludes peers using the same shared warehouse.
            if (reservations.any { it.request.mode == LogisticsMode.ALLOCATION || request.mode == LogisticsMode.ALLOCATION ||
                    it.warehouse == outfit.warehouseId }) return reject(LogisticsRejection.BUSY)
            sequence = Math.addExact(sequence, 1)
            reservations.add(Reservation(sequence, request, outfit.warehouseId, outfit.baseId, Math.addExact(now, leaseMillis)))
            return LogisticsResult(stocks, reservation = sequence)
        }
        if (existing == null || existing.id != request.reservation || existing.request.mode != request.mode || existing.request.outfitId != request.outfitId ||
            existing.warehouse != outfit.warehouseId || existing.base != outfit.baseId) return reject(LogisticsRejection.PREPARATION)
        val next = try {
            if (request.transfer.ships.keys.any { key -> ProductionUnits.definitions.none { it.ship && it.kind == key.kind && it.power == power } } ||
                request.transfer.troops.keys.any { key -> ProductionUnits.definitions.none { !it.ship && it.kind == key.kind && it.power == power } })
                return reject(LogisticsRejection.STOCK)
            if (request.mode == LogisticsMode.ALLOCATION) {
                val moved = request.transfer.apply(base.units, warehouse.units)
                val remaining = base.supplies.toLong() - request.supplies
                val received = warehouse.supplies.toLong() + request.supplies
                if (remaining !in 0..Int.MAX_VALUE.toLong() || received !in 0..Int.MAX_VALUE.toLong())
                    return reject(LogisticsRejection.STOCK)
                stocks.copy(bases = stocks.bases + (base.baseId to base.copy(units = moved.source, supplies = remaining.toInt())),
                    warehouses = stocks.warehouses + (warehouse.id to warehouse.copy(units = moved.destination, supplies = received.toInt())))
            } else {
                val moved = request.transfer.apply(warehouse.units, outfit.units)
                val required = moved.destination.ships.entries.sumOf { (key, quantity) ->
                    val spec = UnitSpecifications.ships.singleOrNull { it.kind == key.kind }
                        ?: return reject(LogisticsRejection.STOCK)
                    quantity.toLong() * spec.number("必要乗組員ユニット数:必要乗組員ユニット数")
                }
                val crewKinds = UnitSpecifications.crews.filter { it.power == power && it.type == 3 }.map { it.kind }.toSet()
                val crew = moved.destination.troops.filterKeys { it.kind in crewKinds }.values.sumOf { it.toLong() }
                if (required != crew) return reject(LogisticsRejection.CREW)
                ActiveShips.reorganize(stocks, outfit.id, moved.destination).copy(
                    warehouses = stocks.warehouses + (warehouse.id to warehouse.copy(units = moved.source)))
            }
        } catch (_: IllegalArgumentException) { return reject(LogisticsRejection.STOCK) }
        val saved = production.replace(stocks, next)
        reservations.remove(existing)
        return LogisticsResult(saved)
    }
}
