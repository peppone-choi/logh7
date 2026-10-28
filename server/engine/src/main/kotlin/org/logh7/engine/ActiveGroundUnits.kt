package org.logh7.engine

import java.util.Collections

/** Ground identities share the commissioned-unit sequence. Zero carrier/base retains legacy
 * outfit membership without inventing a physical carrier. Author: 최병호. */
data class ActiveGroundUnit(val id:Int,val stack:UnitStack,val carrier:Int=0,val base:Int=0) {
    init {
        require(id in 1..999_999 && GroundUnits.isGround(stack.kind))
        require(carrier in 0..999_999 && base in 0..65535 && (carrier==0 || base==0))
        require(carrier!=id)
    }
}
class ActiveGroundUnits(values:List<ActiveGroundUnit>) {
    val values:List<ActiveGroundUnit> = Collections.unmodifiableList(ArrayList(values))
    init {require(values.map {it.id}.distinct().size==values.size)}
    fun quantities():Map<UnitStack,Int> = values.groupingBy {it.stack}.eachCount()
    override fun equals(other:Any?)=other is ActiveGroundUnits && values==other.values
    override fun hashCode()=values.hashCode()
}

object GroundUnits {
    fun isGround(kind:Int)=UnitSpecifications.crews.any {it.kind==kind && it.type!=3}
    fun quantities(inventory:UnitInventory)=inventory.troops.filterKeys {isGround(it.kind)}
    private val order=compareBy<UnitStack> {it.kind}.thenBy {it.level}

    /** Missing historical carrier assignments stay unknown. A ground outfit has a known home base. */
    fun initialize(stocks:PlanetStocks):PlanetStocks {
        var cursor=stocks.nextShipId
        val outfits=stocks.outfits.toMutableMap()
        for(outfit in stocks.outfits.values.sortedBy {it.id}) {
            if(outfit.activeGround!=null || quantities(outfit.units).isEmpty())continue
            val (roster,next)=reorganize(outfit,outfit.units,outfit.activeShips,cursor)
            outfits[outfit.id]=outfit.copy(activeGround=roster);cursor=next
        }
        return if(cursor==stocks.nextShipId)stocks else stocks.copy(outfits=outfits,nextShipId=cursor)
    }

    internal fun reorganize(outfit:OutfitStock,inventory:UnitInventory,ships:ActiveShipUnits?,nextId:Int):Pair<ActiveGroundUnits?,Int> {
        val wanted=quantities(inventory)
        if(outfit.activeGround==null && wanted.isEmpty())return null to nextId
        val previous=outfit.activeGround?.values.orEmpty().groupBy {it.stack}
        val alive=ships?.values.orEmpty().filter {it.boats>0}.map {it.id}.toSet()
        var cursor=nextId
        val roster=buildList {
            for((stack,count) in wanted.toSortedMap(order)) {
                // A deployed unit cannot be returned to a warehouse through a fleet slider.
                val old=previous[stack].orEmpty().sortedWith(compareByDescending<ActiveGroundUnit> {
                    it.base!=0 && outfit.kind!=OutfitKind.GROUND
                }.thenBy {it.id})
                val retained=old.take(count)
                require(old.drop(count).none {it.base!=0 && outfit.kind!=OutfitKind.GROUND}) {"Deployed ground unit is busy"}
                require(retained.all {it.carrier==0 || it.carrier in alive}) {"Unload troops before returning their carrier"}
                addAll(retained)
                repeat(count-retained.size) {
                    require(cursor<1_000_000) {"Active unit identity space exhausted"}
                    add(ActiveGroundUnit(cursor++,stack,base=if(outfit.kind==OutfitKind.GROUND)outfit.position?.baseId ?: outfit.baseId else 0))
                }
            }
            require(previous.filterKeys {it !in wanted}.values.flatten().none {
                it.base!=0 && outfit.kind!=OutfitKind.GROUND
            }) {"Deployed ground unit is busy"}
        }
        return ActiveGroundUnits(roster) to cursor
    }

    /** Landed troops stay ashore when the last carrier is sunk; survivors aboard a lost carrier
     * retain outfit membership until an explicit carrier allocation is available. */
    internal fun afterLoss(outfit:OutfitStock,ships:ActiveShipUnits):Pair<Map<UnitStack,Int>,ActiveGroundUnits?> {
        val old=outfit.activeGround ?: return (if(ships.values.any {it.boats>0})outfit.units.troops else emptyMap()) to null
        val alive=ships.values.filter {it.boats>0}.map {it.id}.toSet()
        val retained=old.values.filter {alive.isNotEmpty() || it.base>0}.map {
            if(it.carrier!=0 && it.carrier !in alive)it.copy(carrier=0) else it
        }
        val roster=ActiveGroundUnits(retained)
        val crew=if(alive.isEmpty())emptyMap() else outfit.units.troops.filterKeys {!isGround(it.kind)}
        return (crew+roster.quantities()) to roster
    }
}
