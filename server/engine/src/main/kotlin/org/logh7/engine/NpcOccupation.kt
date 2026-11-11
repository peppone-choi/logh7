package org.logh7.engine

import kotlin.math.atan2
import kotlin.math.hypot

/** A national objective or a vacant government's expedition directs regular uncommanded carriers (evidence:guess). */
internal object NpcOccupation {
    private fun eligible(outfit:OutfitStock,stocks:PlanetStocks,eliminated:Set<CountryId>) =
        outfit.kind in setOf(OutfitKind.FLEET,OutfitKind.PATROL) && outfit.country !in eliminated &&
            !TacticalAuthority.camp(outfit,stocks).insurgent && TacticalAuthority.uncommanded(outfit,stocks)

    fun goal(outfit:OutfitStock,stocks:PlanetStocks,galaxy:Galaxy,eliminated:Set<CountryId>):GalaxyBase? {
        if(!eligible(outfit,stocks,eliminated))return null
        val order=stocks.social.goals.national[outfit.country.value]
        val id=order?.base ?: outfit.strategy?.expeditionBase ?: return null
        val owner=stocks.bases[id]?.country ?: return null
        if(owner==outfit.country || owner in eliminated)return null
        if(order==null && (outfit.kind!=OutfitKind.FLEET || outfit.guard!=null ||
            owner!=expeditionEnemy(outfit.country,stocks,galaxy,eliminated) || stocks.bases.getValue(id).camp!=0))return null
        return galaxy.bases.singleOrNull {it.id==id}
    }

    private val infantry = UnitSpecifications.crews.filter {it.type!=3}.associateBy {it.kind}
    fun expeditionEnemy(country:CountryId,stocks:PlanetStocks,galaxy:Galaxy,eliminated:Set<CountryId>):CountryId? {
        val enemy=when(country) {
            Power.EMPIRE.country -> Power.ALLIANCE.country
            Power.ALLIANCE.country -> Power.EMPIRE.country
            else -> return null
        }
        if(country in eliminated || stocks.social.goals.national.containsKey(country.value))return null
        if(enemy in eliminated)return null
        val offices=OrganizationalOfficePolicy(galaxy)
        if(offices.capital(stocks,enemy)==null || offices.capital(stocks,country)==null ||
            GoalActions.nationalCards.any {offices.holders(stocks,it,country).isNotEmpty()})return null
        return enemy
    }

    fun canLand(kind:Int,outfit:OutfitStock,target:GalaxyBase)=infantry[kind]?.let {
        it.power==outfit.shipStyle.nativePower && GroundBattleRules.admitsCrewType(target,it.type)
    }==true

    private fun available(ship:ActiveShipUnit,stocks:PlanetStocks) =
        ship.boats>ship.damaged && ship.support==null && ship.tactical?.retreat==null &&
            !stocks.tacticalCommands.confused(ship.id)

    private fun aboard(outfit:OutfitStock,ship:ActiveShipUnit,base:GalaxyBase) =
        outfit.activeGround?.values.orEmpty().filter {
            it.carrier==ship.id && GroundBattleOperations.admits(base,it)
        }

    fun hasCarrier(outfit:OutfitStock,base:GalaxyBase,stocks:PlanetStocks)=
        outfit.activeShips?.values.orEmpty().any { available(it,stocks) && aboard(outfit,it,base).isNotEmpty() }

