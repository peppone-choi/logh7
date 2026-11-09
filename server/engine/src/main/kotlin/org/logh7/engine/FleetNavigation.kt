package org.logh7.engine

/** Commander-led deployment uses the same durable transaction as the flagship. Author: Peppone Choi. */
internal object FleetNavigation {
    fun apply(request: NavigationRequest, session: SessionState, galaxy: Galaxy, navigation: Navigation,
        production: PlanetProduction, stocks: PlanetStocks, committer: WorldStateCommitter?,
        initialProfile:StrategicPerson?=null,blocked: (Int) -> Boolean): NavigationResult {
        if (BattleDeath.deceased(stocks, request.characterId)) return navigation.apply(request, session, stocks)
        val players = navigation.snapshot(session.generation)
        val before = players[request.account]
        // Manual pp.60/75: Kircheis is the aide of the Empire's Second Fleet at Odin.
        val startingFleet=stocks.outfits[2]?.takeIf {fleet->
            request.initializeOriginal && request.shipKind!=null && request.characterId==1001 && request.country==Power.EMPIRE.country &&
                session.participants[request.account]?.original==true && before==null && committer!=null &&
                players.values.none {it.characterId==request.characterId} && !BattleDeath.deceased(stocks,request.characterId) &&
                fleet.country==request.country && fleet.kind==OutfitKind.FLEET && fleet.number==2 && fleet.baseId==1049 &&
                fleet.positionIn(galaxy).baseId==1049 && fleet.aideId==0 && CoupActions.outfitCamp(stocks,fleet)==0 &&
                stocks.bases[1049]?.let {it.country==request.country && it.camp==0}==true &&
                stocks.outfits.values.none {request.characterId in it.members()}
        }
        val startingPerson=initialProfile?.takeIf {committer!=null && before==null &&
            it.peerage!=null && request.characterId !in stocks.strategic.people}?.let {person->
            val supports=request.country?.let {CountryCatalogLoader.current().supportsNativePeerage(it)}==true
            person.copy(peerage=person.peerage.takeIf {supports})
        }
        fun configured(inventory:PlanetStocks,player:Navigator)=request.offlineDirection?.let {mask ->
            inventory.copy(tacticalCommands=inventory.tacticalCommands.copy(
                aiSwitches=inventory.tacticalCommands.aiSwitches+(player.characterId to mask)))
        } ?: inventory
        fun commitPersonal(result:NavigationResult):NavigationResult {
            val after=result.player ?: return result
            if(committer==null)return NavigationResult(null,before ?: after,NavigationRejection.DISABLED)
            val next=players+(request.account to after)
            val settled=CivicActions.settle(galaxy,stocks,session.gameMillis/1000,next.values)
            val updated=configured(settled,after)
            val withPerson=if(startingPerson!=null && result.rejection==null)
                updated.copy(strategic=updated.strategic.copy(people=updated.strategic.people+(after.characterId to startingPerson)))
            else updated
            val assigned=if(startingFleet!=null && result.rejection==null)withPerson.copy(outfits=withPerson.outfits+
                (startingFleet.id to FleetOffice.EMPIRE_AIDE.assign(startingFleet,after.characterId))) else withPerson
            val prepared=production.prepareReplacement(stocks,assigned)
            committer.saveWorld(next,prepared)
            navigation.publishCommitted(players,next,session.generation);production.publishCommitted(stocks,prepared)
            return result
        }
        fun personal(movingUnits:Int=1,soloShip:Boolean=true):NavigationResult {
            if(startingFleet==null && startingPerson==null && request.offlineDirection==null && (before==null || stocks.civic.active.none {it.character==before.characterId}))
                return navigation.apply(request,session,stocks,movingUnits=movingUnits,soloShip=soloShip)
            return commitPersonal(navigation.apply(request,session,stocks,persist=false,movingUnits=movingUnits,soloShip=soloShip,
                initialDock=startingFleet?.positionIn(galaxy)))
        }
        // Personal flagship retreat must not transport the commander's unimplemented fleet/CRC selection.
        if (request.tacticalRetreat != null || before?.retreat != null)
            return if(request.offlineDirection!=null)personal() else navigation.apply(request, session, stocks)
        val outfit = before?.let { player -> stocks.outfits.values.singleOrNull {
            it.commanderId == player.characterId && it.kind != OutfitKind.GROUND &&
                player.country!=null && it.country == player.country &&
                TacticalAuthority.camp(it,stocks) == TacticalAuthority.camp(player,stocks) &&
                it.positionIn(galaxy).let { position -> position.grid == player.grid && position.baseId == player.baseId }
        } }
        if (before == null || outfit == null) return personal()
        val camp = TacticalAuthority.camp(outfit,stocks)
        fun deferred(reason:NavigationRejection)=if(request.offlineDirection!=null)
            commitPersonal(NavigationResult(before,before)) else NavigationResult(null,before,reason)
        val followers = players.filter { (account, player) ->
            account != request.account && player.country == before.country && player.characterId in outfit.members() &&
                TacticalAuthority.camp(player,stocks) == camp &&
                player.grid == before.grid && player.baseId == before.baseId && player.mode in 4..5 &&
                !stocks.social.justice.detained(player.characterId) &&
                stocks.outfits.values.count {
                    it.country == player.country && player.characterId in it.members() && TacticalAuthority.camp(it,stocks) == camp
                } == 1 &&
                (player.spotId == 0 || player.spotOwner == 0 && galaxy.facilities[player.baseId].orEmpty().any { facility ->
                    facility.kind == 4 && facility.spots.any { it.id == player.spotId && it.kind == 6 }
                })
        }
        val units = (GridForces.mobileUnits(outfit) + 1 + followers.size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val preview = navigation.apply(request, session, stocks, persist = false, movingUnits = units, soloShip = false)
        val after = preview.player ?: preview.previous ?: return preview
        val position = OutfitPosition(after.grid, after.baseId)
        val starting = preview.rejection == null && (request.destination != null || request.place is PlaceMove.Dock ||
            request.place == PlaceMove.SwitchMode(5))
        val now = session.gameMillis / 1000
        if ((starting || position != outfit.positionIn(galaxy)) && followers.values.any {
            it.cpPaused || it.navigationPending || (it.hull?.woundedUntil ?: 0) > now
        })
            return deferred(NavigationRejection.BUSY)
        if (starting && request.destination != null && after.warpTarget == request.destination) {
            val reservedCruise = navigation.warpCruise(after.grid, request.destination)
            if (followers.values.any { it.cruise < reservedCruise }) return deferred(NavigationRejection.CRUISE)
        }
        if (position == outfit.positionIn(galaxy))
            return personal(units,false)
        if (committer == null) return NavigationResult(null, before, NavigationRejection.DISABLED)
        if ((request.destination != null || request.place == PlaceMove.SwitchMode(5)) && blocked(outfit.id))
            return NavigationResult(null, before, NavigationRejection.BUSY)
        // A reserved warp moves the fleet when its job completes, not on the accepting request.
        val cruiseCost = if (request.destination != null || preview.completedWarp) (before.cruise - after.cruise).coerceAtLeast(0) else 0
        if (followers.values.any { it.cruise < cruiseCost }) return deferred(NavigationRejection.CRUISE)
        val departureBase = before.baseId.takeIf { it != 0 && after.baseId == 0 && after.cpPaused }
            ?.let { id -> galaxy.bases.single { it.id == id } }
        var slot = 1
        val moved = followers.entries.sortedBy { it.value.unitId }.associate { (account, player) -> account to player.copy(
            grid = after.grid, baseId = after.baseId,
            mode = after.mode, spotId = 0, spotOwner = 0, cruise = player.cruise - cruiseCost,
            cpPaused = after.cpPaused, cpRecoveryAt = if (player.cpPaused != after.cpPaused) now else player.cpRecoveryAt,
            commandRange = if (after.cpPaused) TacticalCommandRange(now, command = player.commandRange?.command ?: 50) else null,
            moves = Math.addExact(player.moves, 1),
            motion = departureBase?.let { TacticalDeployment.departure(it, now, stocks, slot++) },
            posture = if (departureBase == null) player.posture else null) }
        val next = players + moved + (request.account to after)
        // A reserved warp whose error pulled the fleet into a black hole's gravity zone damages it on arrival.
        val pulled = preview.completedWarp && before.warpTarget != null && after.grid != before.warpTarget &&
            BlackHoleTides.inGravity(galaxy, after.grid)
        val ships = departureBase?.let { base ->
            outfit.activeShips?.values?.let { units -> ActiveShipUnits(units.map { unit ->
                if (unit.boats == 0 || unit.posture?.let { it.grid == base.grid && it.base == base.id } == true) unit
                else unit.copy(tactical = NpcTacticalState(TacticalDeployment.departure(base, now, stocks, slot++), now, now),
                    posture = null, motionCommander = 0, support = null, attack = null, shot = null)
            }) }
        } ?: outfit.activeShips
        val moving = outfit.copy(position = position, activeShips = ships)
            .let { if (pulled) BlackHoleTides.loss(it, BlackHoleTides.PULL_PERCENT) else it }
        val deployed=stocks.copy(outfits = stocks.outfits + (outfit.id to moving))
        val settled=CivicActions.settle(galaxy,deployed,session.gameMillis/1000,next.values)
        val prepared = production.prepareReplacement(stocks,configured(settled,after))
        committer.saveWorld(next, prepared)
        navigation.publishCommitted(players, next, session.generation)
        production.publishCommitted(stocks, prepared)
        return preview
    }
}
