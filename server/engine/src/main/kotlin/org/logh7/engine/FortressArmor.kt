package org.logh7.engine

/**
 * CD manual p.46 orbital ground attack targets (evidence:manual): a fortress's armor can be destroyed, and when all of
 * it is gone every enemy flagship/ship unit stationed inside is sunk; a mobile fortress's propulsion can be destroyed.
 * Values and recovery are user decisions 2026-11-03 (evidence:guess): [ARMOR] armor, [DAMAGE] per attacking ship, and
 * full recovery when the fortress changes hands. The fortress itself remains and is still taken only by ground troops.
 * Author: Peppone Choi.
 */
object FortressArmor {
    const val ARMOR=10_000
    const val DAMAGE=500

    fun remaining(stocks:PlanetStocks,base:Int)=ARMOR-(stocks.tacticalCommands.fortressDamage[base] ?: 0)
    fun breached(stocks:PlanetStocks,base:Int)=remaining(stocks,base)<=0
    fun propulsion(stocks:PlanetStocks,base:Int)=base !in stocks.tacticalCommands.brokenPropulsion

    /** Applies one orbital attack of [attackers] ships to a fortress. Planets are left unchanged. */
    fun strike(base:GalaxyBase,stocks:PlanetStocks,attackers:Int):PlanetStocks {
        if(base.kind!=1)return stocks
        require(attackers>0)
        val c=stocks.tacticalCommands
        val damage=minOf(ARMOR,(c.fortressDamage[base.id] ?: 0)+DAMAGE*attackers)
        return stocks.copy(tacticalCommands=c.copy(fortressDamage=c.fortressDamage+(base.id to damage),
            brokenPropulsion=if(base.mobile)c.brokenPropulsion+base.id else c.brokenPropulsion))
    }
    /** A captured fortress is repaired by its new owner. */
    fun restore(stocks:PlanetStocks,base:Int):PlanetStocks {
        val c=stocks.tacticalCommands
        if(base !in c.fortressDamage && base !in c.brokenPropulsion)return stocks
        return stocks.copy(tacticalCommands=c.copy(fortressDamage=c.fortressDamage-base,brokenPropulsion=c.brokenPropulsion-base))
    }
    private fun stationed(base:GalaxyBase,galaxy:Galaxy,outfit:OutfitStock,unit:ActiveShipUnit)=unit.posture?.let {
        it.grid==base.grid && it.base==base.id && it.mode==TacticalShipMode.STATIONED} ?: (outfit.positionIn(galaxy)==OutfitPosition(base.grid,base.id))
    fun pending(stocks:PlanetStocks,galaxy:Galaxy,players:Collection<Navigator>)=galaxy.bases.any {base->
        breached(stocks,base.id) && (stocks.outfits.values.any {o->o.power==stocks.bases[base.id]?.power &&
            o.activeShips?.values.orEmpty().any {it.boats>0 && stationed(base,galaxy,o,it)}} ||
            players.any {it.baseId==base.id && it.grid==base.grid && (if(it.power==Power.EMPIRE)2 else 3)==stocks.bases[base.id]?.power})}
    /** Sinks the holder's flagships and ship units stationed inside a fortress whose armor is gone. */
    fun advance(session:SessionState,galaxy:Galaxy,players:MutableMap<String,Navigator>,original:PlanetStocks,
        sunk:MutableList<Navigator>):PlanetStocks {
        if(session.ended!=null || original.tacticalCommands.fortressDamage.values.none {it>=ARMOR})return original
        val now=session.gameMillis/1000
        var stocks=original
        for(base in galaxy.bases.filter {it.kind==1 && breached(original,it.id)}) {
            val holder=stocks.bases[base.id]?.power ?: continue
            stocks=stocks.copy(outfits=stocks.outfits.mapValues {(_,outfit)->
                var changed=outfit
                if(outfit.power==holder)for(unit in outfit.activeShips?.values.orEmpty())
                    if(unit.boats>0 && stationed(base,galaxy,outfit,unit))changed=TacticalLosses.apply(changed,unit.id,unit.boats)
                changed
            })
            for((account,p) in players.toMap()) {
                if(p.generation!=session.generation || p.baseId!=base.id || p.grid!=base.grid ||
                    (if(p.power==Power.EMPIRE)2 else 3)!=holder)continue
                val departed=FlagshipRecovery.defeated(p,now,galaxy,stocks,players.values)
                players[account]=departed
                sunk+=departed
            }
        }
        return stocks
    }
}
