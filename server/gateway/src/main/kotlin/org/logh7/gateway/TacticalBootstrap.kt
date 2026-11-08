package org.logh7.gateway

import org.logh7.engine.*
import org.logh7.protocol.*

/** Native tactical initialization from actual unit identities; formation placement is provisional. Author: Peppone Choi. */
internal class TacticalBootstrap(private val snapshot: WorldSnapshot,private val galaxy: Galaxy,private val viewer: Navigator,
    private val countries:CountryCatalog,
    baseCharacterById: (Int)->CharacterMessages.Generate?) {
    constructor(snapshot:WorldSnapshot,galaxy:Galaxy,viewer:Navigator,baseCharacterById:(Int)->CharacterMessages.Generate?):
        this(snapshot,galaxy,viewer,CountryCatalogLoader.current(),baseCharacterById)
    private val characterById:(Int)->CharacterMessages.Generate? = {id->baseCharacterById(id)?.let {WorldCharacters.view(it,snapshot)}}
    private val people=snapshot.navigators.values.filter {it.generation==viewer.generation && it.grid==viewer.grid && it.baseId==0}
        .sortedBy {it.characterId}
    val characterIds get()=people.map {it.characterId}
    val grid get()=viewer.grid
    val corps by lazy { people.mapNotNull {p->characterById(p.characterId)?.let { c->
        val state=snapshot.planetStocks!!.tacticalCommands
        val mission=state.missions[p.characterId]
        val chief=snapshot.navigators.values.filter {it.generation==viewer.generation && it.grid==p.grid &&
            Camp.of(it,CoupActions.rebel(snapshot.planetStocks!!,it.characterId,it.country))==Camp.of(p,CoupActions.rebel(snapshot.planetStocks!!,p.characterId,p.country)) &&
                (it.cpPaused || it.baseId>0)}.maxWithOrNull(compareBy<Navigator> {
            -(characterById(it.characterId)?.rank ?: 20)}.thenBy {TacticalPersonnel.merit(checkNotNull(snapshot.planetStocks),it.characterId)}.thenByDescending {it.characterId})
        val spec=UnitSpecifications.ships.single {it.kind==(p.hull?.kind ?: c.shipKind)}
        val energy=p.energy ?: TacticalEnergy.ZERO
        val charge=if(p.cpPaused) p.charge?.levels(snapshot.session?.gameSeconds ?: 0,energy) else null
        TacticsMessages.Corps(c.id.toInt(),p.commandRange?.radius(snapshot.session?.gameSeconds ?: 0) ?: 0f,(p.energy ?: TacticalEnergy.ZERO).values,
            p.hull?.settle(snapshot.session?.gameSeconds ?: 0,energy)?.levels()
                ?: List(6){spec.number("シールド（旗艦のみ）:容量")},
            mission=mission?.mission ?: 0,targetKind=mission?.targetKind ?: 0,target=mission?.target ?: 0,
            chief=chief?.characterId==p.characterId,formation=state.formations[p.characterId] ?: 7,
            beam=if(spec.number("ビーム兵装破壊力:ビーム兵装破壊力")>0) charge?.first ?: 0 else 0,
            gun=if(spec.number("ガン兵装破壊力/消費物資:ガン兵装破壊力/消費物資")>0) charge?.second ?: 0 else 0)
    }}}
    val bases=galaxy.bases.filter {it.grid==viewer.grid}.map {base->
        val point=TacticalBasePosition.at(base,snapshot.session?.gameSeconds ?: 0,snapshot.planetStocks)
        // 004c4240 imports wire x/y into native world x/z; wire z is not the map plane.
        TacticsMessages.Base(base.id,point.x,point.y,0f)
    }
    val baseInformation by lazy { bases.map {base ->
        val cannon=snapshot.planetStocks!!.tacticalCommands.orders.singleOrNull {it.command.base==base.id && it.command.opcode==0x419}
        TacticsMessages.BaseInformation(base,cannonStart=cannon?.acceptedAt ?: 0,
            cannonAngle=cannon?.command?.direction?.let {(((it/(2*Math.PI)*65536).toInt()%65536)+65536)%65536} ?: 0,
            stamina=TacticalBaseDefense.strength(checkNotNull(snapshot.planetStocks),galaxy.bases.single {it.id==base.id}))}}
    /** [camp] is the unit's actual combat side (country + rebellion, an unaffiliated individual, or pirates). */
    private data class Source(val id:Int,val kind:Int,val outfit:Int,val character:Int,val supplies:Int,val cruise:Float,
        val camp:Camp,val destroyed:Int=0,val posture:TacticalPosture?=null)
    private val sources:List<Source> = run {
        val stocks=checkNotNull(snapshot.planetStocks)
        people.mapNotNull {p->characterById(p.characterId)?.let {c->
            val fleet=stocks.outfits.values.singleOrNull {p.characterId in it.members() && it.positionIn(galaxy)==OutfitPosition(p.grid)}
            Source(p.unitId,p.hull?.kind ?: c.shipKind,fleet?.id ?: 0,p.characterId,p.supplies,p.cruise.toFloat(),
                TacticalAuthority.camp(p,stocks),posture=p.posture?.takeIf {it.grid==viewer.grid})
        }} + stocks.outfits.values.filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}.flatMap {fleet->
            val supplies=OutfitSupplies.distribution(fleet)
            val camp=TacticalAuthority.camp(fleet,stocks)
            checkNotNull(fleet.activeShips).values.map {ship->
            Source(ship.id,ship.stack.kind,fleet.id,TacticalAuthority.commander(fleet,ship,stocks),supplies.getValue(ship.id),0f,
                    camp,UnitSpecifications.ships.single {it.kind==ship.stack.kind}.number-ship.boats,
                    ship.posture?.takeIf {it.grid==viewer.grid})
            }
        } + stocks.social.pirates.fleets.values.filter {it.grid==viewer.grid}.flatMap {f->f.units.map {u->
            Source(u.id,org.logh7.engine.PirateActions.kind(),org.logh7.engine.PirateActions.OUTFIT_BASE+f.id,0,
                0,0f,Camp.PIRATES,org.logh7.engine.PirateActions.UNIT_BOATS-u.boats)}}
    }
    /** Actual camp of every unit in [units], keyed by the same unit ID; read-only, no wider world lookup. */
    val unitCamps:Map<Int,Camp> get()=sources.associate {it.id to it.camp}
    val units: List<TacticsMessages.Unit> by lazy {
        val stocks=checkNotNull(snapshot.planetStocks)
        sources.sortedBy {it.id}.map {u->
            val initial by lazy {TacticalDeployment.initial(u.id,viewer.grid,people,stocks,galaxy,countries)}
            val pose=org.logh7.engine.PirateActions.pose(stocks,u.id) ?: people.singleOrNull {it.unitId==u.id}?.motion?.takeIf {it.grid==viewer.grid}
                ?.pose(snapshot.session?.gameSeconds ?: 0,u.posture,galaxy,stocks) ?: stocks.outfits[u.outfit]?.activeShips?.values
                ?.singleOrNull {it.id==u.id}?.tactical?.motion?.takeIf {it.grid==viewer.grid}?.pose(snapshot.session?.gameSeconds ?: 0,u.posture,galaxy,stocks)
            val berth=u.posture?.takeIf {it.mode.requiresBase}?.let {state->
                galaxy.bases.singleOrNull {it.id==state.base && it.grid==viewer.grid}}
                ?.let {TacticalBasePosition.at(it,snapshot.session?.gameSeconds ?: 0,stocks)}
            TacticsMessages.Unit(u.id,u.kind,u.outfit,u.character,u.supplies,u.cruise,u.destroyed,
                pose?.position?.x ?: berth?.x ?: initial.position.x,pose?.position?.y ?: berth?.y ?: initial.position.y,0f,
                pose?.direction ?: initial.direction,
                damaged=if(people.singleOrNull {it.unitId==u.id}?.hull?.hp?.let {it<100}==true)1 else
                    stocks.outfits[u.outfit]?.activeShips?.values?.singleOrNull {it.id==u.id}?.unavailable ?: 0,
                mode=u.posture?.mode?.wire ?: TacticalShipMode.SAILING.wire,base=u.posture?.base ?: 0,
                troops=stocks.outfits[u.outfit]?.activeGround?.values.orEmpty().filter {it.carrier==u.id}.sortedBy {it.id}.map {it.id},
                morale=stocks.tacticalCommands.morale[u.id] ?: 100,confused=stocks.tacticalCommands.confused(u.id))
        }.let { units ->
            val camps=unitCamps
            val own=TacticalAuthority.camp(viewer,stocks)
            val activeObservers=people.filter {it.cpPaused && !BattleDeath.deceased(stocks,it.characterId)}
                .map {it.unitId}.toMutableSet()
            stocks.outfits.values.filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}.forEach {outfit->
                outfit.activeShips?.values.orEmpty().filter {ship->ship.boats>0 &&
                    ship.posture?.takeIf {it.grid==viewer.grid}?.mode!=TacticalShipMode.STATIONED
                }.mapTo(activeObservers) {it.id}
            }
            // Under the star-only rule the closest allied observer can see everything any ally can see.
            val observer=units.asSequence().filter {it.id in activeObservers && camps[it.id]==own}
                .minByOrNull {it.x.toDouble()*it.x+it.y.toDouble()*it.y}
                ?: units.singleOrNull {it.id==viewer.unitId}
            val headquarters=TacticalTerrain.defenseObserver(galaxy,viewer.grid,own,snapshot.session?.gameSeconds ?: 0,stocks)
            val detected=units.associate { u -> u.id to (
                observer==null || camps[u.id]==own ||
                    (headquarters!=null && !TacticalTerrain.sensorBlocked(galaxy,viewer.grid,headquarters,TacticalPoint(u.x,u.y))) ||
                    !TacticalTerrain.sensorBlocked(galaxy,viewer.grid,TacticalPoint(observer.x,observer.y),TacticalPoint(u.x,u.y))) }
            units.map { u ->
                val visible=detected.getValue(u.id)
                val leader=stocks.tacticalCommands.formationLeaders[u.id]?.takeIf {detected[it]==true}
                    ?.let {id->units.singleOrNull {it.id==id}}
                if(leader==null)u.copy(detected=visible)
                else u.copy(detected=visible,leader=leader.id,leaderX=leader.x,leaderY=leader.y,leaderZ=leader.z,leaderDirection=leader.direction)
            }
        }.also {require(it.size<=600)}
    }

    fun detectionChanges(previousHidden:Set<Int>):List<TacticsMessages.Unit> =
        units.filter { it.detected==(it.id in previousHidden) }

    val groundDefinitions by lazy { snapshot.planetStocks?.outfits?.values.orEmpty().sortedBy {it.id}.flatMap {outfit ->
        val carriers=units.filter {it.outfit==outfit.id && it.destroyed<UnitSpecifications.ships.single {s->s.kind==it.kind}.number}
            .map {it.id}.toSet()
        outfit.activeGround?.values.orEmpty().filter {unit ->
            (unit.base>0 && galaxy.bases.any {it.id==unit.base && it.grid==viewer.grid}) ||
                (unit.carrier>0 && unit.carrier in carriers)
        }.sortedBy {it.id}.map {unit -> TacticsMessages.GroundDefinition(unit.id,1000+unit.stack.kind,outfit.id,unit.base,unit.carrier,unit.origin>0)}
    }}
    private fun rotations():List<ByteArray> {
        data class Entry(val id:Int,val character:Int,val motion:TacticalMotion)
        val stocks=checkNotNull(snapshot.planetStocks)
        val entries=people.filterNot {BattleDeath.deceased(stocks,it.characterId)}.mapNotNull {p->
            p.motion?.takeIf {it.grid==viewer.grid && it.rotation!=null}?.let {Entry(p.unitId,p.characterId,it)}
        } + stocks.outfits.values.filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}.flatMap {o->
            o.activeShips?.values.orEmpty().filter {u->
                u.boats>0 && u.motionCommander==TacticalAuthority.commander(o,u,stocks) &&
                    !BattleDeath.deceased(stocks,u.motionCommander)
            }.mapNotNull {u->
                u.tactical?.motion?.takeIf {it.grid==viewer.grid && it.rotation!=null}?.let {Entry(u.id,u.motionCommander,it)}
            }
        }
        return entries.groupBy {Triple(it.character,it.motion.acceptedAt,it.motion.rotation)}.values.flatMap {group ->
            TacticalMotionMessages.formation(group.associate {it.id to it.motion},snapshot.session?.gameSeconds ?: 0,group.first().character)
        }
    }
    private fun postureOrders():List<ByteArray> {
        val records=people.mapNotNull {p->p.posture?.takeIf {it.grid==viewer.grid}?.order?.let {p.unitId to it}} +
            snapshot.planetStocks!!.outfits.values.filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}.flatMap {o->
                o.activeShips?.values.orEmpty().filter {it.boats>0}.mapNotNull {u->
                    u.posture?.takeIf {it.grid==viewer.grid}?.order?.takeIf {it.character==TacticalAuthority.commander(o,u,snapshot.planetStocks!!)}?.let {u.id to it}}}
        val now=snapshot.session?.gameSeconds ?: 0
        return records.groupBy {it.second}.flatMap {(order,entries)->entries.chunked(32).map {group->
                val ids=group.map {it.first}
                val sortie=order.mode==TacticalShipMode.ANCHORED && snapshot.planetStocks!!.outfits.values.any {o->
                    o.activeShips?.values?.any {it.id==ids.first() && it.posture?.mode==TacticalShipMode.STATIONED}==true}
                if(sortie)TacticalGroundMessages.reply(TacticalGroundMessages.Command(TacticalGroundMessages.Action.SORTIE,order.character,ids),
                    now,(order.readyAt-now).coerceAtLeast(0))
                else TacticalGroundMessages.reply(TacticalGroundMessages.Posture(order.character,ids,order.mode.wire,order.base),
                    now,(order.readyAt-now).coerceAtLeast(0))}}
    }
    val groundOrders by lazy { pendingGroundOrders(snapshot.planetStocks, viewer.grid) }
    private fun groundOrderReplies():List<ByteArray> = groundOrders.map {order->TacticalGroundMessages.reply(TacticalGroundMessages.Command(
        if(order.action==TacticalGroundAction.DEPLOY)TacticalGroundMessages.Action.DEPLOY else TacticalGroundMessages.Action.WITHDRAW,
        order.character,listOf(order.carrier)),snapshot.session?.gameSeconds ?: 0,
        (order.readyAt-(snapshot.session?.gameSeconds ?: 0)).coerceAtLeast(0))}
    fun resume() = snapshot.planetStocks!!.tacticalCommands.orders.filter {it.grid==viewer.grid}.mapNotNull {order->
        TacticalCommandWire.command(order)?.let {org.logh7.protocol.TacticalCommandMessages.reply(it,snapshot.session?.gameSeconds ?: 0,
            (order.readyAt-(snapshot.session?.gameSeconds ?: 0)).coerceAtLeast(0))}}+
        groundOrderReplies()+postureOrders()+rotations()+people.filter {it.motion?.rotation==null && it.posture?.takeIf {p->p.grid==viewer.grid}?.mode?.requiresBase!=true}.mapNotNull {TacticalMotionMessages.reply(it,snapshot.session?.gameSeconds ?: 0)} +
        snapshot.planetStocks!!.outfits.values.filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}.flatMap {o ->
            o.activeShips?.values.orEmpty().filter {it.boats>0 && it.posture?.takeIf {p->p.grid==viewer.grid}?.mode?.requiresBase!=true}.mapNotNull {u -> u.tactical?.motion?.takeIf {it.grid==viewer.grid && it.rotation==null}
                ?.let {TacticalMotionMessages.npc(u.id,it,snapshot.session?.gameSeconds ?: 0)}}} +
        snapshot.planetStocks!!.outfits.values.filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}.flatMap {o ->
            o.activeShips?.values.orEmpty().mapNotNull {u ->u.support?.takeIf {it.grid==viewer.grid}?.let {order ->
                val now=snapshot.session?.gameSeconds ?: 0
                TacticalSupportMessages.reply(TacticalSupportMessages.Command(
                    if(order.action==TacticalSupportAction.REPAIR)TacticalSupportMessages.Action.REPAIR else TacticalSupportMessages.Action.SUPPLY,
                    order.character,u.id,order.target),now,(order.readyAt-now).coerceAtLeast(0))
            }} } + snapshot.planetStocks!!.outfits.values.filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}.flatMap {o ->
            o.activeShips?.values.orEmpty().filter {it.boats>0}.mapNotNull {u -> u.attack?.takeIf {it.commander==TacticalAuthority.commander(o,u,snapshot.planetStocks!!)}?.let {a ->
                val now=snapshot.session?.gameSeconds ?: 0
                TacticalAttackMessages.reply(TacticalAttackMessages.Attack(a.commander,listOf(u.id),a.order.mode,0),
                    now,(a.order.startsAt-now).coerceAtLeast(0))
            }} } + snapshot.planetStocks!!.outfits.values.filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}.flatMap {o ->
            o.activeShips?.values.orEmpty().filter {it.boats>0}.mapNotNull {u -> u.shot?.takeIf {it.commander==TacticalAuthority.commander(o,u,snapshot.planetStocks!!)}?.let {shot ->
                val now=snapshot.session?.gameSeconds ?: 0
                TacticalAttackMessages.reply(TacticalAttackMessages.Shoot(shot.commander,listOf(u.id),shot.weapon.wire,1,shot.target),
                    now,(shot.firesAt-now).coerceAtLeast(0))
            }} }
    fun emergencySupply(base:Int):PlanetStock? {
        val stocks=checkNotNull(snapshot.planetStocks)
        val openCountry=stocks.tacticalCommands.emergencySupplies[base] ?: return null
        return stocks.bases[base]?.takeIf {
            it.country?.value==openCountry && it.camp==0 && TacticalAuthority.camp(it)==TacticalAuthority.camp(viewer,stocks)
        }
    }
    fun committedMotion(unit:Int):TacticalMotion? {
        if(unit>1_000_000)return people.singleOrNull {it.unitId==unit && it.cpPaused}?.motion
        return snapshot.planetStocks?.outfits?.values.orEmpty()
            .filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}
            .firstNotNullOfOrNull {outfit->
                outfit.activeShips?.values?.singleOrNull {it.id==unit && it.boats>0}?.tactical?.motion
            }
    }

    private fun emergencySupplies():List<ByteArray> = bases.mapNotNull {base->
        emergencySupply(base.id)?.let {TacticalBaseMessages.emergency(base.id,0,it.supplies,0)}
    }
    fun initialize(offices:(CharacterMessages.Generate,Navigator)->List<BootstrapMessages.OfficeCard> = {_,_->emptyList()}): List<ByteArray> = people.mapNotNull {p->characterById(p.characterId)?.let {c->
        BootstrapMessages.character(c,p.pcp,p.mcp,p.spotId,p.spotOwner,p.returnBase,offices(c,p))
    }} + listOf(TacticsMessages.baseInformation(baseInformation),
        TacticsMessages.definitions(viewer.grid,units,groundDefinitions),
        TacticsMessages.characters(viewer.grid,people.map {it.characterId}),TacticsMessages.corps(corps),TacticsMessages.states(units))+emergencySupplies()
}
