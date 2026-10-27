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
        val spec=UnitSpecifications.ships.single {it.kind==c.shipKind}
        TacticsMessages.Corps(c.id.toInt(),0f,(p.energy ?: TacticalEnergy.ZERO).values,
            List(6){spec.number("シールド（旗艦のみ）:容量")})
    }}
    val bases=galaxy.bases.filter {it.grid==viewer.grid}.map {base->
        val radians=Math.toRadians(base.angle.toDouble())
        // 004c4240 imports wire x/y into native world x/z; wire z is not the map plane.
        TacticsMessages.Base(base.id,(kotlin.math.cos(radians)*base.radius*1000).toFloat(),
            (kotlin.math.sin(radians)*base.radius*1000).toFloat(),0f)
    }
    val units: List<TacticsMessages.Unit> = run {
        data class Source(val id:Int,val kind:Int,val outfit:Int,val character:Int,val power:Int,val supplies:Int,val cruise:Float,val destroyed:Int=0)
        val stocks=checkNotNull(snapshot.planetStocks)
        val sources=people.mapNotNull {p->characterById(p.characterId)?.let {c->
            val fleet=stocks.outfits.values.singleOrNull {p.characterId in it.members() && it.positionIn(galaxy)==OutfitPosition(p.grid)}
            Source(p.unitId,c.shipKind,fleet?.id ?: 0,p.characterId,c.power,10_000,p.cruise.toFloat())
        }} + stocks.outfits.values.filter {it.positionIn(galaxy)==OutfitPosition(viewer.grid)}.flatMap {fleet->
            val supplies=OutfitSupplies.distribution(fleet)
            checkNotNull(fleet.activeShips).values.map {ship->
                Source(ship.id,ship.stack.kind,fleet.id,fleet.commanderId,fleet.power,supplies.getValue(ship.id),0f,
                    UnitSpecifications.ships.single {it.kind==ship.stack.kind}.number-ship.boats)
            }
        }
        val indices=mutableMapOf<Int,Int>()
        sources.sortedBy {it.id}.map {u->
            val index=indices[u.power] ?: 0;indices[u.power]=index+1
            val initial=TacticalDeployment.formation(index,if(u.power==2)Power.EMPIRE else Power.ALLIANCE)
            val pose=people.singleOrNull {it.unitId==u.id}?.motion?.takeIf {it.grid==viewer.grid}
                ?.pose(snapshot.session?.gameSeconds ?: 0)
            TacticsMessages.Unit(u.id,u.kind,u.outfit,u.character,u.supplies,u.cruise,u.destroyed,
                pose?.position?.x ?: initial.position.x,pose?.position?.y ?: initial.position.y,0f,
                pose?.direction ?: initial.direction)
        }.also {require(it.size<=600)}
    }
    fun resume() = people.mapNotNull {TacticalMotionMessages.reply(it,snapshot.session?.gameSeconds ?: 0)}
    fun initialize(): List<ByteArray> = people.mapNotNull {p->characterById(p.characterId)?.let {c->
        BootstrapMessages.character(c,p.pcp,p.mcp,p.spotId,p.spotOwner,p.returnBase)
    }} + listOf(TacticsMessages.definitions(viewer.grid,units),
        TacticsMessages.characters(viewer.grid,people.map {it.characterId}),TacticsMessages.corps(corps),TacticsMessages.states(units))
}