    /** Reuse actual warehouse/base inventory and the same ground IDs and berths as manual loading. */
    fun loadAtHome(outfit:OutfitStock,goal:GalaxyBase,stocks:PlanetStocks,mayAllocate:()->Boolean):PlanetStocks {
        val ships=outfit.activeShips?.values.orEmpty().filter {available(it,stocks)}.sortedBy {it.id}
        if(ships.isEmpty() || hasCarrier(outfit,goal,stocks))return stocks
        val roster=outfit.activeGround ?: if(GroundUnits.quantities(outfit.units).isEmpty()) ActiveGroundUnits(emptyList())
            else return stocks // The world initializes historical quantities before offering them for transfer.
        val slots=GroundTransfer.freeBerths(roster,ships).values.sumOf {maxOf(0,it)}
            .coerceAtMost(GroundBattleRules.BOX_CAPACITY_PER_FACTION)
        if(slots==0)return stocks
        val kinds=infantry.values.filter {
            it.power==outfit.shipStyle.nativePower && GroundBattleRules.admitsCrewType(goal,it.type)
        }.map {it.kind}.toSet()
        val ready=roster.values.filter {it.carrier==0 && it.base==0 && it.order==null && it.stack.kind in kinds}
            .sortedBy {it.id}.take(slots)
        if(ready.isNotEmpty()) {
            val loaded=outfit.copy(activeGround=GroundTransfer.board(roster,ships,ready))
            return stocks.copy(outfits=stocks.outfits+(outfit.id to loaded))
        }
        val room=minOf(slots.toLong(),GridEntryRules.MAX_UNITS_PER_CAMP-GridForces.mobileUnits(outfit)).toInt()
        if(room<=0)return stocks
        val warehouse=stocks.warehouses.getValue(outfit.warehouseId)
        fun select(units:UnitInventory):Map<UnitStack,Int> {
            var remaining=room
            return buildMap {
                for((stack,count) in units.troops.entries.sortedWith(compareBy({it.key.kind},{it.key.level}))) {
                    if(remaining==0)break
                    if(stack.kind !in kinds)continue
                    val moved=minOf(remaining,count)
                    put(stack,moved)
                    remaining-=moved
                }
            }
        }
        val reserve=select(warehouse.units)
        val transfer=UnitTransfer(troops=reserve.ifEmpty {
            if(!mayAllocate())return stocks
            select(stocks.bases.getValue(outfit.baseId).units)
        })
        if(transfer.troops.isEmpty())return stocks
        return try {
            val staged=if(reserve.isNotEmpty())stocks else StockAssignment.applyTransfer(stocks,outfit,transfer,0)
            val commissioned=ReplacementBoats.reorganize(staged,outfit.id,transfer)
            val next=commissioned.outfits.getValue(outfit.id)
            val ground=checkNotNull(next.activeGround)
            val added=ground.values.filter {it.id>=stocks.nextShipId}
            check(added.size==transfer.troops.values.sum() && added.size<=slots)
            val loaded=next.copy(activeGround=GroundTransfer.board(ground,ships,added))
            commissioned.copy(outfits=commissioned.outfits+(outfit.id to loaded))
        } catch (_: IllegalArgumentException) {
            // Capacity, depleted stock or identity exhaustion leaves the source untouched.
            stocks
        }
    }

    fun canFinishPosture(outfit:OutfitStock,ship:ActiveShipUnit,state:TacticalPosture,order:TacticalPostureOrder,
        stocks:PlanetStocks,galaxy:Galaxy,eliminated:Set<CountryId>):Boolean {
        if(!eligible(outfit,stocks,eliminated) || outfit.positionIn(galaxy)!=OutfitPosition(state.grid) ||
            ship.boats==0)return false
        if(order.mode==TacticalShipMode.SAILING)return state.autonomousAnchor
        val base=goal(outfit,stocks,galaxy,eliminated) ?: return false
        return base.grid==state.grid && base.id==order.base && !state.mode.requiresBase &&
            available(ship,stocks) && aboard(outfit,ship,base).any {it.order==null}
    }

