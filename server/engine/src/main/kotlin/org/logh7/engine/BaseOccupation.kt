package org.logh7.engine

/** Manual W pp.51-52. Warehouse caps and sortie offsets are revival policy. Author: 최병호. */
data class BaseOccupation(val generation:Long,val grid:Int,val base:Int,val previousPower:Int,val power:Int,
    val seconds:Long,val destroyedShips:Map<Int,Int>,val expelled:Map<String,Navigator>,
    val recipients:Map<Int,String>)

internal object BaseOccupationOperations {
    fun victor(stocks:PlanetStocks,base:GalaxyBase):Int? {
        val owner=stocks.bases.getValue(base.id)
        if(owner.power !in 2..3 || owner.camp!=0)return null
        val enemy=5-owner.power
        return enemy.takeIf {GroundBattleOperations.participants(stocks,base,it).isNotEmpty() &&
            GroundBattleOperations.participants(stocks,base,owner.power).isEmpty()}
    }
    fun pending(stocks:PlanetStocks,galaxy:Galaxy)=galaxy.bases.any {victor(stocks,it)!=null}

    fun advance(session:SessionState,galaxy:Galaxy,players:MutableMap<String,Navigator>,original:PlanetStocks,
        maximumStock:Int,scale:Long,events:MutableList<BaseOccupation>):PlanetStocks {
        if(session.ended!=null)return original
        val now=session.gameMillis/1000
        var stocks=original
        for(base in galaxy.bases.sortedBy {it.id}) {
            val winner=victor(stocks,base) ?: continue
            val previous=stocks.bases.getValue(base.id)
            val loser=previous.power
            val destroyed=linkedMapOf<Int,Int>()
            val outfits=stocks.outfits.mapValues {(_,outfit)->
                if(outfit.power!=loser)return@mapValues outfit
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
                // Ineligible garrison reserves are eliminated too, never converted into the victor's troop type.
                if(changed.kind==OutfitKind.GROUND) {
                    val old=changed.activeGround
                    if(old!=null) {
                        val roster=ActiveGroundUnits(old.values.filter {it.base!=base.id})
                        changed=changed.copy(activeGround=roster,units=UnitInventory(changed.units.ships,
                            changed.units.troops.filterKeys {!GroundUnits.isGround(it.kind)}+roster.quantities()))
                    }
                }
                changed
            }
            val localWarehouses=stocks.warehouses.values.filter {it.baseId==base.id && it.power==loser}
            // Same finite capacity as production. Accumulate in Long before applying the cap.
            val supplies=(previous.supplies.toLong()+localWarehouses.sumOf {it.supplies.toLong()})
                .coerceAtMost(maxOf(previous.supplies,maximumStock).toLong()).toInt()
            val warehouses=stocks.warehouses.mapValues {(_,warehouse)->
                if(warehouse.baseId!=base.id || warehouse.power!=loser)warehouse else warehouse.copy(
                    supplies=0,units=UnitInventory(troops=warehouse.units.troops),spareShips=SpareShipBoats())
            }
            val occupied=previous.copy(power=winner,camp=0,supplies=supplies,
                governorId=0,garrisonCommanderId=0,capitalAdministratorId=0,capitalDefenseCommanderId=0,guardInspectorId=0,
                // Captured works restart, including recruitment; never revive progress on recapture.
                units=UnitInventory(troops=previous.units.troops),
                spareShips=SpareShipBoats())
            val capital=Capital.of(base)
            stocks=stocks.copy(bases=stocks.bases+(base.id to occupied),outfits=outfits,warehouses=warehouses,
                logisticsOfficers=stocks.logisticsOfficers.filterValues {it.capital!=capital},
                nationalOffices=stocks.nationalOffices.filterKeys {it.capital!=capital},
                groundBattles=stocks.groundBattles-base.id,
                strategic=stocks.strategic.copy(suppressions=stocks.strategic.suppressions.filter {it.base!=base.id},
                    fiefs=stocks.strategic.fiefs-base.id,offices=stocks.strategic.offices.filter {holding ->
                        if(OrganizationOffices.of(holding.card)?.scope==OfficeScope.OUTFIT)
                            stocks.outfits[holding.seat]?.baseId!=base.id else holding.seat!=base.id
                    }))
            val expelled=linkedMapOf<String,Navigator>()
            var offset=0
            for((account,p) in players.toMap()) {
                if(p.generation!=session.generation || (if(p.power==Power.EMPIRE)2 else 3)!=loser)continue
                var next=p
                if(p.returnBase==base.id)next=next.copy(returnBase=0)
                if(p.baseId==base.id && p.grid==base.grid) {
                    val centre=TacticalBasePosition.at(base,now)
                    val point=TacticalPoint(centre.x+base.diameter+2f+offset++*2f,centre.y)
                    val pose=TacticalPose(point,0f)
                    next=next.copy(baseId=0,mode=5,spotId=0,spotOwner=0,cpPaused=true,cpRecoveryAt=now,
                        fuelReadyAt=null,strategy=null,dockTarget=null,retreat=null,attack=null,
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
            events+=BaseOccupation(session.generation,base.grid,base.id,loser,winner,now,destroyed,expelled,recipients)
        }
        return stocks
    }
}
