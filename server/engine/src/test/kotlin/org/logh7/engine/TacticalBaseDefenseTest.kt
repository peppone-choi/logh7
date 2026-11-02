package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalBaseDefenseTest {
    private val troop=UnitStack(0)
    private fun mixed():PlanetStocks {
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32))))
        val own=OutfitStock(1,2,OutfitKind.FLEET,1000,101,
            units=UnitInventory(ships.quantities(),mapOf(troop to 5,UnitStack(3) to 9)),
            activeShips=ships,position=OutfitPosition(2488),activeGround=ActiveGroundUnits(listOf(
                ActiveGroundUnit(2,troop,base=1000),ActiveGroundUnit(3,troop,base=1000),
                ActiveGroundUnit(4,troop,carrier=1),ActiveGroundUnit(5,troop,base=1001),ActiveGroundUnit(6,troop))))
        val enemy=OutfitStock(2,3,OutfitKind.GROUND,1000,102,
            units=UnitInventory(troops=mapOf(UnitStack(4) to 1)),
            activeGround=ActiveGroundUnits(listOf(ActiveGroundUnit(7,UnitStack(4),base=1000))))
        return PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0),1001 to PlanetStock(1001,3,0,0,0,0)),
            mapOf(1 to own,2 to enemy),mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1000,1),
                102 to OutfitWarehouse(102,3,OutfitKind.PATROL,1000)),nextShipId=8)
    }
    @Test fun onlyActualAshoreDefendersCountDespiteFleetMovementCrewOrCarrierMembership() {
        val state=mixed()
        assertEquals(2000,TacticalBaseDefense.strength(state,1000))
        assertEquals(0,TacticalBaseDefense.strength(state,1001)) // The landed unit is hostile to this base.
        assertEquals(5,state.outfits.getValue(1).activeGround!!.values.size)
        assertEquals(8,state.nextShipId)
    }
    @Test fun currentOwnershipControlsTheBarAndRegularTroopsDoNotBecomeRebels() {
        val state=mixed();val base=state.bases.getValue(1000)
        val captured=state.copy(bases=state.bases+(1000 to base.copy(power=3)))
        assertEquals(1000,TacticalBaseDefense.strength(captured,1000))
        val rebel=captured.copy(bases=captured.bases+(1000 to base.copy(power=3,camp=1)))
        assertEquals(0,TacticalBaseDefense.strength(rebel,1000))
        assertEquals(0,TacticalBaseDefense.strength(state.copy(bases=state.bases+(1000 to base.copy(power=0))),1000))
    }
    @Test fun legacyKnownGroundBaseWorksButUnresolvedFleetTroopsAreNotInventedAshore() {
        val state=mixed();val outfit=state.outfits.getValue(1)
        val legacy=state.copy(outfits=state.outfits+(1 to outfit.copy(activeGround=null)))
        assertEquals(0,TacticalBaseDefense.strength(legacy,1000))
        val ground=OutfitStock(3,2,OutfitKind.GROUND,1000,103,
            units=UnitInventory(troops=mapOf(troop to 5,UnitStack(3) to 9)))
        val next=legacy.copy(outfits=legacy.outfits+(3 to ground),
            warehouses=legacy.warehouses+(103 to OutfitWarehouse(103,2,OutfitKind.PATROL,1000)))
        assertEquals(5000,TacticalBaseDefense.strength(next,1000))
    }
    @Test fun nativeBarSaturatesAtThirtyWithoutRemovingOverflowOrDeclaringCombatComplete() {
        val ground=OutfitStock(1,2,OutfitKind.GROUND,1000,101,units=UnitInventory(troops=mapOf(troop to 65535)))
        val state=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0)),mapOf(1 to ground),
            mapOf(101 to OutfitWarehouse(101,2,OutfitKind.PATROL,1000)))
        assertEquals(30_000,TacticalBaseDefense.strength(state,1000))
        assertEquals(65535,state.outfits.getValue(1).units.troops.getValue(troop))
        assertNull(state.outfits.getValue(1).activeGround)
    }
}
