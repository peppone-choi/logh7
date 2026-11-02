package org.logh7.engine

/** Owner-authenticated settings and navigation share the durable world transaction. Author: Peppone Choi. */
internal object TacticalOfflineSettings {
    fun accept(request:NavigationRequest,session:SessionState,navigation:Navigation,production:PlanetProduction,
        stocks:PlanetStocks,committer:WorldStateCommitter?):NavigationResult {
        val preview=navigation.apply(request.copy(offlineDirection=null),session,stocks,persist=false)
        val player=preview.player ?: return preview
        if(committer==null)return NavigationResult(null,player,NavigationRejection.DISABLED)
        val players=navigation.snapshot(session.generation)
        val next=players+(request.account to player)
        val settings=stocks.tacticalCommands.aiSwitches+(player.characterId to checkNotNull(request.offlineDirection))
        val prepared=production.prepareReplacement(stocks,stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(aiSwitches=settings)))
        committer.saveWorld(next,prepared)
        navigation.publishCommitted(players,next,session.generation)
        production.publishCommitted(stocks,prepared)
        return preview
    }
}
