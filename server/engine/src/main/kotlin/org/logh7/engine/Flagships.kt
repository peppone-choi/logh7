package org.logh7.engine

import org.logh7.engine.worldseed.SeedFaction
import org.logh7.engine.worldseed.ShipUnitDefinition
import org.logh7.engine.worldseed.ShipUnitSeedLoader

data class FlagshipDefinition(val kind: Int, val type: Int, val model: Int, val power: Int,
    val name: String, val manual: ShipUnitDefinition) {
    fun number(column: String): Int = manual.stats.getValue(column).let {
        if (it == "-") 0 else it.replace(",", "").substringBefore('/').toInt()
    }
}

/** Kinds: constmsg group 85. Model codes use native 004f3e30 faction encoding. Author: 최병호. */
object Flagships {
    private val manual = ShipUnitSeedLoader.load().units
    val definitions: List<FlagshipDefinition> = listOf(
        entry(0, 0, 12, 2, "표준전함", "戦艦"),
        entry(2, 2, 16, 2, "순항함", "巡航艦"),
        entry(3, 3, 18, 2, "구축함", "駆逐艦"),
        assault(4,3,2),
        entry(89, 0, 1012, 3, "표준전함", "戦艦"),
        entry(90, 2, 1016, 3, "순항함", "巡航艦"),
        entry(93, 3, 1014, 3, "구축함", "駆逐艦"),
        assault(94,1003,3),
    )
    /** Native group 85 confirms kind 4/94; the manual has no assault flagship row.
     * Use its type-I hull and 200 output as explicit replacement policy, with no invented shield. */
    private fun assault(kind:Int,model:Int,power:Int):FlagshipDefinition {
        val faction=if(power==2)SeedFaction.EMPIRE else SeedFaction.ALLIANCE
        val hull=manual.single {it.faction==faction && it.unit=="揚陸艦Ⅰ"}
        return FlagshipDefinition(kind,4,model,power,"양륙함",hull.copy(stats=hull.stats+("出力:出力" to "200"),
            verificationStatus="designed-assault-flagship"))
    }
    private fun entry(kind: Int, type: Int, model: Int, power: Int, name: String, sourceName: String): FlagshipDefinition {
        val faction = if (power == 2) SeedFaction.EMPIRE else SeedFaction.ALLIANCE
        return FlagshipDefinition(kind, type, model, power, name,
            manual.single { it.faction == faction && it.unit == "$sourceName（旗艦）" })
    }
    fun find(kind: Int): FlagshipDefinition? = definitions.singleOrNull { it.kind == kind }
    fun forPower(power: Int): List<FlagshipDefinition> = definitions.filter { it.power == power }.also { require(it.isNotEmpty()) }
    fun forType(power: Int, type: Int): FlagshipDefinition = forPower(power).single { it.type == type }
}
