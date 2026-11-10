package org.logh7.engine

/** Commander-led deployment uses the same durable transaction as the flagship. Author: Peppone Choi. */
internal object FleetNavigation {
    fun apply(request: NavigationRequest, session: SessionState, galaxy: Galaxy, navigation: Navigation,
        production: PlanetProduction, stocks: PlanetStocks, committer: WorldStateCommitter?,
        initialProfile:StrategicPerson?=null,blocked: (Int) -> Boolean): NavigationResult {
        if (BattleDeath.deceased(stocks, request.characterId)) return navigation.apply(request, session, stocks)
        val players = navigation.snapshot(session.generation)
        val saved = players[request.account]
        val before = saved?.takeIf { it.blocksFirstEntry(request,stocks) }
        val claim=OriginalBerthClaim(request.account,request.characterId,request.country,request.rank,request.startingOffice)
        val placement=claim.berth()
        val startingRole=placement?.second
        val startingFleet=if(request.initializeOriginal && request.shipKind!=null &&
            session.participants[request.account]?.original==true && before==null && committer!=null)
            placement?.first?.let {stocks.outfits[it.fleetId]}?.takeIf {claim.vacantFleet(it,stocks,galaxy,players)!=null}
        else null
        val startingPerson=initialProfile?.takeIf {committer!=null && before==null &&
            it.peerage!=null && request.characterId !in stocks.strategic.people}?.let {person->
            val supports=request.country?.let {CountryCatalogLoader.current().supportsNativePeerage(it)}==true
            person.copy(peerage=person.peerage.takeIf {supports})
        }
        fun configured(inventory:PlanetStocks,player:Navigator)=request.offlineDirection?.let {mask ->
            inventory.copy(tacticalCommands=inventory.tacticalCommands.copy(
                aiSwitches=inventory.tacticalCommands.aiSwitches+(player.characterId to mask)))
        } ?: inventory
        fun endDepartedActivities(inventory: PlanetStocks, before: Navigator?, after: Navigator): PlanetStocks =
            EspionageActions.endDepartedInfiltration(AcademyActions.endDepartedLecture(inventory, before, after), before, after)
        fun commitPersonal(result:NavigationResult, inventory: PlanetStocks? = null):NavigationResult {
            val after=result.player ?: return result
            if(committer==null)return NavigationResult(null,saved ?: after,NavigationRejection.DISABLED)
            val next=players+(request.account to after)
            val settled=CivicActions.settle(galaxy,inventory ?: endDepartedActivities(stocks, saved, after),session.gameMillis/1000,next.values)
            val updated=configured(settled,after)
            val withPerson=if(startingPerson!=null && result.rejection==null)
                updated.copy(strategic=updated.strategic.copy(people=updated.strategic.people+(after.characterId to startingPerson)))
            else updated
            val assigned=if(startingFleet!=null && result.rejection==null)withPerson.copy(outfits=withPerson.outfits+
                (startingFleet.id to checkNotNull(startingRole).assign(startingFleet,after.characterId))) else withPerson
            val withAuthority=TacticalEntryAssignment.settle(session,galaxy,players,next,assigned) {
                production.identities?.invoke().orEmpty()
            }
            val prepared=production.prepareReplacement(stocks,withAuthority)
            committer.saveWorld(next,prepared)
            navigation.publishCommitted(players,next,session.generation);production.publishCommitted(stocks,prepared)
            return result
        }
        fun personal(movingUnits:Int=1,soloShip:Boolean=true):NavigationResult {
            val needsSharedCommit=startingFleet!=null || startingPerson!=null || request.offlineDirection!=null ||
                saved!=null && stocks.civic.active.any {it.character==saved.characterId}
            val hasSpotActivity=request.place!=null && (saved?.characterId in stocks.social.academy.lectures ||
                saved?.characterId in stocks.social.justice.infiltrations)
            if(committer==null && !needsSharedCommit && !hasSpotActivity)
                return navigation.apply(request,session,stocks,movingUnits=movingUnits,soloShip=soloShip)
            val preview=navigation.apply(request,session,stocks,persist=false,movingUnits=movingUnits,soloShip=soloShip,
                initialDock=startingFleet?.positionIn(galaxy))
            val inventory=preview.player?.let { endDepartedActivities(stocks, saved, it) } ?: stocks
            val after=preview.player
            val entryChanged=after!=null && (saved?.cpPaused!=after.cpPaused || saved.grid!=after.grid ||
                saved.characterId!=after.characterId) &&
                (saved?.cpPaused==true || after.cpPaused)
            val withAuthority=if(entryChanged && !needsSharedCommit)
                TacticalEntryAssignment.settle(session,galaxy,players,players+(request.account to after),inventory) {
                    production.identities?.invoke().orEmpty()
                }
            else inventory
            if(!needsSharedCommit && withAuthority === stocks)
                return navigation.apply(request,session,stocks,movingUnits=movingUnits,soloShip=soloShip)
            return commitPersonal(preview,withAuthority)
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
        val preview = navigation.apply(request, session, stocks, persist = false, movingUnits = units, soloShip = false,
            warpForcesAfterArrival = { arrived ->
                // The previous leg has moved these units in the preview, but they are still at the origin in storage.
                val arrivedOutfit = outfit.copy(position = OutfitPosition(arrived.grid, arrived.baseId))
                val arrivedPlayers = players + followers.mapValues { (_, follower) ->
                    follower.copy(grid = arrived.grid, baseId = arrived.baseId, mode = arrived.mode)
                } + (request.account to arrived)
                val arrivedStocks = stocks.copy(outfits = stocks.outfits + (outfit.id to arrivedOutfit))
                GridForces.collect(galaxy, session.generation, arrivedPlayers.values, arrivedStocks, session.eliminated.keys)
            })
        val after = preview.player ?: preview.previous ?: return preview
        val position = OutfitPosition(after.grid, after.baseId)
        val starting = preview.rejection == null && (request.destination != null || request.place is PlaceMove.Dock ||
            request.place == PlaceMove.SwitchMode(5))
        val now = session.gameMillis / 1000
        if ((starting || position != outfit.positionIn(galaxy)) && followers.values.any {
            it.cpPaused || it.navigationPending || (it.hull?.woundedUntil ?: 0) > now
        })
            return deferred(NavigationRejection.BUSY)
        // A reserved warp moves the fleet when its job completes, not on the accepting request.
        val cruiseCost = if (request.destination != null || preview.completedWarp) (before.cruise - after.cruise).coerceAtLeast(0) else 0
        fun refuseWarp(): NavigationResult {
            if(request.destination==null || !preview.completedWarp)return deferred(NavigationRejection.CRUISE)
            // Settle the due leg on its own so refusing the follow-up cannot undo its arrival.
            val arrived=apply(request.copy(destination=null),session,galaxy,navigation,production,stocks,committer,initialProfile,blocked)
            return if(arrived.player==null)arrived else arrived.copy(player=null,previous=arrived.player,rejection=NavigationRejection.CRUISE)
        }
        if (starting && request.destination != null && after.warpTarget == request.destination) {
            val reservedCruise = navigation.warpCruise(after.grid, request.destination)
            if (followers.values.any { it.cruise - cruiseCost < reservedCruise }) return refuseWarp()
        }
        val arrivedFromWarp = preview.completedWarp && preview.warpCancellation == null
        if (position == outfit.positionIn(galaxy) && !arrivedFromWarp)
            return personal(units,false)
        if (committer == null) return NavigationResult(null, before, NavigationRejection.DISABLED)
        val departing = request.place == PlaceMove.SwitchMode(5) || request.place == PlaceMove.Dock(0, 5)
        if ((request.destination != null || departing) && blocked(outfit.id))
            return NavigationResult(null, before, NavigationRejection.BUSY)
        if (followers.values.any { it.cruise < cruiseCost }) return refuseWarp()
        val departureBase = before.baseId.takeIf { it != 0 && after.baseId == 0 && after.cpPaused }
            ?.let { id -> galaxy.bases.single { it.id == id } }
        var slot = 1
        val moved = followers.entries.sortedBy { it.value.unitId }.associate { (account, player) -> account to player.copy(
            grid = after.grid, baseId = after.baseId,
            mode = after.mode, spotId = 0, spotOwner = 0, cruise = player.cruise - cruiseCost,
            cpPaused = after.cpPaused, cpRecoveryAt = if (player.cpPaused != after.cpPaused) now else player.cpRecoveryAt,
            commandRange = if (after.cpPaused) TacticalCommandRange(now, command = player.commandRange?.command ?: 50) else null,
            moves = Math.addExact(player.moves, after.moves - before.moves),
            motion = departureBase?.let { TacticalDeployment.departure(it, now, stocks, slot++) },
            posture = player.posture.takeIf { departureBase == null && !arrivedFromWarp && player.grid == after.grid }) }
        val next = players + moved + (request.account to after)
        fun pulled(target: Int?, arrival: Int) = target != null && target != arrival &&
            BlackHoleTides.inGravity(galaxy, target) && BlackHoleTides.inGravity(galaxy, arrival)
        // A new warp request can also settle a previously reserved leg; its previous state is that leg's arrival.
        val nextOrigin = preview.previous?.grid ?: before.grid
        val reservedArrival = if (request.destination == null) after.grid else nextOrigin
        val reservedPulled = preview.completedWarp && preview.warpCancellation == null && pulled(before.warpTarget, reservedArrival)
        val immediatePulled = preview.rejection == null && after.warpTarget == null && after.grid != nextOrigin &&
            pulled(request.destination, after.grid)
        val ships = departureBase?.let { base ->
            outfit.activeShips?.values?.let { units -> ActiveShipUnits(units.map { unit ->
                if (unit.boats == 0 || unit.posture?.let { it.grid == base.grid && it.base == base.id } == true) unit
                else unit.copy(tactical = NpcTacticalState(TacticalDeployment.departure(base, now, stocks, slot++), now, now),
                    posture = null, motionCommander = 0, support = null, attack = null, shot = null)
            }) }
        } ?: outfit.activeShips
        val moving = outfit.copy(position = position, activeShips = ships)
            .let { if (reservedPulled) BlackHoleTides.loss(it, BlackHoleTides.PULL_PERCENT) else it }
            .let { if (immediatePulled) BlackHoleTides.loss(it, BlackHoleTides.PULL_PERCENT) else it }
        val deployed=stocks.copy(outfits = stocks.outfits + (outfit.id to moving))
        val departed = moved.entries.fold(endDepartedActivities(deployed, before, after)) { inventory, (account, follower) ->
            endDepartedActivities(inventory, followers.getValue(account), follower)
        }
        val settled=CivicActions.settle(galaxy,departed,session.gameMillis/1000,next.values)
        val withAuthority=TacticalEntryAssignment.settle(session,galaxy,players,next,configured(settled,after)) {
            production.identities?.invoke().orEmpty()
        }
        val prepared = production.prepareReplacement(stocks,withAuthority)
        committer.saveWorld(next, prepared)
        navigation.publishCommitted(players, next, session.generation)
        production.publishCommitted(stocks, prepared)
        return preview
    }
}
