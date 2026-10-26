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
}
