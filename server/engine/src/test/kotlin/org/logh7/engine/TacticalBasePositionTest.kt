package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalBasePositionTest {
    private fun base(radius:Float,cycle:Int=86400,angle:Float=0f,direction:Int=0,kind:Int=0)=
        GalaxyBase(1048,2487,60,kind,"Base",0,radius,cycle,direction,angle,2f)
    @Test fun capturedNativeBaseCoordinatesAtTheCapturedGameClockMatchExactly() {
        // Actual run-175 memory samples, game clock 791544; these are independent golden values.
        for((b,expected) in listOf(base(12f) to TacticalPoint(10.187660217285156f,6.341259956359863f),
            base(.001f,172800) to TacticalPoint(-.00048557258560322225f,-.0008741964120417833f),
            base(28f,259200,94f) to TacticalPoint(25.703594207763672f,-11.105189323425293f),
            base(36f,345600,141f) to TacticalPoint(-32.765113830566406f,-14.914666175842285f))) {
            assertEquals(expected,TacticalBasePosition.at(b,791544))
        }
    }
    @Test fun axesPeriodDirectionAndFixedFortressAreDistinct() {
        val planet=base(20f,1000)
        assertEquals(TacticalPoint(0f,20f),TacticalBasePosition.at(planet,0))
        assertEquals(TacticalBasePosition.at(planet,250),TacticalBasePosition.at(planet,1250))
        val prograde=TacticalBasePosition.at(planet,250)
        val retrograde=TacticalBasePosition.at(planet.copy(direction=1),250)
        assertEquals(-prograde.x,retrograde.x);assertEquals(prograde.y,retrograde.y)
        val fortress=planet.copy(kind=1,angle=45f)
        assertEquals(TacticalBasePosition.at(fortress,0),TacticalBasePosition.at(fortress,999))
        assertTrue(TacticalBasePosition.at(planet,0xffffffffL).x<0f) // Native signed remainder is -1.
        assertFailsWith<IllegalArgumentException>{TacticalBasePosition.at(planet,-1)}
        assertFailsWith<IllegalArgumentException>{TacticalBasePosition.at(planet,0x100000000L)}
    }
}
