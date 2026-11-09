package org.logh7.engine

/** Peaceful settlement of a vacant neutral planet, pending founding. Revival rule. Author: Peppone Choi. */
object IndependentBaseClaim {
    const val COMMAND = 250

    fun candidates(galaxy:Galaxy,player:Navigator,stocks:PlanetStocks):List<GalaxyBase> {
        val occupied=stocks.outfits.values.flatMap {it.activeGround?.values.orEmpty()}.flatMap {
            listOf(it.base,it.order?.base ?: 0)
        }.toSet()
        return galaxy.bases.filter {base->
            val stock=stocks.bases.getValue(base.id)
            base.grid==player.grid && base.kind==0 && base.name!=TradeActions.FEZZAN_NAME &&
                Capital.of(base)==null && base.id !in stocks.strategic.capitals.values && base.id !in occupied &&
                stock.camp==0 && stock.owner==Allegiance.Neutral
        }.sortedBy {it.id}
    }

    internal fun evaluate(galaxy:Galaxy,r:StrategyActionRequest,s:SessionState,p:Navigator,stocks:PlanetStocks):StrategyActionResult {
        fun reject(reason:StrategyActionRejection)=StrategyActionResult(p,stocks,reason)
        if(galaxy.unaffiliatedStart==null || p.country!=null || r.card!=0 || r.outfit!=0 || r.repairPreview)
            return reject(StrategyActionRejection.AUTHORITY)
        if(p.cpPaused || p.navigationPending || p.mode!=5 || p.baseId!=0 ||
            TacticalContact.active(galaxy,p,r.navigators.values,stocks,s.eliminated.keys))
            return reject(StrategyActionRejection.LOCATION)
        if(p.hull?.woundedUntil?.let {it>s.gameMillis/1000}==true)
            return reject(StrategyActionRejection.BUSY)
        val choices=candidates(galaxy,p,stocks)
        val base=if(r.base==0)choices.singleOrNull() else choices.singleOrNull {it.id==r.base}
        if(base==null)return reject(StrategyActionRejection.TARGET)
        val secured=stocks.bases.getValue(base.id).copy(owner=Allegiance.Independent(p.characterId))
        val landed=p.copy(mode=4,baseId=base.id,spotId=0,spotOwner=0,moves=Math.addExact(p.moves,1))
        return StrategyActionResult(landed,stocks.copy(bases=stocks.bases+(base.id to secured)),changed=true)
    }
}
