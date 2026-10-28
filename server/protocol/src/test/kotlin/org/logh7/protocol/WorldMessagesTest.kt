package org.logh7.protocol

import java.nio.ByteBuffer
import kotlin.test.*
import org.junit.jupiter.api.Test

class WorldMessagesTest {
    @Test fun officeCardsCarryTheParentOfficeThatNativeAppointmentFilteringReads() {
        val input=ByteBuffer.wrap(WorldMessages.cards())
        assertEquals(261,input.short.toInt())
        repeat(261) { kind ->
            val record=input.position()
            assertEquals(kind,input.short.toInt())
            if(kind==55 || kind==54)assertEquals(16,input.get(record+5).toInt())
            if(kind in setOf(52,55,188,191)) {
                assertEquals(10,input.get(record+10).toInt());assertEquals(2,input.get(record+11).toInt())
            }
            if(kind in setOf(56,57,192,193)) {
                assertEquals(17,input.get(record+5).toInt())
                assertEquals(if(kind<136)2 else 3,input.get(record+3).toInt())
                assertEquals(when(kind) { 56->4;57->3;192->1;else->8 },input.get(record+10).toInt())
                assertEquals(if(kind==192)1 else 2,input.get(record+11).toInt())
            }
            if(kind in setOf(2,17,134,161)) {
                assertEquals(if(kind in setOf(2,17))2 else 3,input.get(record+3).toInt())
                assertEquals(when(kind){2->0;17->3;134->20;else->23},input.get(record+5).toInt())
            }
            if(kind in setOf(24,25,150)) {
                assertEquals(if(kind==150)3 else 2,input.get(record+3).toInt())
                assertEquals(if(kind==150)21 else 4,input.get(record+5).toInt())
                assertEquals(if(kind==25)5 else 4,input.get(record+10).toInt())
                assertEquals(2,input.get(record+11).toInt())
                assertEquals(if(kind==150)147 else 22,input.getShort(record+6).toInt())
            }
            input.position(record+6)
            val parent=input.short.toInt()
            when(kind) {
                54 -> assertEquals(6,parent)
                55 -> assertEquals(54,parent)
                52 -> assertEquals(51,parent)
                188 -> assertEquals(187,parent)
                56 -> assertEquals(2,parent)
                57 -> assertEquals(17,parent)
                192 -> assertEquals(134,parent)
                193 -> assertEquals(161,parent)
            }
            input.position(record+18)
            val count=input.get().toInt() and 255
            if(kind in setOf(24,25,150)) {assertEquals(1,count);assertEquals(67,input.getShort(input.position()).toInt())}
            if(kind==192) { assertEquals(2,count);assertEquals(7,input.getShort(input.position()).toInt());assertEquals(30,input.getShort(input.position()+2).toInt()) }
            if(kind in setOf(2,3,17,134,161,169,171)) {
                assertEquals(3,count)
                assertEquals(listOf(5,6,7),List(count) {input.getShort(input.position()+it*2).toInt()})
            }
            input.position(input.position()+count*2)
        }
        assertFalse(input.hasRemaining())
    }
    @Test fun shipCatalogueSeparatesClassFromModelAndWritesNativeFloats() {
        val ship = WorldMessages.Ship(93, 3, 1014, "Ship", 220, 3200f, 30000f,
            10, 6, 4, 40, 19, 24, 0, 50, 50, 40,commandRange=20f)
        val input = ByteBuffer.wrap(WorldMessages.ships(listOf(ship)))
        assertEquals(117, input.limit())
        assertEquals(1, input.get().toInt()); assertEquals(93, input.short.toInt())
        assertEquals(3, input.get().toInt()); assertEquals(0, input.get().toInt())
        assertEquals(0, input.short.toInt()); assertEquals(1014, input.short.toInt())
        assertEquals(5, input.get().toInt()); repeat(5) { input.char }
        assertEquals(1, input.short.toInt()); assertEquals(0, input.int)
        repeat(5) { assertEquals(0, input.short.toInt()) }
        assertEquals(220, input.short.toInt())
        assertEquals(20f, input.float); assertEquals(3200f, input.float)
        repeat(13) { input.short }
        assertEquals(30000f, input.float); assertEquals(1f, input.float)
        assertEquals(listOf(10, 4, 6, 40, 19), List(5) { input.short.toInt() })
        assertEquals(24, input.getShort(91).toInt()); assertEquals(40, input.getShort(113).toInt())
        assertFailsWith<IllegalArgumentException> { WorldMessages.ships(listOf(ship, ship)) }
        assertFailsWith<IllegalArgumentException> { WorldMessages.ships(listOf(ship.copy(model = 120))) }
        assertFailsWith<IllegalArgumentException> { WorldMessages.ships(listOf(ship.copy(model = 51))) }
        assertFailsWith<IllegalArgumentException> { WorldMessages.ships(listOf(ship.copy(commandRange = -1f))) }
        assertFailsWith<IllegalArgumentException> { WorldMessages.ships(listOf(ship.copy(commandRange = Float.NaN))) }
    }
    @Test fun armamentProfilesAndShipSectorMasksAreIndependentFromWeaponPower() {
        val profiles = List(27) { row -> List(8) { column -> row * 8 + column - 100 } }
        val table = ByteBuffer.wrap(WorldMessages.armaments(profiles))
        assertEquals(432, table.remaining())
        repeat(216) { assertEquals(it - 100, table.short.toInt()) }
        assertFailsWith<IllegalArgumentException> { WorldMessages.armaments(profiles.dropLast(1)) }
        assertFailsWith<IllegalArgumentException> { WorldMessages.armaments(profiles + listOf(profiles.first())) }
        assertFailsWith<IllegalArgumentException> { WorldMessages.armaments(profiles.toMutableList().also { it[0] = listOf(1) }) }
        assertFailsWith<IllegalArgumentException> { WorldMessages.armaments(List(27) { List(8) { 32768 } }) }
        val ship = WorldMessages.Ship(93, 3, 1014, "Ship", 220, 3200f, 30000f,
            10, 6, 4, 40, 19, 24, 0, 50, 50, 40,
            beamArms = 26, beamAngles = 0x21, gunArms = 255, gunAngles = 0,
            missileArms = 2, missileAngles = 0x3f, antiaircraftArms = 3)
        val wire = ByteBuffer.wrap(WorldMessages.ships(listOf(ship)))
        assertEquals(117, wire.limit())
        assertEquals(26, wire.get(90).toInt() and 255); assertEquals(24, wire.getShort(91).toInt())
        assertEquals(0x21, wire.get(93).toInt()); assertEquals(255, wire.get(94).toInt() and 255)
        assertEquals(2, wire.get(98).toInt()); assertEquals(50, wire.getShort(99).toInt())
        assertEquals(0x3f, wire.get(101).toInt()); assertEquals(3, wire.get(104).toInt())
        for (invalid in listOf(ship.copy(beamArms = 27), ship.copy(gunArms = -1),
            ship.copy(missileArms = 254), ship.copy(antiaircraftArms = 256), ship.copy(beamAngles = 64),
            ship.copy(gunAngles = -1), ship.copy(missileAngles = 255))) {
            assertFailsWith<IllegalArgumentException> { WorldMessages.ships(listOf(invalid)) }
        }
    }
    @Test fun baseStatePreservesSeparateFactionFieldsAndEnvironmentAtNativeWireOffsets() {
        val state = WorldMessages.Condition(1049, 2487, 2, 0, 100_000_000, 60_000_000, 20,
            70, 80, 1, 1, 100_000, 1_000_000, 1_000_000)
        val wire = WorldMessages.conditions(listOf(state))
        val input = ByteBuffer.wrap(wire)
        assertEquals(127, wire.size)
        assertEquals(1, input.get().toInt()); assertEquals(1049, input.int)
        assertEquals(2, input.get().toInt()); assertEquals(0, input.get().toInt())
        assertEquals(2487, input.int)
        assertEquals(100_000_000, input.getInt(41)); assertEquals(60_000_000, input.getInt(45))
        assertEquals(20, input.getShort(49).toInt())
        assertEquals(1, input.get(118).toInt()); assertEquals(1, input.get(119).toInt())
        assertEquals(0, input.getInt(123))
        assertFailsWith<IllegalArgumentException> { WorldMessages.conditions(listOf(state.copy(camp = 2))) }
    }
    @Test fun institutionWireCarriesParentAndImageIdsWithoutPadding() {
        val bytes = WorldMessages.institutions(listOf(WorldMessages.BaseInstitutions(1000,
            listOf(WorldMessages.Institution(4, 100005, listOf(WorldMessages.Spot(6, 10000807, 5)))))))
        assertContentEquals(java.util.HexFormat.of().parseHex("01000003e8010004000186a5010006009899a70005"), bytes)
        assertFailsWith<IllegalArgumentException> {
            WorldMessages.institutions(List(5) { WorldMessages.BaseInstitutions(it + 1, emptyList()) })
        }
    }
    @Test fun baseQueriesUseIdsAndRejectTruncationDuplicatesAndOversizedLists() {
        val input = ByteBuffer.allocate(11).putShort(0x0320).put(2).putInt(1000).putInt(1001).array()
        assertEquals(listOf(1000, 1001), WorldMessages.baseQuery(input, 0x0320))
        assertFailsWith<IllegalArgumentException> { WorldMessages.baseQuery(input.dropLast(1).toByteArray(), 0x0320) }
        assertFailsWith<IllegalArgumentException> { WorldMessages.baseQuery(input.also { ByteBuffer.wrap(it).putInt(7, 1000) }, 0x0320) }
    }
    @Test fun gridRunsReconstructExactlyFiveThousandCellsIncludingBoundaryRuns() {
        val cells = MutableList(5000) { 1 }.also { it[0] = 3; it[255] = 4; it[4999] = 88 }
        val input = ByteBuffer.wrap(WorldMessages.grid(cells))
        assertEquals(100, input.get().toInt()); assertEquals(50, input.get().toInt())
        assertEquals(input.remaining() - 2, input.short.toInt() and 65535)
        val restored = mutableListOf<Int>()
        while (input.hasRemaining()) {
            val count = input.get().toInt() and 255
            val kind = input.get().toInt() and 255
            assertTrue(count > 0)
            repeat(count) { restored += kind }
        }
        assertEquals(cells, restored)
        assertFailsWith<IllegalArgumentException> { WorldMessages.grid(cells.dropLast(1)) }
    }
    @Test fun baseCatalogueCarriesItsGridAndOrbitalParametersWithoutNativePadding() {
        val base = WorldMessages.Base(1049, 2487, 0, 0, "Planet", 0, 20f, 172800, 0, 47f, 2f)
        val input = ByteBuffer.wrap(WorldMessages.bases(listOf(base)))
        assertEquals(1, input.short.toInt()); assertEquals(1049, input.int)
        assertEquals(2487, input.short.toInt()); assertEquals(0, input.short.toInt()); assertEquals(0, input.short.toInt())
        assertEquals(7, input.get().toInt())
        assertEquals("Planet\u0000", CharArray(7) { input.char }.concatToString())
        assertEquals(0, input.get().toInt()); assertEquals(20f, input.float)
        assertEquals(172800, input.int); assertEquals(0, input.get().toInt())
        assertEquals(47f, input.float); assertEquals(2f, input.float)
        assertFalse(input.hasRemaining())
    }
}
