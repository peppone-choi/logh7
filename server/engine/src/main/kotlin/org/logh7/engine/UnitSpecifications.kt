package org.logh7.engine

import org.logh7.engine.worldseed.*

/** Manual statistics joined to native IDs. Model/class assignments beyond the six flagships are designed. */
data class ShipSpecification(val kind: Int, val type: Int, val category: Int, val model: Int,
    val name: String, val manual: ShipUnitDefinition, val number: Int) {
    fun number(column: String, part: Int = 0): Int = manual.stats.getValue(column).let {
        if (it == "-") 0 else it.replace(",", "").split('/').getOrNull(part)?.toInt() ?: 0
    }
}
data class TroopSpecification(val kind: Int, val type: Int, val category: Int, val power: Int,
    val manual: CrewUnitDefinition)

object UnitSpecifications {
    private val manualShips = ShipUnitSeedLoader.load().units
    private val troops = CrewUnitSeedLoader.load().units
    private data class Class(val type: Int, val category: Int, val empireModel: Int, val allianceModel: Int)
    // Category IDs: native constmsg 114. Carrier types 9/10: native 004c76e0.
    // Other extended type/model correspondences are explicitly provisional, not recovered server data.
    private val classes = mapOf(
        "戦艦" to Class(0, 3, 12, 12), "高速戦艦" to Class(1, 4, 14, 12),
        "巡航艦" to Class(2, 5, 16, 16), "駆逐艦" to Class(3, 6, 18, 14),
        "戦闘艇母艦" to Class(9, 7, 44, 37), "雷撃艇母艦" to Class(10, 8, 45, 37),
        "工作艦" to Class(11, 9, 39, 43), "輸送艦" to Class(12, 10, 40, 47),
        "兵員輸送艦" to Class(13, 11, 3, 37), "揚陸艦" to Class(4, 12, 3, 37),
        "打撃巡航艦" to Class(7, 13, 16, 23), "商船" to Class(14, 14, 40, 47))
    val ships: List<ShipSpecification> = buildList {
        Flagships.definitions.forEach { add(ShipSpecification(it.kind, it.type, 0, it.model, it.name, it.manual, 1)) }
        ProductionUnits.definitions.filter { it.ship }.forEach { product ->
            val faction = if (product.power == 2) SeedFaction.EMPIRE else SeedFaction.ALLIANCE
            val manual = manualShips.single { it.faction == faction && it.unit == product.name }
            val name = product.name.trimEnd('Ⅰ','Ⅱ','Ⅲ','Ⅳ','Ⅴ','Ⅵ','Ⅶ','Ⅷ')
            val type = classes.getValue(name)
            add(ShipSpecification(product.kind, type.type, type.category,
                if (product.power == 2) type.empireModel else 1000 + type.allianceModel, product.name, manual, 300))
        }
    }.also { require(it.map { ship -> ship.kind }.distinct().size == it.size) }
    val crews: List<TroopSpecification> = ProductionUnits.definitions.filter { !it.ship }.map { product ->
        val faction = if (product.power == 2) SeedFaction.EMPIRE else SeedFaction.ALLIANCE
        val manual = troops.single { it.faction == faction && it.unit == product.name }
        val type = when (product.kind) { 1,5,9 -> 1; 2,6 -> 2; 3,7 -> 3; else -> 0 }
        TroopSpecification(product.kind, type, 16 + type, product.power, manual)
    }
}
