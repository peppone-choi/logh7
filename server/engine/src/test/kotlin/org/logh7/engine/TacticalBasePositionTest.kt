package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalBasePositionTest {
    @Test fun anchoredPoseRetainsItsWorldOffsetAndStopRebasingDoesNotLoseTheOrbit() {
        val b=base(20f,1000)
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Base",2)),listOf(b),Power.entries.associateWith {2487})
        val origin=TacticalPose(TacticalPoint(2f,23f),.4f)
        val motion=TacticalMotion(2487,0,origin,0f,.4f,listOf(origin.position))
        val anchored=TacticalPosture(2487,TacticalShipMode.ANCHORED,b.id)
        val quarter=motion.pose(250,anchored,galaxy)
        assertEquals(22f,quarter.position.x,.00001f);assertEquals(3f,quarter.position.y,.00001f)
        val stopped=TacticalMotion(2487,250,quarter,0f,quarter.direction,listOf(quarter.position))
        assertEquals(motion.pose(500,anchored,galaxy).position.x,stopped.pose(500,anchored,galaxy).position.x,.00001f)
        assertEquals(motion.pose(500,anchored,galaxy).position.y,stopped.pose(500,anchored,galaxy).position.y,.00001f)
        assertEquals(origin,motion.pose(250,anchored.copy(grid=2488),galaxy))
        assertEquals(origin,motion.pose(250,anchored.copy(base=999),galaxy))
        assertEquals(origin,motion.pose(250,TacticalPosture(2487),galaxy))
        assertEquals(TacticalBasePosition.at(b,250),motion.pose(250,anchored.copy(mode=TacticalShipMode.STATIONED),galaxy).position)
    }
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
