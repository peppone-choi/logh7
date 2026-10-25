package org.logh7.engine

import org.logh7.engine.worldseed.*

data class ProductionUnit(val kind: Int, val ship: Boolean, val power: Int, val name: String, val hours: Int) {
    init { require(kind in 0..199 && (ship || kind <= 15) && power in 2..3 && hours in 1..65511) }
    val key get() = "${if (ship) 'S' else 'T'}$kind"
}

/** Native constmsg 85/86 IDs joined to the reviewed manual definitions, never row-position IDs. */
object ProductionUnits {
    private val ships = ShipUnitSeedLoader.load().units
    private val crews = CrewUnitSeedLoader.load().units
    private val romans = listOf("Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ", "Ⅵ", "Ⅶ", "Ⅷ")
    val definitions: List<ProductionUnit> = buildList {
        fun series(power: Int, start: Int, prefix: String, count: Int) {
            val faction = if (power == 2) SeedFaction.EMPIRE else SeedFaction.ALLIANCE
            repeat(count) { index ->
                val name = prefix + romans[index]
                val source = ships.single { it.faction == faction && it.unit == name }
                add(ProductionUnit(start + index, true, power, name, source.stats.getValue("建造工期:建造工期").toInt()))
            }
        }
        for ((start, name, count) in listOf(Triple(32,"戦艦",8), Triple(40,"高速戦艦",8), Triple(48,"巡航艦",8),
            Triple(56,"駆逐艦",8), Triple(64,"戦闘艇母艦",4), Triple(68,"雷撃艇母艦",4), Triple(72,"工作艦",4),
            Triple(76,"輸送艦",4), Triple(80,"兵員輸送艦",4), Triple(84,"揚陸艦",4))) series(2, start, name, count)
        for ((start, name, count) in listOf(Triple(119,"戦艦",8), Triple(127,"巡航艦",8), Triple(135,"打撃巡航艦",4),
            Triple(139,"駆逐艦",8), Triple(147,"戦闘艇母艦",4), Triple(151,"工作艦",4), Triple(155,"輸送艦",4),
            Triple(159,"兵員輸送艦",4), Triple(163,"揚陸艦",4))) series(3, start, name, count)
        for ((power, kind) in listOf(2 to 88, 3 to 167)) {
            val faction = if (power == 2) SeedFaction.EMPIRE else SeedFaction.ALLIANCE
            val source = ships.single { it.faction == faction && it.unit == "商船" }
            add(ProductionUnit(kind, true, power, source.unit, source.stats.getValue("建造工期:建造工期").toInt()))
        }
        for ((power, names) in listOf(2 to listOf(0 to "軽装陸戦兵",1 to "装甲擲弾兵",2 to "装甲兵",3 to "艦隊乗組員",
            8 to "近衛兵",9 to "擲弾兵教導"), 3 to listOf(4 to "軽装陸戦兵",5 to "装甲擲弾兵",6 to "装甲兵",7 to "艦隊乗組員",10 to "薔薇の騎士"))) {
            val faction = if (power == 2) SeedFaction.EMPIRE else SeedFaction.ALLIANCE
            names.forEach { (kind, name) ->
                val source = crews.single { it.faction == faction && it.unit == name }
                add(ProductionUnit(kind, false, power, name, source.training))
            }
        }
    }
    fun find(power: Int, name: String): ProductionUnit = definitions.single {
        it.power == power && it.name == name.replace("揚陸艇", "揚陸艦")
    }
}
