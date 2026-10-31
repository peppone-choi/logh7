package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** update03 guard-ship catalogue. Author: Peppone Choi. */
class GuardShipsTest {
    @Test fun eachFactionHasThirteenOfficialPricesAndRanks() {
        for(power in 2..3) {
            val list=GuardShips.forPower(power)
            assertEquals(13,list.size)
            assertEquals(listOf(20000L,500L),listOf(list.first().price,list.last().price))
            assertEquals(7,list.first().maximumRank);assertEquals(20,list.last().maximumRank)
            assertTrue(list.zipWithNext().all {(a,b)->a.price>=b.price})
        }
        assertEquals((0..25).toList(),GuardShips.options.map {it.index})
        assertEquals(8,GuardShips.option(16)!!.maximumRank)
    }
    @Test fun baseHullsBelongToTheBuyersFactionAndKeepAWeapon() {
        for(o in GuardShips.options) {
            val base=UnitSpecifications.ships.single {it.kind==o.baseKind}
            assertTrue(o.modifier.weapons.isNotEmpty())
            assertTrue(ProductionUnits.definitions.single {it.kind==base.kind && it.ship}.power==o.power)
        }
    }
}
