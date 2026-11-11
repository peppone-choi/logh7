package org.logh7.engine

/** Native 0c0b moves between base stock and reserved outfit stock. Author: Peppone Choi. */
class StockAssignmentRequest(val account: String, val connection: String, val characterId: Int, val country: CountryId?,
    val generation: Long, val base: Int, val outfit: Int, val kind: Int,
    ships: List<ReorganizationShip>, troops: List<ReorganizationTroop>, val supplies: Int,
    val politics: Int = 50, val administration: Int = 50) {
    constructor(account:String,connection:String,characterId:Int,power:Power,generation:Long,base:Int,outfit:Int,kind:Int,
        ships:List<ReorganizationShip>,troops:List<ReorganizationTroop>,supplies:Int,politics:Int=50,administration:Int=50):
        this(account,connection,characterId,power.country,generation,base,outfit,kind,ships,troops,supplies,politics,administration)
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
class StockAssignmentProposal(val token: Int, val base: Int, val outfit: Int, val kind: Int,
    ships: List<ReorganizationShip>, troops: List<ReorganizationTroop>, val supplies: Int) {
    val ships: List<ReorganizationShip> = java.util.Collections.unmodifiableList(ArrayList(ships))
    val troops: List<ReorganizationTroop> = java.util.Collections.unmodifiableList(ArrayList(troops))

    init {
        require(token > 0)
        request("", "", 1, null, 1)
    }

    internal fun request(account: String, connection: String, character: Int, country: CountryId?, generation: Long) =
        StockAssignmentRequest(account, connection, character, country, generation, base, outfit, kind, ships, troops, supplies)

    override fun equals(other: Any?): Boolean = other is StockAssignmentProposal &&
        token == other.token && base == other.base && outfit == other.outfit && kind == other.kind &&
        ships == other.ships && troops == other.troops && supplies == other.supplies

    override fun hashCode(): Int = listOf(token, base, outfit, kind, ships, troops, supplies).hashCode()
}

data class StockAssignmentResult(val player: Navigator?, val stocks: PlanetStocks?,
    val rejection: LogisticsRejection? = null, val changed: Boolean = false, val recovery: NavigationResult? = null)

internal object StockAssignment {
    const val COST = 160 // Web manual, logistics command table: MCP 160, no wait.

    fun preview(request: StockAssignmentRequest, actor: Navigator, stocks: PlanetStocks, galaxy: Galaxy,
        requiredCard: Int? = null, countries: CountryCatalog = CountryCatalogLoader.current(),
        blocked: () -> Boolean = { false }): StockAssignmentResult {
        fun reject(reason: LogisticsRejection) = StockAssignmentResult(actor, stocks, reason)
        if (request.generation != stocks.generation) return reject(LogisticsRejection.SESSION)
        if (actor.generation != request.generation || actor.characterId != request.characterId ||
            actor.country != request.country) return reject(LogisticsRejection.IDENTITY)
        val country = actor.country ?: return reject(LogisticsRejection.ACCESS)
        if (BattleDeath.deceased(stocks, actor.characterId) || stocks.social.justice.detained(actor.characterId))
            return reject(LogisticsRejection.ACCESS)
        val outfit = stocks.outfits[request.outfit] ?: return reject(LogisticsRejection.OUTFIT)
        val transfer = try {
            transfer(request, stocks)
        } catch (_: IllegalArgumentException) {
            return reject(LogisticsRejection.STOCK)
        } catch (_: NoSuchElementException) {
            return reject(LogisticsRejection.OUTFIT)
        }
        if (!TacticalAuthority.canAccessOutfitWarehouse(stocks, outfit, actor.characterId, country) ||
            !LogisticsOfficePolicy(galaxy, countries).permits(stocks, actor.characterId, country, outfit.kind, requiredCard))
            return reject(LogisticsRejection.ACCESS)
        if (blocked()) return reject(LogisticsRejection.BUSY)
        val next = try {
            applyTransfer(stocks, outfit, transfer, request.supplies)
        } catch (_: IllegalArgumentException) {
            return reject(LogisticsRejection.STOCK)
        }
        return StockAssignmentResult(actor, next, changed = next != stocks)
    }

    fun applyTransfer(stocks: PlanetStocks, outfit: OutfitStock, transfer: UnitTransfer, supplies: Int,
        sourceBaseId:Int=outfit.baseId): PlanetStocks {
        require(transfer.ships.keys.all { key ->
            ProductionUnits.definitions.any { it.ship && it.kind == key.kind && it.power == outfit.shipStyle.nativePower }
        } && transfer.troops.keys.all { key ->
            ProductionUnits.definitions.any { !it.ship && it.kind == key.kind && it.power == outfit.shipStyle.nativePower }
        })
        val base = stocks.bases.getValue(sourceBaseId)
        require(sourceBaseId==outfit.baseId || base.country==outfit.country && base.camp==0 &&
            outfit.position?.baseId==sourceBaseId)
        val warehouse = stocks.warehouses.getValue(outfit.warehouseId)
        val moved = ReplacementBoats.moveStored(base.units, base.spareShips, warehouse.units, warehouse.spareShips, transfer)
        require(warehouseShipIncreaseFits(base.units.ships, base.spareShips, moved.source.ships, moved.sourceSpare) &&
            warehouseShipIncreaseFits(warehouse.units.ships, warehouse.spareShips, moved.destination.ships, moved.destinationSpare))
        validateTroopReply(moved.source)
        validateTroopReply(moved.destination)
        val remaining = base.supplies.toLong() - supplies
        val received = warehouse.supplies.toLong() + supplies
        require(remaining in 0..Int.MAX_VALUE.toLong() && received in 0..Int.MAX_VALUE.toLong())
        return stocks.copy(
            bases = stocks.bases + (base.baseId to base.copy(units = moved.source, spareShips = moved.sourceSpare, supplies = remaining.toInt())),
            warehouses = stocks.warehouses + (warehouse.id to warehouse.copy(units = moved.destination,
                spareShips = moved.destinationSpare, supplies = received.toInt())),
        )
    }

    /** Until native paging is recovered, never commit a result that its reply cannot represent. */
    fun validateReply(units: UnitInventory, spare: SpareShipBoats = SpareShipBoats()) {
        val kinds=(units.ships.keys+spare.values.keys).map {it.kind}.toSet()
        require(kinds.size <= 99)
        for (kind in kinds) {
            val number = UnitSpecifications.ships.single { it.kind == kind }.number.toLong()
            val boats=units.ships.filterKeys {it.kind==kind}.values.sumOf {it.toLong()}*number+
                spare.values.filterKeys {it.kind==kind}.values.sumOf {it.toLong()}
            require((boats+number-1)/number in 1..255 && boats in 1..65535)
        }
        validateTroopReply(units)
    }
    private fun validateTroopReply(units: UnitInventory) {
        require(units.troops.size <= 24 && units.troops.all { (stack, count) ->
            stack.kind in 0..15 && stack.level in 0..3 && count in 1..65535
        })
    }
    fun transfer(request: StockAssignmentRequest, stocks: PlanetStocks): UnitTransfer {
        val outfit = stocks.outfits.getValue(request.outfit)
        require(request.base == outfit.baseId && request.kind == outfit.kind.wire)
        require(request.ships.map { it.kind }.distinct().size == request.ships.size &&
            request.troops.map { it.kind to it.grade }.distinct().size == request.troops.size)
        val base=stocks.bases.getValue(request.base)
        val warehouse=stocks.warehouses.getValue(outfit.warehouseId)
        val ships = linkedMapOf<UnitStack, Int>()
        for (row in request.ships) {
            require(row.units != 0 && row.boats == 0) { "Only selectable-unit assignment is supported" }
            var remaining = kotlin.math.abs(row.units)
            val stock = if (row.units > 0) base.units else warehouse.units
            val spare = if (row.units > 0) base.spareShips else warehouse.spareShips
            val available = (stock.ships.keys + spare.values.keys).filter { it.kind == row.kind }
                .associateWith { (stock.ships[it] ?: 0) + if (it in spare.values) 1 else 0 }
            for ((stack, count) in available.entries.sortedBy { it.key.level }) {
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
