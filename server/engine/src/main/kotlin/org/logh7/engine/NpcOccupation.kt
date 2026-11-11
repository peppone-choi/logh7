package org.logh7.engine

import kotlin.math.atan2
import kotlin.math.hypot

/** A national objective directs regular uncommanded carriers (evidence:guess). */
internal object NpcOccupation {
    private fun eligible(outfit:OutfitStock,stocks:PlanetStocks,eliminated:Set<CountryId>) =
        outfit.kind in setOf(OutfitKind.FLEET,OutfitKind.PATROL) && outfit.country !in eliminated &&
            !TacticalAuthority.camp(outfit,stocks).insurgent && TacticalAuthority.uncommanded(outfit,stocks)

    fun goal(outfit:OutfitStock,stocks:PlanetStocks,galaxy:Galaxy,eliminated:Set<CountryId>):GalaxyBase? {
        if(!eligible(outfit,stocks,eliminated))return null
        val id=stocks.social.goals.national[outfit.country.value]?.base ?: return null
        val owner=stocks.bases[id]?.country ?: return null
        if(owner==outfit.country || owner in eliminated)return null
        return galaxy.bases.singleOrNull {it.id==id}
    }

    private fun available(ship:ActiveShipUnit,stocks:PlanetStocks) =
        ship.boats>ship.damaged && ship.support==null && ship.tactical?.retreat==null &&
            !stocks.tacticalCommands.confused(ship.id)

    private fun aboard(outfit:OutfitStock,ship:ActiveShipUnit,base:GalaxyBase) =
        outfit.activeGround?.values.orEmpty().filter {
            it.carrier==ship.id && GroundBattleOperations.admits(base,it)
        }

    fun hasCarrier(outfit:OutfitStock,base:GalaxyBase,stocks:PlanetStocks)=
        outfit.activeShips?.values.orEmpty().any { available(it,stocks) && aboard(outfit,it,base).isNotEmpty() }

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
