package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class UnitTransferTest {
    private val battleship = UnitStack(32, 0)
    private val veteran = UnitStack(32, 1)
    private val crew = UnitStack(3, 0)
    @Test fun exchangeConservesEachKindAndLevelAndPreservesProductionProgress() {
        val source = UnitInventory(mapOf(battleship to 10), mapOf(crew to 8), mapOf("S32" to 12))
        val target = UnitInventory(mapOf(veteran to 3), work = mapOf("S40" to 3))
        val transfer = UnitTransfer(mapOf(battleship to 7, veteran to -3), mapOf(crew to 8))
        val result = transfer.apply(source, target)
        assertEquals(mapOf(battleship to 3, veteran to 3), result.source.ships)
        assertEquals(mapOf(battleship to 7), result.destination.ships)
        assertEquals(emptyMap(), result.source.troops); assertEquals(mapOf(crew to 8), result.destination.troops)
        assertEquals(source.work, result.source.work); assertEquals(target.work, result.destination.work)
        assertEquals(source, UnitTransfer(mapOf(battleship to -7, veteran to 3), mapOf(crew to -8))
            .apply(result.source, result.destination).source)
        assertEquals(10, source.ships[battleship]); assertEquals(3, target.ships[veteran])
    }
    @Test fun aLaterFailureCannotPartiallyMoveAnEarlierStack() {
        val source = UnitInventory(mapOf(battleship to 10), mapOf(crew to 1))
        val target = UnitInventory()
        assertFails { UnitTransfer(mapOf(battleship to 7), mapOf(crew to 2)).apply(source, target) }
        assertEquals(10, source.ships[battleship]); assertTrue(target.ships.isEmpty())
        assertFails { UnitTransfer(mapOf(veteran to 1)).apply(source, target) }
        assertFails { UnitTransfer(mapOf(battleship to -1)).apply(source, target) }
    }
    @Test fun overflowingQuantitiesAndNativeStackLimitsAreRejected() {
        assertFails { UnitTransfer(mapOf(battleship to 1)).apply(UnitInventory(mapOf(battleship to 1)),
            UnitInventory(mapOf(battleship to 65535))) }
        val full = UnitInventory((0..98).associate { UnitStack(it) to 1 })
        assertFails { UnitTransfer(mapOf(UnitStack(99) to 1)).apply(UnitInventory(mapOf(UnitStack(99) to 1)), full) }
        assertEquals(99, full.ships.size)
        assertFails { UnitTransfer(mapOf(battleship to Int.MIN_VALUE)) }
        assertFails { UnitTransfer(mapOf(battleship to 0)) }
        assertFails { UnitTransfer(troops = mapOf(UnitStack(16) to 1)) }
    }
    @Test fun transferCannotChangeAfterValidationThroughCallersMutableMaps() {
        val amounts = linkedMapOf(battleship to 1)
        val transfer = UnitTransfer(amounts)
        amounts[battleship] = 10
        assertEquals(1, transfer.apply(UnitInventory(mapOf(battleship to 2)), UnitInventory()).destination.ships[battleship])
        assertFailsWith<UnsupportedOperationException> { (transfer.ships as MutableMap).clear() }
    }
}
