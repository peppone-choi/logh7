package org.logh7.engine

/** Native 0c02 deltas: ship rows have no grade; zero boats means whole units. Author: 최병호. */
data class ReorganizationShip(val kind: Int, val units: Int, val boats: Int = 0)
data class ReorganizationTroop(val kind: Int, val grade: Int, val units: Int)
class ReorganizationRequest(val account: String, val connection: String, val characterId: Int, val power: Power,
    val generation: Long, val base: Int, val outfit: Int, val kind: Int, val mode: Int,
    ships: List<ReorganizationShip>, troops: List<ReorganizationTroop>, val supplies: Int = 0,
    val politics: Int = 50, val administration: Int = 50) {
    val ships: List<ReorganizationShip> = java.util.Collections.unmodifiableList(ArrayList(ships))
    val troops: List<ReorganizationTroop> = java.util.Collections.unmodifiableList(ArrayList(troops))
    init { require(characterId > 0 && generation > 0 && base in 1..65535 && outfit > 0 && kind in 0..255 && mode in 0..255 &&
        ships.size <= 99 && troops.size <= 24 && ships.all { it.kind in 0..65535 && it.units in -128..127 && it.boats in 0..65535 } &&
        troops.all { it.kind in 0..65535 && it.grade in 0..255 && it.units in -32768..32767 } &&
        politics in 0..65535 && administration in 0..65535) }
}
data class ReorganizationResult(val player: Navigator?, val stocks: PlanetStocks?,
    val rejection: LogisticsRejection? = null, val changed: Boolean = false, val recovery: NavigationResult? = null,
    val capacity: ReorganizationCapacity? = null)
data class ReorganizationCapacity(val additionalTroops: Int, val crew: Int)

internal object Reorganization {
    const val COST = 160 // constmsg group 0, entry 130: MCP 160; no wait/duration.
    /** Resolve a grade-less ship row from actual source stacks without discarding their stored levels. */
    fun transfer(request: ReorganizationRequest, stocks: PlanetStocks): UnitTransfer {
        val outfit = stocks.outfits.getValue(request.outfit)
        // 004b4fc0 copies troop rows only in nonzero mode; the command-63
        // factory's normal SendOrganizeDataCommand path selects mode 1.
        require(request.base == outfit.baseId && request.kind == outfit.kind.wire && request.mode in 0..1 &&
            (request.mode == 1 || request.troops.isEmpty()) && request.supplies == 0)
        require(request.ships.map { it.kind }.distinct().size == request.ships.size &&
            request.troops.map { it.kind to it.grade }.distinct().size == request.troops.size)
        val warehouse = stocks.warehouses.getValue(outfit.warehouseId)
        val ships = linkedMapOf<UnitStack, Int>()
        for (row in request.ships) {
            // 004c6930 emits zero boats for whole-unit moves. Partial-boat movement is not implemented.
            require(row.units != 0 && row.boats == 0)
            // 004c6a70 compares the edited outfit quantity with its baseline.
            val units = row.units
            val source = if (units > 0) warehouse.units.ships else outfit.units.ships
            var remaining = kotlin.math.abs(units)
            for ((stack, count) in source.entries.filter { it.key.kind == row.kind }.sortedBy { it.key.level }) {
                val moved = minOf(count, remaining)
                if (moved > 0) ships[stack] = if (units > 0) moved else -moved
                remaining -= moved
            }
            require(remaining == 0) { "Insufficient ship units" }
        }
        val troops = request.troops.associate { row ->
            require(row.kind in 1000..1015 && row.units != 0)
            UnitStack(row.kind - 1000, row.grade) to row.units
        }
        return UnitTransfer(ships, troops)
    }
    fun capacity(stocks: PlanetStocks, outfitId: Int, transfer: UnitTransfer): ReorganizationCapacity {
        val outfit = stocks.outfits.getValue(outfitId)
        val units = transfer.apply(stocks.warehouses.getValue(outfit.warehouseId).units,outfit.units).destination
        val crew = units.ships.entries.sumOf { (stack,count) ->
            count.toLong()*UnitSpecifications.ships.single { it.kind == stack.kind }
                .number("必要乗組員ユニット数:必要乗組員ユニット数")
        }
        // Additional infantry transport capacity is not reconstructed yet.
        // Preserve the editor's existing infantry baseline, never invent slots.
        return ReorganizationCapacity(0,Math.toIntExact(crew))
    }
}
