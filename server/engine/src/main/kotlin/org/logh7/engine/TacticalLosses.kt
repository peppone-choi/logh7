package org.logh7.engine

/** W p.52: cargo follows surviving boats; damaged survivors remain repairable.
 * Disable half as many additional boats (minimum one); destroy already damaged boats first.
 * Damage allocation/rounding and total-carrier troop loss are replacement policy. Author: 최병호. */
internal object TacticalLosses {
    fun apply(outfit:OutfitStock,id:Int,damage:Int):OutfitStock {
        require(damage>0)
        val ships=checkNotNull(outfit.activeShips)
        val unit=ships.values.single {it.id==id}
        require(unit.boats>0)
        val destroyed=minOf(unit.boats,damage)
        val remaining=unit.boats-destroyed
        val previousDamaged=(unit.damaged-destroyed).coerceAtLeast(0)
        val damaged=previousDamaged+minOf(remaining-previousDamaged,maxOf(1,destroyed/2))
        val balances=OutfitSupplies.distribution(outfit)
        // Integer cargo rounds down. No other unit pays for this unit's casualties.
        val cargo=minOf((balances.getValue(id).toLong()*remaining/unit.boats).toInt(),
            OutfitSupplies.unitCapacity(unit.copy(boats=remaining,damaged=damaged)))
        val next=unit.copy(boats=remaining,damaged=damaged,supplies=cargo,
            support=if(remaining==0)null else unit.support,
            attack=if(remaining==0)null else unit.attack,shot=if(remaining==0)null else unit.shot,
            motionCommander=if(remaining==0)0 else unit.motionCommander)
        val roster=ActiveShipUnits(ships.values.map {if(it.id==id)next else it})
        // Troops are already pooled at outfit level, so survivors retain them collectively.
        // The original nonzero evacuation threshold is unknown; zero carriers cannot retain troops.
        val troops=if(roster.values.any {it.boats>0})outfit.units.troops else emptyMap()
        val inventory=UnitInventory(roster.quantities(),troops)
        return OutfitSupplies.withBalances(outfit.copy(activeShips=roster,units=inventory),balances+(id to cargo))
    }
}
