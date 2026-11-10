package org.logh7.engine

/** Manual W pp.51-52. Warehouse caps and sortie offsets are revival policy. Author: Peppone Choi. */
data class BaseOccupation(val generation:Long,val grid:Int,val base:Int,val previousOwner:Allegiance,val country:CountryId,
    val seconds:Long,val destroyedShips:Map<Int,Int>,val expelled:Map<String,Navigator>,
    val recipients:Map<Int,String>,val camp:Int=0)

internal object BaseOccupationOperations {
    fun victor(stocks:PlanetStocks,base:GalaxyBase,eliminated:Set<CountryId> = emptySet()):Camp? {
        val owner=stocks.bases.getValue(base.id)
        // U-07 (CD manual p.39): armed occupation of neutral Fezzan ends its neutrality; it becomes the occupier's planet.
        if(owner.country==null && owner.owner !is Allegiance.Independent &&
            !(owner.owner==Allegiance.Neutral && base.name==TradeActions.FEZZAN_NAME))return null
        val winner=GroundBattleOperations.sides(stocks,base,eliminated).singleOrNull()?.first ?: return null
        return Camp(winner.first,winner.second==1).takeUnless {it==TacticalAuthority.camp(owner)}
    }
    fun pending(stocks:PlanetStocks,galaxy:Galaxy,eliminated:Set<CountryId>)=
        galaxy.bases.any {victor(stocks,it,eliminated)!=null}

