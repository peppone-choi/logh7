package org.logh7.engine

/** Commander-led deployment uses the same durable transaction as the flagship. Author: 최병호. */
internal object FleetNavigation {
    fun apply(request: NavigationRequest, session: SessionState, galaxy: Galaxy, navigation: Navigation,
        production: PlanetProduction, stocks: PlanetStocks, committer: WorldStateCommitter?, blocked: (Int) -> Boolean): NavigationResult {
        val before = navigation.snapshot(session.generation)[request.account]
        val outfit = before?.let { player -> stocks.outfits.values.singleOrNull {
            it.commanderId == player.characterId && it.kind != OutfitKind.GROUND &&
                it.power == (if (player.power == Power.EMPIRE) 2 else 3) &&
                it.positionIn(galaxy).let { position -> position.grid == player.grid && position.baseId == player.baseId }
        } }
        if (before == null || outfit == null) return navigation.apply(request, session, stocks)
        val units = (GridForces.units(outfit) + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val preview = navigation.apply(request, session, stocks, persist = false, movingUnits = units, soloShip = false)
        val after = preview.player ?: preview.previous ?: return preview
        val position = OutfitPosition(after.grid, after.baseId)
        if (position == outfit.positionIn(galaxy))
            return navigation.apply(request, session, stocks, movingUnits = units, soloShip = false)
        if (committer == null) return NavigationResult(null, before, NavigationRejection.DISABLED)
        if ((request.destination != null || request.place == PlaceMove.SwitchMode(5)) && blocked(outfit.id))
            return NavigationResult(null, before, NavigationRejection.BUSY)
        val prepared = production.prepareReplacement(stocks,
            stocks.copy(outfits = stocks.outfits + (outfit.id to outfit.copy(position = position))))
        committer.saveWorld(navigation.snapshot(session.generation) + (request.account to after), prepared)
        navigation.publishCommitted(request.account, before, after)
        production.publishCommitted(stocks, prepared)
        return preview
    }
}
