package org.logh7.engine

/** W p.51: thirty defending units fill the bar. Native 004c32a0 uses 30000 as
 * the base bar maximum. Mapping each whole unit to 1000 is a display policy;
 * The live overload sums the first thirty eligible defenders and their saved remaining strength;
 * occupation remains separate.
 * Author: Peppone Choi.
 */
object TacticalBaseDefense {
    const val MAXIMUM_STRENGTH=30_000
    fun strength(stocks:PlanetStocks,base:GalaxyBase):Int {
        val owner=stocks.bases.getValue(base.id)
        if(owner.power !in 2..3 || owner.camp!=0)return 0
        return GroundBattleOperations.participants(stocks,base,owner.power).sumOf {it.strength}
    }
    fun strength(stocks:PlanetStocks,base:Int):Int {
        val owner=stocks.bases.getValue(base)
        // Outfit insurgency is not represented yet; regular units cannot defend a rebel owner.
        if(owner.power !in 2..3 || owner.camp!=0)return 0
        val outfits=stocks.outfits.values.filter {it.power==owner.power}
        val known=outfits.flatMap {it.activeGround?.values.orEmpty()}.filter {it.base==base}
            .sortedBy {it.id}.take(GroundBattleRules.BOX_CAPACITY_PER_FACTION)
        val legacy=outfits.filter {it.activeGround==null && it.kind==OutfitKind.GROUND &&
            (it.position?.baseId ?: it.baseId)==base}.sumOf {GroundUnits.quantities(it.units).values.sumOf(Int::toLong)}
        return known.sumOf {it.strength} + legacy.coerceAtMost((30-known.size).toLong()).toInt()*1000
    }
}