    fun advance(session:SessionState,galaxy:Galaxy,players:MutableMap<String,Navigator>,original:PlanetStocks,
        maximumStock:Int,scale:Long,events:MutableList<BaseOccupation>,
        commands:MutableList<TacticalCommandResult>):PlanetStocks {
        if(session.ended!=null)return original
        val now=session.gameMillis/1000
        var stocks=original
        for(base in galaxy.bases.sortedBy {it.id}) {
            val winningSide=victor(stocks,base,session.eliminated.keys) ?: continue
            val winner=checkNotNull(winningSide.country)
            val winningCamp=if(winningSide.insurgent)1 else 0
            // Occupation evaluation (design-evaluation-points.md): the victor's ground commanders here and its
            // tactical participants in the grid share it equally, before the occupation rewrites the outfits.
            val groundIds=GroundBattleOperations.participants(stocks,base,winner,winningCamp).map {it.id}.toSet()
            val commanders=stocks.outfits.values.filter {o->o.activeGround?.values.orEmpty().any {it.id in groundIds}}.map {it.commanderId}
            val earners=commanders+players.values.filter {TacticalAuthority.camp(it,stocks)==winningSide && it.generation==session.generation &&
                    it.grid==base.grid && it.cpPaused}.map {it.characterId}
            val previous=stocks.bases.getValue(base.id)
            val loser=previous.country
            val losingSide=TacticalAuthority.camp(previous)
            stocks=StrategicOperations.recordDefenseOccupation(galaxy,stocks,players.values,base,losingSide,winningSide,now)
            val coups=stocks.social.coup
            val winningPlot=if(winningSide.insurgent)coups.plots.values.filter {plot->
                plot.revolt>0 && plot.country==winner && plot.rebels.any {it in commanders}
            }.minByOrNull {it.leader} else null
            val plots=coups.plots.mapValues {(_,plot)->
                if(plot==winningPlot)plot.copy(bases=plot.bases+base.id)
                else if(base.id in plot.bases)plot.copy(bases=plot.bases-base.id)
                else plot
            }
            val destroyed=linkedMapOf<Int,Int>()
            val outfits=stocks.outfits.mapValues {(_,outfit)->
                if(TacticalAuthority.camp(outfit,stocks)!=losingSide)return@mapValues outfit
                var changed=outfit
                val ships=outfit.activeShips
                if(ships!=null)for(unit in ships.values) {
                    val stationed=unit.posture?.let {it.grid==base.grid && it.base==base.id &&
                        it.mode==TacticalShipMode.STATIONED} ?: (outfit.positionIn(galaxy)==OutfitPosition(base.grid,base.id))
                    if(stationed && unit.boats>0) {
                        destroyed[unit.id]=unit.boats
                        changed=TacticalLosses.apply(changed,unit.id,unit.boats)
                    }
                }
                if(ships==null && outfit.positionIn(galaxy)==OutfitPosition(base.grid,base.id)) {
                    val roster=changed.activeGround?.let {ActiveGroundUnits(it.values.filter {u->u.base>0})}
                    changed=changed.copy(units=UnitInventory(troops=roster?.quantities().orEmpty()),
                        activeShips=ActiveShipUnits(emptyList()),activeGround=roster,supplies=0)
                }
                // Landed reserves belong to the defeated garrison regardless of their outfit kind.
                val old=changed.activeGround
                if(old!=null && old.values.any {it.base==base.id}) {
                    val roster=ActiveGroundUnits(old.values.filter {it.base!=base.id})
                    changed=changed.copy(activeGround=roster,units=UnitInventory(changed.units.ships,
                        changed.units.troops.filterKeys {!GroundUnits.isGround(it.kind)}+roster.quantities()))
                }
                changed
            }
            // Warehouses are keyed by country, so an internal camp change has no foreign stock to seize.
            val localWarehouses=stocks.warehouses.values.filter {it.baseId==base.id && it.country==loser && loser!=winner}
            // Unladen packages are still stored locally. Use the existing capture rules for their contents.
            val (localPackages,otherPackages)=stocks.strategic.transports.partition {
                loser!=winner && it.country==loser && it.source==base.id && it.carrier==0
            }
            // Same finite capacity as production. Accumulate in Long before applying the cap.
            val supplies=(previous.supplies.toLong()+localWarehouses.sumOf {it.supplies.toLong()}+
                localPackages.sumOf {it.supplies.toLong()}).coerceAtMost(maxOf(previous.supplies,maximumStock).toLong()).toInt()
            val troops=previous.units.troops.toMutableMap()
            for(pack in localPackages.sortedBy {it.id})for((stack,count) in pack.units.troops) {
                // Preserve existing stacks first, within the native 24-stack and u16 limits.
                if(stack in troops || troops.size<24)
                    troops[stack]=((troops[stack] ?: 0)+count).coerceAtMost(65535)
            }
            val warehouses=stocks.warehouses.mapValues {(_,warehouse)->
                if(warehouse.baseId!=base.id || warehouse.country!=loser || loser==winner)warehouse else warehouse.copy(
                    supplies=0,units=UnitInventory(troops=warehouse.units.troops),spareShips=SpareShipBoats())
            }
            val occupied=previous.copy(owner=Allegiance.Country(winner),camp=winningCamp,supplies=supplies,
                governorId=0,garrisonCommanderId=0,capitalAdministratorId=0,capitalDefenseCommanderId=0,guardInspectorId=0,
                // Captured works restart, including recruitment; never revive progress on recapture.
                units=UnitInventory(troops=troops),productionCountry=null,
                spareShips=SpareShipBoats())
            val cancelled=stocks.tacticalCommands.orders.filter {it.command.base==base.id}
            val capital=Capital.of(base)
            stocks=stocks.copy(bases=stocks.bases+(base.id to occupied),outfits=outfits,warehouses=warehouses,
                logisticsOfficers=stocks.logisticsOfficers.filterValues {it.capital!=capital},
                nationalOffices=stocks.nationalOffices.filterKeys {it.capital!=capital},
                groundBattles=stocks.groundBattles-base.id,
                tacticalCommands=stocks.tacticalCommands.copy(orders=stocks.tacticalCommands.orders-cancelled.toSet(),
                    emergencySupplies=stocks.tacticalCommands.emergencySupplies-base.id),
                strategic=stocks.strategic.copy(suppressions=stocks.strategic.suppressions.filter {it.base!=base.id},
                    fiefs=stocks.strategic.fiefs-base.id,transports=otherPackages,
                    lastTransportId=if(localPackages.isEmpty())stocks.strategic.lastTransportId else stocks.strategic.transportIdFloor,
                    offices=stocks.strategic.offices.filter {holding ->
                        if(OrganizationOffices.of(holding.card)?.scope==OfficeScope.OUTFIT)
                            stocks.outfits[holding.seat]?.let {it.baseId==base.id && TacticalAuthority.camp(it,stocks)==losingSide}!=true else holding.seat!=base.id
                    }),
                // U-08: like automatic production, a pending manual ship order does not survive the capture.
                social=stocks.social.copy(coup=coups.copy(plots=plots),production=stocks.social.production.let {it.copy(orders=it.orders-base.id)}))
            // User decision 2026-11-03: the new owner repairs the fortress armor and propulsion (FortressArmor).
            stocks=FortressArmor.restore(stocks,base.id)
            val expelled=linkedMapOf<String,Navigator>()
            var offset=0
            for((account,p) in players.toMap()) {
                if(p.generation!=session.generation)continue
                var next=if(p.returnBase==base.id && !TacticalAuthority.ownsBase(p,occupied.owner,occupied.camp,
                    CoupActions.camp(stocks,p.characterId,p.country)))
                    p.copy(returnBase=0) else p
                val displaced=when(val owner=previous.owner) {
                    is Allegiance.Independent -> p.country==null && p.characterId==owner.characterId
                    is Allegiance.Country -> TacticalAuthority.camp(p,stocks)==losingSide
                    else -> false
                }
                if(displaced && p.dockTarget==base.id)next=next.copy(dockTarget=null,strategy=null)
                if (displaced && p.baseId == base.id && p.grid == base.grid &&
                    stocks.social.justice.detentions[p.characterId]?.holdsAt(galaxy, p) != true) {
                    val centre=TacticalBasePosition.at(base,now,stocks)
                    val point=TacticalPoint(centre.x+base.diameter+2f+offset++*2f,centre.y)
                    val pose=TacticalPose(point,0f)
                    next=next.copy(baseId=0,mode=5,spotId=0,spotOwner=0,cpPaused=true,cpRecoveryAt=now,
                        fuelReadyAt=null,strategy=null,dockTarget=null,warpTarget=null,retreat=null,attack=null,
                        fire=next.fire?.copy(pending=null),
                        motion=TacticalMotion(base.grid,now,pose,0f,pose.direction,listOf(point)),
                        posture=TacticalPosture(base.grid,TacticalShipMode.ANCHORED,base.id),
                        charge=TacticalCharge(now,clockScale=scale),commandRange=TacticalCommandRange(now))
                    expelled[account]=next
                }
                if(next!=p)players[account]=next
            }
            val recipients=session.participants.mapNotNull {(account,member)->players[account]?.takeIf {
                member.online && member.connection!=null && it.generation==session.generation
            }?.let {it.characterId to checkNotNull(member.connection)}}.toMap()
            for((character,points) in EvaluationPoints.shares(if(base.kind==1)EvaluationPoints.FORTRESS else EvaluationPoints.PLANET,earners))
                stocks=EvaluationPoints.award(stocks,character,points)
            stocks=GoalActions.achieved(stocks,base.id,winner,earners)
            for(order in cancelled) {
                val audience=players.values.filter {it.grid==order.grid && (it.cpPaused || it.baseId>0)}
                    .map {it.characterId}.toSet()
                commands+=TacticalCommandResult(session.generation,order,now,recipients.filterKeys {it in audience},true)
            }
            events+=BaseOccupation(session.generation,base.grid,base.id,previous.owner,winner,now,destroyed,expelled,recipients,winningCamp)
        }
        return stocks
    }
}
