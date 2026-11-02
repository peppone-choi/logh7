package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalCommandRangeTest {
    @Test fun nativeGrowthUsesCommandAndStaminaFloorAndClampsAtMaximum() {
        val range=TacticalCommandRange(100)
        assertEquals(0.0625,range.growth)
        assertEquals(5f,range.radius(180));assertEquals(20f,range.radius(420));assertEquals(20f,range.radius(10000))
        assertEquals(4f,range.copy(stamina=0).radius(180))
        assertEquals(4f,range.copy(stamina=80).radius(180))
        assertEquals(10f,range.copy(command=100).radius(180))
        assertFailsWith<IllegalArgumentException>{range.radius(99)}
        assertFailsWith<IllegalArgumentException>{range.copy(radius=Float.NaN)}
        assertFailsWith<IllegalArgumentException>{range.copy(radius=20.1f)}
        assertFailsWith<IllegalArgumentException>{range.copy(command=-1)}
    }
    @Test fun resetStartsRegrowthAndSkillChangesDoNotApplyRetroactively() {
        val range=TacticalCommandRange(100).settle(180,100)
        assertEquals(5f,range.radius);assertEquals(10f,range.radius(220))
        assertEquals(0f,range.reset(220).radius(220));assertEquals(5f,range.reset(220).radius(260))
        assertEquals(TacticalCommandRange(100).radius(107),
            (101L..107).fold(TacticalCommandRange(100)){r,t->r.settle(t)}.radius(107))
    }
}
