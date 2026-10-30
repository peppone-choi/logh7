package org.logh7.engine

/** Commander-led deployment uses the same durable transaction as the flagship. Author: 최병호. */
internal object FleetNavigation {
    fun apply(request: NavigationRequest, session: SessionState, galaxy: Galaxy, navigation: Navigation,
        production: PlanetProduction, stocks: PlanetStocks, committer: WorldStateCommitter?, blocked: (Int) -> Boolean): NavigationResult {
        val players = navigation.snapshot(session.generation)
        val before = players[request.account]
        fun personal(movingUnits:Int=1,soloShip:Boolean=true):NavigationResult {
            if(before==null || stocks.civic.active.none {it.character==before.characterId})
                return navigation.apply(request,session,stocks,movingUnits=movingUnits,soloShip=soloShip)
            if(committer==null)return NavigationResult(null,before,NavigationRejection.DISABLED)
            val result=navigation.apply(request,session,stocks,persist=false,movingUnits=movingUnits,soloShip=soloShip)
            val after=result.player ?: return result
            val next=players+(request.account to after)
            val settled=CivicActions.settle(galaxy,stocks,session.gameMillis/1000,next.values)
            val prepared=production.prepareReplacement(stocks,settled)
            committer.saveWorld(next,prepared)
            navigation.publishCommitted(players,next,session.generation);production.publishCommitted(stocks,prepared)
            return result
        }
        // Personal flagship retreat must not transport the commander's unimplemented fleet/CRC selection.
        if (request.tacticalRetreat != null || before?.retreat != null) return navigation.apply(request, session, stocks)
        val outfit = before?.let { player -> stocks.outfits.values.singleOrNull {
            it.commanderId == player.characterId && it.kind != OutfitKind.GROUND &&
                it.power == (if (player.power == Power.EMPIRE) 2 else 3) &&
                it.positionIn(galaxy).let { position -> position.grid == player.grid && position.baseId == player.baseId }
        } }
        if (before == null || outfit == null) return personal()
        val followers = players.filter { (account, player) ->
            account != request.account && player.power == before.power && player.characterId in outfit.members() &&
                player.grid == before.grid && player.baseId == before.baseId && player.mode in 4..5 &&
                stocks.outfits.values.count { player.characterId in it.members() } == 1 &&
                (player.spotId == 0 || player.spotOwner == 0 && galaxy.facilities[player.baseId].orEmpty().any { facility ->
                    facility.kind == 4 && facility.spots.any { it.id == player.spotId && it.kind == 6 }
                })
        }
        val units = (GridForces.mobileUnits(outfit) + 1 + followers.size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val preview = navigation.apply(request, session, stocks, persist = false, movingUnits = units, soloShip = false)
        val after = preview.player ?: preview.previous ?: return preview
        val position = OutfitPosition(after.grid, after.baseId)
        if (position == outfit.positionIn(galaxy))
            return personal(units,false)
        if (committer == null) return NavigationResult(null, before, NavigationRejection.DISABLED)
        if ((request.destination != null || request.place == PlaceMove.SwitchMode(5)) && blocked(outfit.id))
            return NavigationResult(null, before, NavigationRejection.BUSY)
        if (followers.values.any { it.cpPaused || it.fuelReadyAt != null || it.dockTarget != null })
            return NavigationResult(null, before, NavigationRejection.BUSY)
        val cruiseCost = if (request.destination != null) (before.cruise - after.cruise).coerceAtLeast(0) else 0
        if (followers.values.any { it.cruise < cruiseCost }) return NavigationResult(null, before, NavigationRejection.CRUISE)
        val moved = followers.mapValues { (_, player) -> player.copy(grid = after.grid, baseId = after.baseId,
            mode = after.mode, spotId = 0, spotOwner = 0, cruise = player.cruise - cruiseCost,
            cpPaused = after.cpPaused, cpRecoveryAt = if(player.cpPaused != after.cpPaused)session.gameMillis/1000 else player.cpRecoveryAt,
            commandRange=if(after.cpPaused)TacticalCommandRange(session.gameMillis/1000,command=player.commandRange?.command ?: 50) else null,
            moves = Math.addExact(player.moves, 1), motion = null) }
        val next = players + moved + (request.account to after)
        val deployed=stocks.copy(outfits = stocks.outfits + (outfit.id to outfit.copy(position = position)))
        val prepared = production.prepareReplacement(stocks,CivicActions.settle(galaxy,deployed,session.gameMillis/1000,next.values))
        committer.saveWorld(next, prepared)
        navigation.publishCommitted(players, next, session.generation)
        production.publishCommitted(stocks, prepared)
        return preview
    }
}
