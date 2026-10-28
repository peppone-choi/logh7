package org.logh7.engine

/** W p.51: thirty defending units fill the bar. Native 004c32a0 uses 30000 as
 * the base bar maximum. Mapping each whole unit to 1000 is a display policy;
 * this neither selects ground combatants nor implements their casualty/occupation rules.
 * Author: 최병호.
 */
object TacticalBaseDefense {
    const val MAXIMUM_STRENGTH=30_000
    fun strength(stocks:PlanetStocks,base:Int):Int {
        val owner=stocks.bases.getValue(base)
        // Outfit insurgency is not represented yet; regular units cannot defend a rebel owner.
        if(owner.power !in 2..3 || owner.camp!=0)return 0
        val count=stocks.outfits.values.filter {it.power==owner.power}.sumOf {outfit ->
            outfit.activeGround?.values?.count {it.base==base}?.toLong() ?:
                if(outfit.kind==OutfitKind.GROUND && (outfit.position?.baseId ?: outfit.baseId)==base)
                    GroundUnits.quantities(outfit.units).values.sumOf {it.toLong()}
                else 0L
        }
        return count.coerceAtMost(GroundBattleRules.BOX_CAPACITY_PER_FACTION.toLong()).toInt()*1000
    }
}
