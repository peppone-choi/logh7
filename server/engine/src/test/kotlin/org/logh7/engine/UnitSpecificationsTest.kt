package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class UnitSpecificationsTest {
    @Test fun everyProducedShipAndTroopHasAReviewedDefinitionWithSeparateFlagshipAndUnitSizes() {
        val ships = UnitSpecifications.ships.associateBy { it.kind }
        val crews = UnitSpecifications.crews.associateBy { it.kind }
        assertEquals(112, ships.size); assertEquals(11, crews.size)
        ProductionUnits.definitions.forEach { unit ->
            if (unit.ship) {
                val definition = ships.getValue(unit.kind)
                assertEquals("reviewed", definition.manual.verificationStatus)
                assertEquals(unit.hours, definition.number("建造工期:建造工期"))
                assertEquals(300, definition.number)
                assertTrue(definition.number("必要乗組員ユニット数:必要乗組員ユニット数") >= 0)
            } else assertEquals(unit.hours, crews.getValue(unit.kind).manual.training)
        }
        assertEquals(1, ships.getValue(0).number); assertEquals(1, ships.getValue(89).number)
        assertEquals(5, ships.getValue(32).number("必要乗組員ユニット数:必要乗組員ユニット数"))
        assertEquals(0, ships.getValue(88).number("必要乗組員ユニット数:必要乗組員ユニット数"))
        assertEquals(760, ships.getValue(32).number("物資搭載量:物資搭載量"))
        assertEquals(5, ships.getValue(32).number("ミサイル兵装破壊力/消費物資:ミサイル兵装破壊力/消費物資", 1))
        assertEquals(50, crews.getValue(2).manual.groundAttack)
        assertEquals(0, crews.getValue(3).manual.groundAttack ?: 0)
    }
}
