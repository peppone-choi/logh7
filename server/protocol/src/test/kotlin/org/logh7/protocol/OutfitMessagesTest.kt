package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class OutfitMessagesTest {
    @Test fun detailHasOneByteCountAndTwentyFourByteUnpaddedRecords() {
        val data = OutfitMessages.information(listOf(OutfitMessages.Definition(264,3,3,1,60,65535,123,(0..9).toList())))
        assertEquals(25,data.size)
        val input = ByteBuffer.wrap(data)
        assertEquals(1,input.get().toInt()); assertEquals(264,input.int)
        assertEquals(listOf(3,3,1,60),List(4) { input.get().toInt() })
        assertEquals(65535,input.short.toInt() and 65535); assertEquals(123,input.int)
        assertEquals((0..9).toList(),List(10) { input.get().toInt() })
        assertFalse(input.hasRemaining())
    }
    @Test fun gridHasTwoByteCountAndTwelveByteRecordsWithCarriedSupplies() {
        val data = OutfitMessages.grid(listOf(OutfitMessages.Entry(1,0,2,0,1,100000),OutfitMessages.Entry(2,2,2,0,48,10000)))
        assertEquals(26,data.size)
        val input = ByteBuffer.wrap(data)
        assertEquals(2,input.short.toInt()); assertEquals(1,input.int)
        assertEquals(listOf(0,2,0,1),List(4) { input.get().toInt() }); assertEquals(100000,input.int)
        assertEquals(2,input.int); assertEquals(listOf(2,2,0,48),List(4) { input.get().toInt() }); assertEquals(10000,input.int)
    }
    @Test fun requestsValidateLengthsCountsAndFilterBounds() {
        val ids = ByteBuffer.allocate(11).putShort(0x032a).put(2).putInt(1).putInt(264).array()
        assertEquals(listOf(1,264),OutfitMessages.query(ids))
        val query = ByteBuffer.allocate(8).putShort(0x032c).putInt(-1).put(255.toByte()).put(255.toByte()).array()
        assertEquals(OutfitMessages.GridQuery(-1,255,255),OutfitMessages.gridQuery(query))
        for (n in ids.indices) assertFails { OutfitMessages.query(ids.copyOf(n)) }
        assertFails { OutfitMessages.query(ids + 0) }
        assertFails { OutfitMessages.query(ids.copyOf().also { it[2] = 101 }) }
        assertFails { OutfitMessages.gridQuery(query.copyOf().also { it[6] = 5 }) }
        assertFails { OutfitMessages.gridQuery(query.copyOf().also { it[7] = 2 }) }
    }
    @Test fun nativeLimitsAndInvalidStatisticsNeverProducePartialResponses() {
        assertFails { OutfitMessages.information(List(101) { OutfitMessages.Definition(it+1,0,2,0,1) }) }
        assertFails { OutfitMessages.grid(List(301) { OutfitMessages.Entry(it+1,0,2,0,1,0) }) }
        assertFails { OutfitMessages.information(listOf(OutfitMessages.Definition(1,0,2,0,1,practice=listOf(1)))) }
        assertFails { OutfitMessages.information(List(2) { OutfitMessages.Definition(1,0,2,0,1) }) }
        assertFails { OutfitMessages.grid(listOf(OutfitMessages.Entry(1,0,2,0,1,-1))) }
    }
}
