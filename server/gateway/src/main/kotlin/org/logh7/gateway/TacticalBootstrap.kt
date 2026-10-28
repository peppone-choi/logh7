package org.logh7.gateway

import org.logh7.engine.*
import org.logh7.protocol.*

/** Native tactical initialization from actual unit identities; formation placement is provisional. Author: 최병호. */
internal class TacticalBootstrap(private val snapshot: WorldSnapshot,private val galaxy: Galaxy,private val viewer: Navigator,
    private val characterById: (Int)->CharacterMessages.Generate?) {
    private val people=snapshot.navigators.values.filter {it.generation==viewer.generation && it.grid==viewer.grid && it.baseId==0}
        .sortedBy {it.characterId}
    val characterIds get()=people.map {it.characterId}
    val grid get()=viewer.grid
    val corps=people.mapNotNull {p->characterById(p.characterId)?.let { c->
        val spec=UnitSpecifications.ships.single {it.kind==(p.hull?.kind ?: c.shipKind)}
        val energy=p.energy ?: TacticalEnergy.ZERO
        val charge=if(p.cpPaused) p.charge?.levels(snapshot.session?.gameSeconds ?: 0,energy) else null
        TacticsMessages.Corps(c.id.toInt(),p.commandRange?.radius(snapshot.session?.gameSeconds ?: 0) ?: 0f,(p.energy ?: TacticalEnergy.ZERO).values,
            p.hull?.settle(snapshot.session?.gameSeconds ?: 0,energy)?.levels()
                ?: List(6){spec.number("シールド（旗艦のみ）:容量")},
            beam=if(spec.number("ビーム兵装破壊力:ビーム兵装破壊力")>0) charge?.first ?: 0 else 0,
            gun=if(spec.number("ガン兵装破壊力/消費物資:ガン兵装破壊力/消費物資")>0) charge?.second ?: 0 else 0)
    }}
    val bases=galaxy.bases.filter {it.grid==viewer.grid}.map {base->
        val radians=Math.toRadians(base.angle.toDouble())
        // 004c4240 imports wire x/y into native world x/z; wire z is not the map plane.
        TacticsMessages.Base(base.id,(kotlin.math.cos(radians)*base.radius*1000).toFloat(),
            (kotlin.math.sin(radians)*base.radius*1000).toFloat(),0f)
    }
    val units: List<TacticsMessages.Unit> = run {
        data class Source(val id:Int,val kind:Int,val outfit:Int,val character:Int,val power:Int,val supplies:Int,val cruise:Float,
            val destroyed:Int=0,val posture:TacticalPosture?=null)
        val stocks=checkNotNull(snapshot.planetStocks)
        val sources=people.mapNotNull {p->characterById(p.characterId)?.let {c->
            val fleet=stocks.outfits.values.singleOrNull {p.characterId in it.members() && it.positionIn(galaxy)==OutfitPosition(p.grid)}
            Source(p.unitId,p.hull?.kind ?: c.shipKind,fleet?.id ?: 0,p.characterId,c.power,p.supplies,p.cruise.toFloat(),
                posture=p.posture?.takeIf {it.grid==viewer.grid})
        }} + stocks.outfits.values.filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}.flatMap {fleet->
            val supplies=OutfitSupplies.distribution(fleet)
            checkNotNull(fleet.activeShips).values.map {ship->
                Source(ship.id,ship.stack.kind,fleet.id,fleet.commanderId,fleet.power,supplies.getValue(ship.id),0f,
                    UnitSpecifications.ships.single {it.kind==ship.stack.kind}.number-ship.boats,
                    ship.posture?.takeIf {it.grid==viewer.grid})
            }
        }
        val indices=mutableMapOf<Int,Int>()
        sources.sortedBy {it.id}.map {u->
            val index=indices[u.power] ?: 0;indices[u.power]=index+1
            val initial=TacticalDeployment.formation(index,if(u.power==2)Power.EMPIRE else Power.ALLIANCE)
            val pose=people.singleOrNull {it.unitId==u.id}?.motion?.takeIf {it.grid==viewer.grid}
                ?.pose(snapshot.session?.gameSeconds ?: 0) ?: stocks.outfits[u.outfit]?.activeShips?.values
                ?.singleOrNull {it.id==u.id}?.tactical?.motion?.takeIf {it.grid==viewer.grid}?.pose(snapshot.session?.gameSeconds ?: 0)
            TacticsMessages.Unit(u.id,u.kind,u.outfit,u.character,u.supplies,u.cruise,u.destroyed,
                pose?.position?.x ?: initial.position.x,pose?.position?.y ?: initial.position.y,0f,
                pose?.direction ?: initial.direction,
                damaged=if(people.singleOrNull {it.unitId==u.id}?.hull?.hp?.let {it<100}==true)1 else
                    stocks.outfits[u.outfit]?.activeShips?.values?.singleOrNull {it.id==u.id}?.unavailable ?: 0,
                mode=u.posture?.mode?.wire ?: TacticalShipMode.SAILING.wire,base=u.posture?.base ?: 0)
        }.also {require(it.size<=600)}
    }
    private fun rotations():List<ByteArray> {
        data class Entry(val id:Int,val character:Int,val motion:TacticalMotion)
        val entries=people.mapNotNull {p->p.motion?.takeIf {it.grid==viewer.grid && it.rotation!=null}?.let {Entry(p.unitId,p.characterId,it)}} +
            snapshot.planetStocks!!.outfits.values.filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}.flatMap {o->
                o.activeShips?.values.orEmpty().filter {it.boats>0 && it.motionCommander==o.commanderId}.mapNotNull {u->
                    u.tactical?.motion?.takeIf {it.grid==viewer.grid && it.rotation!=null}?.let {Entry(u.id,u.motionCommander,it)}
                }}
        return entries.groupBy {Triple(it.character,it.motion.acceptedAt,it.motion.rotation)}.values.flatMap {group ->
            TacticalMotionMessages.formation(group.associate {it.id to it.motion},snapshot.session?.gameSeconds ?: 0,group.first().character)
        }
    }
    private fun postureOrders():List<ByteArray> {
        val records=people.mapNotNull {p->p.posture?.takeIf {it.grid==viewer.grid}?.order?.let {p.unitId to it}} +
            snapshot.planetStocks!!.outfits.values.filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}.flatMap {o->
                o.activeShips?.values.orEmpty().filter {it.boats>0}.mapNotNull {u->
                    u.posture?.takeIf {it.grid==viewer.grid}?.order?.takeIf {it.character==o.commanderId}?.let {u.id to it}}}
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
    fun resume() = postureOrders()+rotations()+people.filter {it.motion?.rotation==null}.mapNotNull {TacticalMotionMessages.reply(it,snapshot.session?.gameSeconds ?: 0)} +
        snapshot.planetStocks!!.outfits.values.filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}.flatMap {o ->
            o.activeShips?.values.orEmpty().filter {it.boats>0}.mapNotNull {u -> u.tactical?.motion?.takeIf {it.grid==viewer.grid && it.rotation==null}
                ?.let {TacticalMotionMessages.npc(u.id,it,snapshot.session?.gameSeconds ?: 0)}}} +
        snapshot.planetStocks!!.outfits.values.filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}.flatMap {o ->
            o.activeShips?.values.orEmpty().mapNotNull {u ->u.support?.takeIf {it.grid==viewer.grid}?.let {order ->
                val now=snapshot.session?.gameSeconds ?: 0
                TacticalSupportMessages.reply(TacticalSupportMessages.Command(
                    if(order.action==TacticalSupportAction.REPAIR)TacticalSupportMessages.Action.REPAIR else TacticalSupportMessages.Action.SUPPLY,
                    order.character,u.id,order.target),now,(order.readyAt-now).coerceAtLeast(0))
            }} } + snapshot.planetStocks!!.outfits.values.filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}.flatMap {o ->
            o.activeShips?.values.orEmpty().filter {it.boats>0}.mapNotNull {u -> u.attack?.takeIf {it.commander==o.commanderId}?.let {a ->
                val now=snapshot.session?.gameSeconds ?: 0
                TacticalAttackMessages.reply(TacticalAttackMessages.Attack(a.commander,listOf(u.id),a.order.mode,0),
                    now,(a.order.startsAt-now).coerceAtLeast(0))
            }} } + snapshot.planetStocks!!.outfits.values.filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}.flatMap {o ->
            o.activeShips?.values.orEmpty().filter {it.boats>0}.mapNotNull {u -> u.shot?.takeIf {it.commander==o.commanderId}?.let {shot ->
                val now=snapshot.session?.gameSeconds ?: 0
                TacticalAttackMessages.reply(TacticalAttackMessages.Shoot(shot.commander,listOf(u.id),shot.weapon.wire,1,shot.target),
                    now,(shot.firesAt-now).coerceAtLeast(0))
            }} }
    fun initialize(): List<ByteArray> = people.mapNotNull {p->characterById(p.characterId)?.let {c->
        BootstrapMessages.character(c,p.pcp,p.mcp,p.spotId,p.spotOwner,p.returnBase)
    }} + listOf(TacticsMessages.definitions(viewer.grid,units),
        TacticsMessages.characters(viewer.grid,people.map {it.characterId}),TacticsMessages.corps(corps),TacticsMessages.states(units))
}
