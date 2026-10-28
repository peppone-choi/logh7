package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class ActiveGroundUnitsTest {
    private val ship=UnitStack(32)
    private val troop=UnitStack(0)
    private val crew=UnitStack(3)
    private fun stocks()=PlanetStocks(1,7,mapOf(1000 to PlanetStock(1000,2,0,100,200,300)),
        mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,101,
            units=UnitInventory(mapOf(ship to 1),mapOf(troop to 2,crew to 4)))),
        mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1000,1)),deploymentInitialized=true)

    @Test fun migrationPreservesBalancesExcludesCrewAndNeverInventsCarrier() {
        val old=stocks();val next=ActiveShips.initialize(old);val outfit=next.outfits.getValue(1)
        assertEquals(listOf(1),outfit.activeShips!!.values.map {it.id})
        assertEquals(listOf(2,3),outfit.activeGround!!.values.map {it.id})
        assertTrue(outfit.activeGround!!.values.all {it.stack==troop && it.carrier==0 && it.base==0})
        assertEquals(old.outfits.getValue(1).units,outfit.units)
        assertEquals(old.bases,next.bases);assertEquals(old.warehouses,next.warehouses)
        assertEquals(7,next.producedDays);assertEquals(4,next.nextShipId)
        assertSame(next,ActiveShips.initialize(next))
    }
    @Test fun returnedAndRecommissionedTroopsDoNotReuseRetiredIdentities() {
        val initial=ActiveShips.initialize(stocks())
        val smaller=ActiveShips.reorganize(initial,1,UnitInventory(mapOf(ship to 1),mapOf(troop to 1,crew to 4)))
        val restored=ActiveShips.reorganize(smaller,1,initial.outfits.getValue(1).units)
        assertEquals(listOf(2,4),restored.outfits.getValue(1).activeGround!!.values.map {it.id})
        assertEquals(5,restored.nextShipId)
        assertEquals(listOf(2,3),initial.outfits.getValue(1).activeGround!!.values.map {it.id})
    }
    @Test fun initialGroundOutfitUsesItsKnownCurrentBase() {
        val old=stocks();val ground=OutfitStock(2,2,OutfitKind.GROUND,1000,102,
            units=UnitInventory(troops=mapOf(troop to 3)))
        val initial=old.copy(outfits=old.outfits+(2 to ground),warehouses=old.warehouses+
            (102 to OutfitWarehouse(102,2,OutfitKind.PATROL,1000)))
        val next=ActiveShips.initialize(initial)
        assertEquals(listOf(4,5,6),next.outfits.getValue(2).activeGround!!.values.map {it.id})
        assertTrue(next.outfits.getValue(2).activeGround!!.values.all {it.base==1000 && it.carrier==0})
        assertEquals(7,next.nextShipId)
    }
    @Test fun survivingFleetKeepsTroopIdentityWithoutInventingAnEvacuationCarrier() {
        val old=stocks().outfits.getValue(1)
        val outfit=old.copy(units=UnitInventory(mapOf(ship to 2),old.units.troops),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,ship),ActiveShipUnit(4,ship))),
            activeGround=ActiveGroundUnits(listOf(ActiveGroundUnit(2,troop,carrier=1),ActiveGroundUnit(3,troop,base=1000))))
        val after=TacticalLosses.apply(outfit,1,300)
        assertEquals(outfit.units.troops,after.units.troops)
        assertEquals(listOf(ActiveGroundUnit(2,troop),ActiveGroundUnit(3,troop,base=1000)),after.activeGround!!.values)
        assertEquals(2,GridForces.mobileUnits(after))
    }
    @Test fun loadedCarrierAndDeployedTroopsCannotBeReturnedThroughReorganization() {
        val initial=ActiveShips.initialize(stocks());val old=initial.outfits.getValue(1)
        val roster=ActiveGroundUnits(listOf(ActiveGroundUnit(2,troop,carrier=1),ActiveGroundUnit(3,troop,base=1000)))
        val state=initial.copy(outfits=mapOf(1 to old.copy(activeGround=roster)))
        assertFailsWith<IllegalArgumentException> {
            ActiveShips.reorganize(state,1,UnitInventory(troops=old.units.troops))
        }
        assertFailsWith<IllegalArgumentException> {ActiveShips.reorganize(state,1,UnitInventory(mapOf(ship to 1)))}
        val smaller=ActiveShips.reorganize(state,1,UnitInventory(mapOf(ship to 1),mapOf(troop to 1)))
        assertEquals(listOf(3),smaller.outfits.getValue(1).activeGround!!.values.map {it.id})
    }
    @Test fun finalCarrierLossRemovesAboardTroopsAndCrewButPreservesAshoreTroops() {
        val initial=ActiveShips.initialize(stocks()).outfits.getValue(1)
        val outfit=initial.copy(activeGround=ActiveGroundUnits(listOf(
            ActiveGroundUnit(2,troop,carrier=1),ActiveGroundUnit(3,troop,base=1000))))
        val lost=TacticalLosses.apply(outfit,1,300)
        assertEquals(mapOf(troop to 1),lost.units.troops)
        assertEquals(listOf(ActiveGroundUnit(3,troop,base=1000)),lost.activeGround!!.values)
        assertEquals(0,GridForces.mobileUnits(lost));assertEquals(1,GridForces.units(lost))
    }
    @Test fun deployedForcesStayAtTheirBaseWhenOutfitMovesToAnotherGrid() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2488,4,"Away",2)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val initial=ActiveShips.initialize(stocks());val old=initial.outfits.getValue(1)
        val next=initial.copy(outfits=mapOf(1 to old.copy(position=OutfitPosition(2488),
            activeGround=ActiveGroundUnits(listOf(ActiveGroundUnit(2,troop,carrier=1),ActiveGroundUnit(3,troop,base=1000))))))
        val forces=GridForces.collect(galaxy,1,emptyList(),next)
        assertEquals(1,forces.getValue(2487).sumOf {it.units})
        assertEquals(2,forces.getValue(2488).sumOf {it.units})
        assertFalse(forces.getValue(2487).single().ship)
        assertTrue(forces.getValue(2488).single().ship)
    }
    @Test fun invalidIdentityFactionCarrierAndMutableAliasesAreRejected() {
        val next=ActiveShips.initialize(stocks());val old=next.outfits.getValue(1)
        assertFailsWith<IllegalArgumentException> {ActiveGroundUnit(2,crew)}
        assertFailsWith<IllegalArgumentException> {ActiveGroundUnit(2,troop,1,1000)}
        assertFailsWith<IllegalArgumentException> {old.copy(activeGround=ActiveGroundUnits(listOf(
            ActiveGroundUnit(2,troop,carrier=99),ActiveGroundUnit(3,troop))))}
        val duplicate=old.copy(activeGround=ActiveGroundUnits(listOf(ActiveGroundUnit(1,troop),ActiveGroundUnit(3,troop))))
        assertFailsWith<IllegalArgumentException> {next.copy(outfits=mapOf(1 to duplicate))}
        assertFailsWith<IllegalArgumentException> {next.copy(nextShipId=3)}
        assertFailsWith<IllegalArgumentException> {ActiveShips.initialize(stocks().copy(nextShipId=999_999))}
        val input=mutableListOf(ActiveGroundUnit(2,troop));val roster=ActiveGroundUnits(input)
        input.clear();assertEquals(1,roster.values.size)
        assertFailsWith<UnsupportedOperationException> {(roster.values as MutableList).clear()}
        val enemy=old.copy(units=UnitInventory(mapOf(ship to 1),mapOf(UnitStack(4) to 1)),activeGround=null)
        assertFailsWith<IllegalArgumentException> {GroundUnits.initialize(next.copy(outfits=mapOf(1 to enemy)))}
    }
}
