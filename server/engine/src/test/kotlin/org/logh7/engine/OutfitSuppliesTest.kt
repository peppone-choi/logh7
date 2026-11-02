package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class OutfitSuppliesTest {
    @Test fun physicalShipCapacityDoesNotAddCrewOrCurrentStockAgain() {
        assertEquals(0,OutfitSupplies.capacity(UnitInventory()))
        assertEquals(456000,OutfitSupplies.capacity(UnitInventory(mapOf(UnitStack(32) to 2),mapOf(UnitStack(3) to 10))))
        assertEquals(5000,OutfitSupplies.capacity(UnitInventory(troops=mapOf(UnitStack(0) to 5))))
        assertFailsWith<ArithmeticException> { OutfitSupplies.capacity(UnitInventory(mapOf(UnitStack(32) to 10000))) }
    }

    private fun outfit() = OutfitStock(1,2,OutfitKind.FLEET,1000,10,
        units=UnitInventory(mapOf(UnitStack(32) to 2)),supplies=10000,
        activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32)),ActiveShipUnit(2,UnitStack(32)))))

    @Test fun spendingUsesTheFiringUnitAndTransfersPreserveTheOutfitTotal() {
        val allocated=OutfitSupplies.withBalances(outfit(),mapOf(1 to 2000,2 to 8000))
        val spent=OutfitSupplies.change(allocated,2,-1500)
        assertEquals(8500,spent.supplies)
        assertEquals(mapOf(1 to 2000,2 to 6500),OutfitSupplies.distribution(spent))
        assertEquals(listOf(2000,6500),spent.activeShips!!.values.map {it.supplies})
        val moved=OutfitSupplies.withBalances(spent,mapOf(1 to 7000,2 to 1500))
        assertEquals(spent.supplies,moved.supplies)
        assertEquals(mapOf(1 to 7000,2 to 1500),OutfitSupplies.distribution(moved))
        assertEquals(allocated.supplies,OutfitSupplies.change(spent,2,1500).supplies)
        assertEquals(listOf(2000,8000),allocated.activeShips!!.values.map {it.supplies})
        assertFailsWith<IllegalArgumentException> {OutfitSupplies.change(spent,2,-6501)}
        assertFailsWith<IllegalArgumentException> {OutfitSupplies.change(spent,3,1)}
        assertFailsWith<IllegalArgumentException> {OutfitSupplies.withBalances(spent,mapOf(1 to 8500))}
        assertFailsWith<IllegalArgumentException> {OutfitSupplies.change(spent,1,OutfitSupplies.unitCapacity(32))}
    }

    @Test fun legacyAndStrategicBulkChangesReconcileDeterministicallyWithoutCreatingSupplies() {
        assertEquals(mapOf(1 to 10000,2 to 0),OutfitSupplies.distribution(outfit()))
        val allocated=OutfitSupplies.withBalances(outfit(),mapOf(1 to 2000,2 to 8000))
        assertEquals(mapOf(1 to 3000,2 to 8000),OutfitSupplies.distribution(allocated.copy(supplies=11000)))
        assertEquals(mapOf(1 to 2000,2 to 7000),OutfitSupplies.distribution(allocated.copy(supplies=9000)))
        assertEquals(mapOf(1 to 1000,2 to 0),OutfitSupplies.distribution(allocated.copy(supplies=1000)))
        val partial=outfit().copy(activeShips=ActiveShipUnits(listOf(
            ActiveShipUnit(1,UnitStack(32),supplies=2000),ActiveShipUnit(2,UnitStack(32)))))
        val balances=OutfitSupplies.distribution(partial)
        assertEquals(10000,balances.values.sum())
        assertEquals(balances,OutfitSupplies.distribution(OutfitSupplies.withBalances(partial,balances)))
    }
}
