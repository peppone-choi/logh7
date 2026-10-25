package org.logh7.engine

/** Server identities, independent of the still-unverified native outfit category encoding. */
enum class OutfitKind(val wire: Int) {
    FLEET(0), PATROL(2), TRANSPORT(1), GROUND(3);
    val warehouseKind: OutfitKind get() = if (this == GROUND) PATROL else this
}
enum class LogisticsOffice {
    EMPIRE_FIRST, EMPIRE_SECOND, ALLIANCE_THIRD;
    fun permits(power: Int, kind: OutfitKind): Boolean = when (this) {
        EMPIRE_FIRST -> power == 2 && kind == OutfitKind.FLEET
        EMPIRE_SECOND -> power == 2 && kind != OutfitKind.FLEET
        ALLIANCE_THIRD -> power == 3
    }
}

data class OutfitStock(val id: Int, val power: Int, val kind: OutfitKind, val baseId: Int,
    val warehouseId: Int, val commanderId: Int = 0, val units: UnitInventory = UnitInventory(),
    val number: Int = 0, val supplies: Int = 0) {
    init {
        require(id > 0 && power in 2..3 && baseId in 1..65535 && warehouseId > 0 && commanderId >= 0)
        require(number in 0..255 && supplies >= 0)
        require(units.work.isEmpty()) { "An outfit cannot own a production line" }
    }
}

data class OutfitWarehouse(val id: Int, val power: Int, val kind: OutfitKind, val baseId: Int, val outfitId: Int = 0,
    val units: UnitInventory = UnitInventory(), val supplies: Int = 0, val food: Int = 0, val mineral: Int = 0) {
    init {
        require(id > 0 && power in 2..3 && baseId in 1..65535 && supplies >= 0 && food >= 0 && mineral >= 0)
        require(if (kind in setOf(OutfitKind.FLEET,OutfitKind.TRANSPORT)) outfitId > 0 else outfitId == 0)
        require(units.work.isEmpty()) { "An outfit warehouse cannot own a production line" }
    }
}

/** Manual 7.3/7.5 and native group 91: patrol/ground share the local warehouse. */
internal fun validateOutfitStocks(bases: Map<Int, PlanetStock>, outfits: Map<Int, OutfitStock>,
    warehouses: Map<Int, OutfitWarehouse>, officers: Map<Int, LogisticsOffice>) {
    require(outfits.size <= 1000 && warehouses.size <= 1000 && officers.size <= 1000)
    require(warehouses.all { (id, warehouse) -> id == warehouse.id && warehouse.baseId in bases &&
        (warehouse.outfitId == 0 || outfits[warehouse.outfitId]?.let { it.power == warehouse.power && it.kind == warehouse.kind } == true) })
    require(warehouses.values.map { listOf(it.baseId,it.power,it.kind.warehouseKind,it.outfitId) }.distinct().size == warehouses.size) {
        "Duplicate warehouse owner at a base"
    }
    require(officers.keys.all { it > 0 })
    require(officers.values.distinct().size == officers.size) { "A logistics office has multiple holders" }
    require(outfits.all { (id, outfit) -> id == outfit.id && outfit.baseId in bases &&
        warehouses[outfit.warehouseId]?.let { it.power == outfit.power && it.kind.warehouseKind == outfit.kind.warehouseKind && it.baseId == outfit.baseId &&
            (it.outfitId == 0 || it.outfitId == outfit.id) } == true })
    outfits.values.groupBy { it.warehouseId }.values.forEach { members ->
        require(members.size == 1 || members.first().kind in setOf(OutfitKind.PATROL, OutfitKind.GROUND)) {
            "Private outfit warehouses cannot be shared"
        }
    }
    outfits.values.filter { it.kind in setOf(OutfitKind.PATROL, OutfitKind.GROUND) }
        .groupBy { Triple(it.power,it.kind.warehouseKind,it.baseId) }.values.forEach { members ->
            require(members.map { it.warehouseId }.distinct().size == 1) { "Shared outfit warehouse is split" }
        }
}
