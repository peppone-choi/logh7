package org.logh7.protocol

import java.nio.ByteBuffer
import kotlin.test.*
import org.junit.jupiter.api.Test

class WorldMessagesTest {
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
