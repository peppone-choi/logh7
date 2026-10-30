package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalLossesTest {
    private val stack=UnitStack(32)
    private fun outfit()=OutfitStock(1,2,OutfitKind.FLEET,1000,10,
        units=UnitInventory(mapOf(stack to 2),mapOf(UnitStack(3) to 5)),supplies=30000,
        activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack,supplies=9000),ActiveShipUnit(2,stack,supplies=21000))))
    @Test fun cargoFollowsOnlyTheStruckUnitsSurvivorsAndNewDamageRemainsRepairable() {
        val before=outfit();val first=TacticalLosses.apply(before,1,30)
        val unit=first.activeShips!!.values.first()
        assertEquals(270,unit.boats);assertEquals(15,unit.damaged);assertEquals(45,unit.unavailable)
        assertEquals(mapOf(1 to 8100,2 to 21000),OutfitSupplies.distribution(first));assertEquals(29100,first.supplies)
        assertEquals(before.units.troops,first.units.troops)
        assertEquals(before.activeShips!!.values.last(),first.activeShips.values.last())
        val second=TacticalLosses.apply(first,1,30)
        assertEquals(mapOf(1 to 7200,2 to 21000),OutfitSupplies.distribution(second))
        assertEquals(240,second.activeShips!!.values.first().boats);assertEquals(15,second.activeShips.values.first().damaged)
        assertEquals(30000,before.supplies)
    }
    @Test fun existingDisabledBoatsAreDestroyedFirstAndTotalCarrierLossRemovesPooledTroops() {
        val before=outfit();val changed=before.copy(activeShips=ActiveShipUnits(before.activeShips!!.values.map {
            if(it.id==1)it.copy(damaged=20) else it}))
        val first=TacticalLosses.apply(changed,1,10)
        assertEquals(15,first.activeShips!!.values.first().damaged)
        assertEquals(290,first.activeShips.values.first().boats)
        val sunk=TacticalLosses.apply(first,1,10000)
        assertEquals(0,sunk.activeShips!!.values.first().supplies);assertEquals(21000,sunk.supplies)
        assertEquals(before.units.troops,sunk.units.troops)
        val all=TacticalLosses.apply(sunk,2,300)
        assertEquals(0,all.supplies);assertTrue(all.units.troops.isEmpty())
        assertTrue(all.activeShips!!.values.all {it.boats==0 && it.damaged==0 && it.supplies==0})
        assertEquals(0,OutfitSupplies.capacity(all))
    }
    @Test fun legacyAllocationRoundingAndLiveCapacityNeverCreateOrOverfillCargo() {
        val before=outfit()
        val legacy=before.copy(activeShips=ActiveShipUnits(before.activeShips!!.values.map {it.copy(supplies=null)}))
        val hit=TacticalLosses.apply(legacy,1,1)
        assertEquals(mapOf(1 to 29900,2 to 0),OutfitSupplies.distribution(hit))
        val capacity=OutfitSupplies.unitCapacity(hit.activeShips!!.values.first())
        assertEquals(299*760,capacity)
        assertEquals(capacity+300*760,OutfitSupplies.capacity(hit))
        assertFails {OutfitSupplies.change(hit,1,capacity-29900+1)}
        val one=before.copy(supplies=21001,activeShips=ActiveShipUnits(before.activeShips.values.map {
            if(it.id==1)it.copy(supplies=1) else it}))
        assertEquals(21000,TacticalLosses.apply(one,1,1).supplies)
        assertFails {TacticalLosses.apply(before,1,0)}
        assertFails {TacticalLosses.apply(before,99,1)}
    }
}
