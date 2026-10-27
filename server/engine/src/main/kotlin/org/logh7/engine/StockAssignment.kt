package org.logh7.engine

/** Native 0c0b moves between base stock and reserved outfit stock. Author: 최병호. */
class StockAssignmentRequest(val account: String, val connection: String, val characterId: Int, val power: Power,
    val generation: Long, val base: Int, val outfit: Int, val kind: Int,
    ships: List<ReorganizationShip>, troops: List<ReorganizationTroop>, val supplies: Int,
    val politics: Int = 50, val administration: Int = 50) {
    val ships: List<ReorganizationShip> = java.util.Collections.unmodifiableList(ArrayList(ships))
    val troops: List<ReorganizationTroop> = java.util.Collections.unmodifiableList(ArrayList(troops))
    init {
        require(characterId > 0 && generation > 0 && base in 1..65535 && outfit > 0 && kind in 0..255 &&
            supplies != Int.MIN_VALUE && politics in 0..65535 && administration in 0..65535 &&
            ships.size <= 99 && troops.size <= 24 &&
            ships.all { it.kind in 0..65535 && it.units in -128..127 && it.boats in 0..65535 } &&
            troops.all { it.kind in 0..65535 && it.grade in 0..255 && it.units in -32768..32767 })
    }
}
data class StockAssignmentResult(val player: Navigator?, val stocks: PlanetStocks?,
    val rejection: LogisticsRejection? = null, val changed: Boolean = false, val recovery: NavigationResult? = null)

internal object StockAssignment {
    const val COST = 160 // Web manual, logistics command table: MCP 160, no wait.
    /** Until native paging is recovered, never commit a result that its reply cannot represent. */
    fun validateReply(units: UnitInventory, spare: SpareShipBoats = SpareShipBoats()) {
        val kinds=(units.ships.keys+spare.values.keys).map {it.kind}.toSet()
        require(kinds.size <= 99 && units.troops.size <= 24)
        for (kind in kinds) {
            val number = UnitSpecifications.ships.single { it.kind == kind }.number.toLong()
            val boats=units.ships.filterKeys {it.kind==kind}.values.sumOf {it.toLong()}*number+
                spare.values.filterKeys {it.kind==kind}.values.sumOf {it.toLong()}
            require((boats+number-1)/number in 1..255 && boats in 1..65535)
        }
        require(units.troops.all { (stack, count) -> stack.kind in 0..15 && stack.level in 0..3 && count in 1..65535 })
    }
    fun transfer(request: StockAssignmentRequest, stocks: PlanetStocks): UnitTransfer {
        val outfit = stocks.outfits.getValue(request.outfit)
        require(request.base == outfit.baseId && request.kind == outfit.kind.wire)
        require(request.ships.map { it.kind }.distinct().size == request.ships.size &&
            request.troops.map { it.kind to it.grade }.distinct().size == request.troops.size)
        val base=stocks.bases.getValue(request.base)
        val warehouse=stocks.warehouses.getValue(outfit.warehouseId)
        val source = ReplacementBoats.selectable(base.units,base.spareShips)
        val destination = ReplacementBoats.selectable(warehouse)
        val ships = linkedMapOf<UnitStack, Int>()
        for (row in request.ships) {
            require(row.units != 0 && row.boats == 0) { "Only selectable-unit assignment is supported" }
            var remaining = kotlin.math.abs(row.units)
            val available = if (row.units > 0) source.ships else destination.ships
            for ((stack, count) in available.entries.filter { it.key.kind == row.kind }.sortedBy { it.key.level }) {
                val moved = minOf(count, remaining)
                if (moved > 0) ships[stack] = if (row.units > 0) moved else -moved
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
}