    fun advance(outfit:OutfitStock,grid:Int,session:SessionState,galaxy:Galaxy,players:Map<String,Navigator>,stocks:PlanetStocks,
        now:Long,scale:Long,movements:MutableList<TacticalMovement>,audience:()->Map<Int,String>):Pair<PlanetStocks,Set<Int>> {
        val original=outfit.activeShips ?: return stocks to emptySet()
        if(!eligible(outfit,stocks,session.eliminated.keys))return stocks to emptySet()
        val base=goal(outfit,stocks,galaxy,session.eliminated.keys)?.takeIf {it.grid==grid}
        if(base==null && original.values.none {it.posture?.autonomousAnchor==true})return stocks to emptySet()
        val camp=TacticalAuthority.camp(outfit,stocks)
        var room=if(base==null)0 else GroundBattleRules.BOX_CAPACITY_PER_FACTION-
            TacticalGroundOperations.landingSlotsUsed(base,camp,players,stocks,galaxy,now,session)
        var commands=stocks.tacticalCommands
        val reserved=mutableSetOf<Int>()
        val landings=mutableMapOf<Int,TacticalGroundOrder>()
        val ships=original.values.associateBy {it.id}.toMutableMap()
        for(ship in original.values.sortedBy {it.id}) {
            if(ship.boats==0)continue
            val state=ship.posture?.takeIf {it.grid==grid} ?: TacticalPosture(grid)
            if(state.order!=null)continue
            val cargo=if(base==null)emptyList() else aboard(outfit,ship,base)
            if(state.autonomousAnchor && (base?.id!=state.base || cargo.isEmpty())) {
                ships[ship.id]=ship.copy(posture=state.copy(order=TacticalPostureOrder(0,TacticalShipMode.SAILING,0,now,now)))
                reserved+=ship.id
                continue
            }
            if(base==null || cargo.isEmpty() || !available(ship,stocks) || room<=0 || cargo.any {it.order!=null})continue
            if(state.mode.requiresBase) {
                if(state.mode!=TacticalShipMode.ANCHORED || state.base!=base.id)continue
                val order=TacticalGroundOrder(TacticalGroundAction.DEPLOY,0,grid,ship.id,base.id,now,
                    now+TacticalGroundOrder.PREPARATION_SECONDS*scale)
                for(troop in cargo.sortedBy {it.id}.take(room)) {
                    landings[troop.id]=order
                    room--
                }
                continue
            }
            val pose=ship.tacticalPose(grid,now,players.values,stocks,galaxy)
            val readyAt=now+10*scale
            val reaches=TacticalPostureOperations.target(TacticalShipMode.ANCHORED,base.id,grid,camp,
                pose.position,galaxy,stocks,now) &&
                TacticalPostureOperations.target(TacticalShipMode.ANCHORED,base.id,grid,camp,pose.position,galaxy,stocks,readyAt)
            val prior=ship.tactical
            if(!reaches && prior?.motion?.grid==grid && prior.target==0 &&
                prior.motion.velocity>0f && prior.motion.completedWaypoints(now)<prior.motion.waypoints.size) {
                reserved+=ship.id
                continue
            }
            val motion=if(reaches)TacticalMotion(grid,now,pose,0f,pose.direction,listOf(pose.position)) else {
                val goal=TacticalBasePosition.at(base,readyAt,stocks)
                val dx=pose.position.x-goal.x;val dy=pose.position.y-goal.y
                val distance=hypot(dx,dy)
                val standoff=minOf(distance,if(base.kind==0)6f else 0.5f)
                if(distance==0f)continue
                val aim=TacticalPoint(goal.x+dx*standoff/distance,goal.y+dy*standoff/distance)
                val route=TacticalTerrain.route(galaxy,grid,pose.position,aim)
                if(route.last()==pose.position)continue
                val speed=UnitSpecifications.ships.single {it.kind==ship.stack.kind}.number("最高速度（km/1G秒）:最高速度（km/1G秒）")
                TacticalMotion(grid,now,pose,(speed/20000f).coerceIn(0.1f,1f),atan2(-dx,-dy),route,
                    turnRate=TacticalMotion.NATIVE_TURN_RATE)
            }
            val (adjusted,nextCommands)=TacticalFleetMotion.withAirSlowdown(mapOf(ship.id to motion),commands)
            commands=nextCommands
            val route=adjusted.getValue(ship.id)
            val order=if(reaches)TacticalPostureOrder(0,TacticalShipMode.ANCHORED,base.id,now,readyAt) else null
            ships[ship.id]=ship.copy(posture=if(order==null)ship.posture else state.copy(order=order),motionCommander=0,
                tactical=NpcTacticalState(route,now+scale,prior?.nextFireAt ?: now+5*scale,prior?.sequence ?: 0),
                attack=null,shot=null)
            reserved+=ship.id
            movements+=TacticalMovement(session.generation,ship.id,grid,now,route,audience())
        }
        val ground=if(landings.isEmpty())outfit.activeGround else ActiveGroundUnits(outfit.activeGround!!.values.map {
            val order=landings[it.id]
            if(order==null)it else it.copy(order=order)
        })
        val updated=outfit.copy(activeShips=ActiveShipUnits(ships.values.toList()),activeGround=ground)
        return (if(updated==outfit && commands==stocks.tacticalCommands)stocks else
            stocks.copy(outfits=stocks.outfits+(outfit.id to updated),tacticalCommands=commands)) to reserved
    }
}
