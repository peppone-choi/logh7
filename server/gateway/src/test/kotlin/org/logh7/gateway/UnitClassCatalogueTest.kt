package org.logh7.gateway

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import kotlin.test.*

/** Native editor classes: 004c6d20 mask and 00534160 totals. Author: 최병호. */
class UnitClassCatalogueTest {
    private val catalogues=WorldBootstrap(Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),emptyList(),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))).catalogues

    @Test fun fleetShipsRemainAllowedAndCrewIsNotCountedAsInfantry() {
        val input=ByteBuffer.wrap(catalogues.getValue(0x030a))
        val classes=mutableMapOf<Int,Int>()
        repeat(input.get().toInt() and 255) {
            val start=input.position()
            val kind=input.short.toInt() and 65535
            classes[kind]=input.get().toInt() and 255
            input.position(start+8)
            val length=input.get().toInt() and 255
            input.position(start+106+length*2)
        }
        assertFalse(input.hasRemaining())
        assertEquals(114,classes.size)
        // Both factions' existing deployment: battleship, cruiser, destroyer.
        for ((kind,type) in mapOf(32 to 3,48 to 5,56 to 6,119 to 3,131 to 5,139 to 6)) {
            assertEquals(type,classes.getValue(kind))
            assertTrue((0x823fb and (1 shl classes.getValue(kind)))!=0)
        }
        for (kind in listOf(0,2,3,89,90,93)) assertEquals(0,classes.getValue(kind))
        val crews=ByteBuffer.wrap(catalogues.getValue(0x030c))
        val troopClasses=mutableMapOf<Int,Int>()
        repeat(crews.get().toInt() and 255) {
            val start=crews.position()
            val kind=crews.short.toInt() and 65535
            troopClasses[kind]=crews.get().toInt() and 255
            crews.position(start+22)
        }
        assertFalse(crews.hasRemaining())
        assertEquals(19,troopClasses.getValue(1003));assertEquals(19,troopClasses.getValue(1007))
        assertEquals(16,troopClasses.getValue(1000));assertEquals(16,troopClasses.getValue(1004))
    }
}
