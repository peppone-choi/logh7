package org.logh7.protocol

import java.nio.ByteBuffer
import kotlin.test.*
import org.junit.jupiter.api.Test

class WorldMessagesTest {
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
