package org.logh7.engine

import kotlin.test.*
import org.junit.jupiter.api.Test

class ActiveShipUnitsTest {
    private val green = UnitStack(32)
    private val veteran = UnitStack(32, 2)
    private fun stocks() = PlanetStocks(1, 12, mapOf(1000 to PlanetStock(1000,2,0,100,200,300)),
        mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,101, units=UnitInventory(mapOf(green to 2,veteran to 1))),
            2 to OutfitStock(2,2,OutfitKind.FLEET,1000,102, units=UnitInventory(mapOf(green to 1)))),
        mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1000,1),
            102 to OutfitWarehouse(102,2,OutfitKind.FLEET,1000,2)), deploymentInitialized=true)

    @Test fun legacyQuantitiesAreCommissionedOnceWithoutChangingStocksOrWatermark() {
        val before = stocks()
        val after = ActiveShips.initialize(before)
        assertEquals(listOf(1,2,3),after.outfits.getValue(1).activeShips!!.values.map { it.id })
        assertEquals(listOf(4),after.outfits.getValue(2).activeShips!!.values.map { it.id })
        assertEquals(5,after.nextShipId)
        assertSame(after,ActiveShips.initialize(after))
        assertEquals(before.bases,after.bases)
        assertEquals(before.warehouses,after.warehouses)
        assertEquals(before.producedDays,after.producedDays)
        assertEquals(before.outfits.mapValues { it.value.units },after.outfits.mapValues { it.value.units })
    }

    @Test fun retainingReturningAndRecommissioningUnitsNeverReusesRetiredIds() {
        val start = ActiveShips.initialize(stocks())
        val smaller = ActiveShips.reorganize(start,1,UnitInventory(mapOf(green to 1,veteran to 1)))
        assertEquals(listOf(1,3),smaller.outfits.getValue(1).activeShips!!.values.map { it.id })
        val empty = ActiveShips.reorganize(smaller,1,UnitInventory())
        assertTrue(empty.outfits.getValue(1).activeShips!!.values.isEmpty())
        val returned = ActiveShips.reorganize(empty,1,UnitInventory(mapOf(green to 2)))
        assertEquals(listOf(5,6),returned.outfits.getValue(1).activeShips!!.values.map { it.id })
        assertEquals(7,returned.nextShipId)
        assertEquals(start.outfits.getValue(2),returned.outfits.getValue(2))
        assertEquals(5,start.nextShipId)
    }

    @Test fun invalidCountsDuplicatesSequenceAndExhaustionAreRejectedBeforePublication() {
        val start = ActiveShips.initialize(stocks())
        assertFailsWith<IllegalArgumentException> { start.outfits.getValue(1).copy(units=UnitInventory()) }
        assertFailsWith<IllegalArgumentException> { start.copy(nextShipId=4) }
        val other = start.outfits.getValue(2).copy(activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,green))))
        assertFailsWith<IllegalArgumentException> { start.copy(outfits=start.outfits+(2 to other)) }
        val exhausted = start.copy(nextShipId=1_000_000)
        assertFailsWith<IllegalArgumentException> { ActiveShips.reorganize(exhausted,1,UnitInventory(mapOf(green to 3,veteran to 1))) }
        assertEquals(start.outfits,exhausted.outfits)
        assertFailsWith<IllegalArgumentException> { ActiveShips.initialize(stocks().copy(nextShipId=999_998)) }
        assertFailsWith<IllegalArgumentException> { ActiveShips.reorganize(start,1,UnitInventory(mapOf(green to 4201))) }
    }

    @Test fun identityListsCannotBeMutatedThroughTheirInputOrPublishedViews() {
        val input = mutableListOf(ActiveShipUnit(1,green))
        val units = ActiveShipUnits(input)
        input.clear()
        assertEquals(1,units.values.size)
        assertFailsWith<UnsupportedOperationException> { (units.values as MutableList).clear() }
    }
}
