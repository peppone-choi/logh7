package org.logh7.protocol

import java.nio.ByteBuffer
import kotlin.test.*
import org.junit.jupiter.api.Test

class OutfitPartyMessagesTest {
    @Test fun capacityIsEncodedAsCurrentPlusRemainingAndRejectsOverloadedInventory() {
        fun encode(current: Int,maximum: Int)=OutfitPartyMessages.party(OutfitPartyMessages.Query(1,1049,0),
            2,0,1,emptyList(),emptyList(),current,maximum)
        for (current in listOf(0,100,1000)) {
            val bytes=encode(current,1000)
            val input=ByteBuffer.wrap(bytes);input.position(22)
            assertEquals(current,input.int);assertEquals(1000-current,input.int)
        }
        assertFails { encode(1001,1000) };assertFails { encode(-1,1000) }
    }
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
        assertEquals(123,input.int); assertEquals(333,input.int); assertEquals(0,input.short.toInt())
        repeat(7) { assertEquals(0,input.get().toInt()) }
        assertFalse(input.hasRemaining())
    }
    @Test fun memberRecordsUseNativeUtf16NamesAndKeepShipSectionAligned() {
        val member = OutfitPartyMessages.Member(10000,0,7,"함대司令")
        val bytes = OutfitPartyMessages.party(OutfitPartyMessages.Query(1,1049,0),2,0,1,
            listOf(OutfitPartyMessages.Ships(32,300,listOf(9))),emptyList(),123,456,listOf(member))
        val input = ByteBuffer.wrap(bytes); input.position(19)
        assertEquals(1,input.get().toInt()); assertEquals(10000,input.int)
        assertEquals(0,input.get().toInt()); assertEquals(7,input.get().toInt())
        assertEquals(member.name.length+1,input.get().toInt())
        assertEquals(member.name+'\u0000',String(CharArray(member.name.length+1) { input.char }))
        assertEquals(1,input.get().toInt()); assertEquals(32,input.short.toInt())
        assertEquals(1,input.get().toInt()); assertEquals(300,input.short.toInt())
        assertEquals(1,input.get().toInt()); assertEquals(9,input.int)
        fun encode(members: List<OutfitPartyMessages.Member>) = OutfitPartyMessages.party(
            OutfitPartyMessages.Query(1,1049,0),2,0,1,emptyList(),emptyList(),0,0,members)
        assertFails { encode(listOf(member,member)) }
        assertFails { encode((1..11).map { member.copy(id=it) }) }
        assertFails { encode(listOf(member.copy(name="a".repeat(13)))) }
        assertFails { encode(listOf(member.copy(name="a\u0000b"))) }
        assertFails { encode(listOf(member.copy(name="\ud83d\ude00"))) }
        assertFails { encode(listOf(member.copy(id=0))) }
        assertFails { encode(listOf(member.copy(rank=256))) }
        encode(listOf(member.copy(name="a".repeat(12))))
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
        val damaged=ByteBuffer.wrap(OutfitPartyMessages.units(listOf(
            OutfitPartyMessages.UnitInformation(101,32,2487,1,0,400,80,50))))
        assertEquals(80,damaged.getShort(27).toInt());assertEquals(50,damaged.getShort(29).toInt())
        assertFails {OutfitPartyMessages.units(listOf(OutfitPartyMessages.UnitInformation(101,32,2487,1,0,damaged=30,destroyed=50)))}
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
