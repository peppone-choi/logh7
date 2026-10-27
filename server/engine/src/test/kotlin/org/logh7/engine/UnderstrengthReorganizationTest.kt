package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** A remainder is selectable stock, never a freshly filled unit. Author: 최병호. */
class UnderstrengthReorganizationTest {
    private val ship=UnitStack(32)
    private fun initial()=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,0,0,0)),
        mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,101,11,
            UnitInventory(mapOf(ship to 1),mapOf(UnitStack(3) to 5)),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,ship,280))))),
        mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1049,1,
            UnitInventory(mapOf(ship to 1)),spareShips=SpareShipBoats(mapOf(ship to 123)))),
        deploymentInitialized=true,nextShipId=2)
    private fun boats(s:PlanetStocks)=s.outfits.getValue(1).activeShips!!.values.sumOf {it.boats}+
        s.warehouses.getValue(101).let {it.units.ships.values.sum()*300+it.spareShips.values.values.sum()}
    private fun transfer(s:PlanetStocks,n:Int)=Reorganization.transfer(ReorganizationRequest(
        "pilot","c",11,Power.EMPIRE,1,1049,1,0,1,listOf(ReorganizationShip(32,n)),emptyList()),s)
    @Test fun storageTransfersCombineRemaindersWithoutChangingPhysicalBoatsOrGrades() {
        val other=ship.copy(level=2)
        val source=UnitInventory(mapOf(ship to 1),work=mapOf("S32" to 2))
        val destination=UnitInventory(mapOf(other to 2))
        val moved=ReplacementBoats.moveStored(source,SpareShipBoats(mapOf(ship to 123)),
            destination,SpareShipBoats(mapOf(ship to 177)),UnitTransfer(mapOf(ship to 2)))
        assertTrue(moved.source.ships.isEmpty());assertTrue(moved.sourceSpare.values.isEmpty())
        assertEquals(mapOf(ship to 2,other to 2),moved.destination.ships)
        assertTrue(moved.destinationSpare.values.isEmpty())
        assertEquals(mapOf("S32" to 2),moved.source.work)
        val reversed=ReplacementBoats.moveStored(moved.source,moved.sourceSpare,moved.destination,
            moved.destinationSpare,UnitTransfer(mapOf(other to -1)))
        assertEquals(mapOf(other to 1),reversed.source.ships)
        assertEquals(mapOf(ship to 2,other to 1),reversed.destination.ships)
        assertFails {ReplacementBoats.moveStored(UnitInventory(),SpareShipBoats(),UnitInventory(),
            SpareShipBoats(),UnitTransfer(mapOf(ship to 1)))}
    }
    @Test fun fullAndRemainderUnitsKeepActualBoatsAndGradesAcrossReturnAndPreview() {
        val before=initial()
        val after=ReplacementBoats.reorganize(before,1,transfer(before,2))
        val units=after.outfits.getValue(1).activeShips!!.values
        assertEquals(listOf(280,300,123),units.map {it.boats})
        assertEquals(listOf(1,2,3),units.map {it.id})
        assertEquals(4,after.nextShipId)
        assertTrue(after.warehouses.getValue(101).units.ships.isEmpty())
        assertTrue(after.warehouses.getValue(101).spareShips.values.isEmpty())
        assertEquals(boats(before),boats(after))
        val capacity=Reorganization.capacity(before,1,transfer(before,2))
        assertEquals(13,capacity.crew);assertEquals(8,capacity.crewDelta)
        val returned=ReplacementBoats.reorganize(after,1,transfer(after,-2))
        assertEquals(before.outfits.getValue(1).activeShips,returned.outfits.getValue(1).activeShips)
        assertEquals(before.warehouses.getValue(101),returned.warehouses.getValue(101))
        assertEquals(4,returned.nextShipId)
        assertEquals(boats(before),boats(returned))
    }
    @Test fun repeatedRoundTripsNeverRefillRemainderAndRejectInsufficientSource() {
        val before=initial()
        var state=ReplacementBoats.reorganize(before,1,transfer(before,1))
        assertEquals(123,state.warehouses.getValue(101).spareShips.values[ship])
        assertEquals(300,state.outfits.getValue(1).activeShips!!.values.last().boats)
        repeat(4) {
            state=ReplacementBoats.reorganize(state,1,transfer(state,1))
            assertEquals(123,state.outfits.getValue(1).activeShips!!.values.last().boats)
            state=ReplacementBoats.reorganize(state,1,transfer(state,-1))
            assertEquals(123,state.warehouses.getValue(101).spareShips.values[ship])
            assertEquals(boats(before),boats(state))
        }
        assertFailsWith<IllegalArgumentException> {transfer(state,2)}
    }
}
