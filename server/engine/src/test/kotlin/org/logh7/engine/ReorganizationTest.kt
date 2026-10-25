package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class ReorganizationTest {
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
        mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,101,10000,
            UnitInventory(mapOf(UnitStack(32,2) to 1)))),
        mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1049,1,
            UnitInventory(mapOf(UnitStack(32,0) to 1,UnitStack(32,1) to 2)))),deploymentInitialized=true)
    private fun request(ships: List<ReorganizationShip>, troops: List<ReorganizationTroop> = emptyList(), base: Int = 1049,
        kind: Int = 0, mode: Int = 1, supplies: Int = 0) =
        ReorganizationRequest("pilot","c",10000,Power.EMPIRE,1,base,1,kind,mode,ships,troops,supplies)
    @Test fun gradeLessShipRowsPreserveSourceLevelsAndConserveBothInventories() {
        val transfer=Reorganization.transfer(request(listOf(ReorganizationShip(32,2))),stocks)
        val moved=transfer.apply(stocks.warehouses.getValue(101).units,stocks.outfits.getValue(1).units)
        assertEquals(mapOf(UnitStack(32,1) to 1),moved.source.ships)
        assertEquals(mapOf(UnitStack(32,0) to 1,UnitStack(32,1) to 1,UnitStack(32,2) to 1),moved.destination.ships)
        assertEquals(4,moved.source.ships.values.sum()+moved.destination.ships.values.sum())
        val reverse=Reorganization.transfer(request(listOf(ReorganizationShip(32,-1))),stocks)
            .apply(stocks.warehouses.getValue(101).units,stocks.outfits.getValue(1).units)
        assertEquals(1,reverse.source.ships[UnitStack(32,2)]); assertTrue(reverse.destination.ships.isEmpty())
    }
    @Test fun ambiguousDuplicatePartialUnknownAndMismatchedRowsAreRejected() {
        val row=ReorganizationShip(32,1)
        for (rows in listOf(listOf(row,row),listOf(row.copy(units=0)),listOf(row.copy(boats=300)),
            listOf(row.copy(kind=999)),listOf(row.copy(units=4))))
            assertFailsWith<IllegalArgumentException> { Reorganization.transfer(request(rows),stocks) }
        assertFails { Reorganization.transfer(request(listOf(row),base=1050),stocks) }
        assertFails { Reorganization.transfer(request(listOf(row),kind=1),stocks) }
        assertFails { Reorganization.transfer(request(listOf(row),mode=2),stocks) }
        assertFails { Reorganization.transfer(request(listOf(row),supplies=1),stocks) }
        val crew=ReorganizationTroop(1003,0,5)
        assertFails { Reorganization.transfer(request(emptyList(),listOf(crew),mode=0),stocks) }
        assertFails { Reorganization.transfer(request(emptyList(),listOf(crew,crew)),stocks) }
        assertFails { Reorganization.transfer(request(emptyList(),listOf(crew.copy(kind=3))),stocks) }
        assertFails { Reorganization.transfer(request(emptyList(),listOf(crew.copy(units=0))),stocks) }
    }
    @Test fun aQueuedRequestCannotBeChangedThroughTheCallersLists() {
        val rows=mutableListOf(ReorganizationShip(32,1)); val request=request(rows)
        rows.clear()
        assertEquals(1,request.ships.size)
        assertFailsWith<UnsupportedOperationException> { (request.ships as MutableList).clear() }
    }
}
