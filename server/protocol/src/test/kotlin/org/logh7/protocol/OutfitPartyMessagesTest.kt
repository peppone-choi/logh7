package org.logh7.protocol

import java.nio.ByteBuffer
import kotlin.test.*
import org.junit.jupiter.api.Test

class OutfitPartyMessagesTest {
    @Test fun partyPreservesRealIdsAndNativeWidthsWithoutCppPadding() {
        val bytes = OutfitPartyMessages.party(OutfitPartyMessages.Query(1,1049,2),2,0,1,
            listOf(OutfitPartyMessages.Ships(32,600,listOf(101,102))),
            listOf(OutfitPartyMessages.Troops(1003,2,10)),123,456)
        val input = ByteBuffer.wrap(bytes)
        assertEquals(1,input.int); assertEquals(1049,input.int); assertEquals(2,input.get().toInt())
        assertEquals(2,input.get().toInt()); assertEquals(0,input.get().toInt())
        assertEquals(0,input.int); assertEquals(1,input.int); assertEquals(0,input.get().toInt())
        assertEquals(1,input.get().toInt()); assertEquals(32,input.short.toInt()); assertEquals(2,input.get().toInt())
        assertEquals(600,input.short.toInt()); assertEquals(2,input.get().toInt()); assertEquals(101,input.int); assertEquals(102,input.int)
        assertEquals(1,input.get().toInt()); assertEquals(1003,input.short.toInt()); assertEquals(2,input.get().toInt()); assertEquals(10,input.short.toInt())
        assertEquals(123,input.int); assertEquals(456,input.int); assertEquals(0,input.short.toInt())
        repeat(7) { assertEquals(0,input.get().toInt()) }
        assertFalse(input.hasRemaining())
    }
    @Test fun singleUnitUsesTheOriginalOutfitUnitResponseCountAndFieldPositions() {
        val bytes = OutfitPartyMessages.units(listOf(OutfitPartyMessages.UnitInformation(101,32,2487,1,1049)))
        assertEquals(43,bytes.size)
        val b = ByteBuffer.wrap(bytes)
        assertEquals(1,b.get().toInt()); assertEquals(101,b.int); assertEquals(32,b.short.toInt()); assertEquals(4,b.get().toInt())
        assertEquals(2487,b.int); assertEquals(1,b.int); assertEquals(0,b.int); assertEquals(0,b.get().toInt())
        assertEquals(1049,b.int); assertEquals(100,b.get().toInt()); assertEquals(0,b.get().toInt())
        assertEquals(0,b.short.toInt()); assertEquals(0,b.short.toInt()); assertEquals(0,b.int); assertEquals(0,b.int); assertEquals(0f,b.float)
        assertFalse(b.hasRemaining())
    }
    @Test fun requestsRequireExactLengthsAndRepliesRejectNativeOverrunsAndDuplicateIds() {
        val query = ByteBuffer.allocate(11).putShort(0x032e).putInt(1).putInt(1049).put(255.toByte()).array()
        assertEquals(OutfitPartyMessages.Query(1,1049,255),OutfitPartyMessages.query(query))
        for (length in 0 until query.size) assertFails { OutfitPartyMessages.query(query.copyOf(length)) }
        assertFails { OutfitPartyMessages.query(query+byteArrayOf(0)) }
        val unit = ByteBuffer.allocate(10).putShort(0x0330).putInt(101).putInt(1049).array()
        assertEquals(OutfitPartyMessages.UnitQuery(101,1049),OutfitPartyMessages.unitQuery(unit))
        for (length in 0 until unit.size) assertFails { OutfitPartyMessages.unitQuery(unit.copyOf(length)) }
        assertFails { OutfitPartyMessages.unitQuery(unit+byteArrayOf(0)) }
        fun party(ships: List<OutfitPartyMessages.Ships>) = OutfitPartyMessages.party(OutfitPartyMessages.Query(1,1049,0),2,0,1,ships,emptyList(),0,0)
        assertFails { party(listOf(OutfitPartyMessages.Ships(32,600,listOf(1,1)))) }
        assertFails { party(listOf(OutfitPartyMessages.Ships(32,300,(1..71).toList()))) }
        assertFails { party(List(61) { OutfitPartyMessages.Ships(32,300,listOf(it+1)) }) }
        assertFails { party(listOf(OutfitPartyMessages.Ships(32,65536,listOf(1)))) }
    }
}
