package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class TaxMessagesTest {
    private fun request() = ByteBuffer.allocate(27).putShort(0x0a00).putInt(-1).putInt(10000).put(0)
        .putInt(Int.MAX_VALUE).putInt(-1).putInt(1049).put(35).putShort(54).put(0).array()
    @Test fun nativeRecordWidthsAndUntrustedHeaderFieldsAreIndependentOfAuthority() {
        assertEquals(TaxMessages.Query(10000,1049,35,54,0), TaxMessages.query(request()))
        val response = TaxMessages.response(TaxMessages.query(request()),123,680,1000,true)
        val b = ByteBuffer.wrap(response)
        assertEquals(31,response.size); assertEquals(0,b.int); assertEquals(0x0a00,b.short.toInt())
        assertEquals(123,b.int); assertEquals(10000,b.int); assertEquals(0,b.get().toInt())
        assertEquals(680,b.int); assertEquals(1000,b.int); assertEquals(1049,b.int)
        assertEquals(35,b.get().toInt()); assertEquals(54,b.short.toInt()); assertEquals(0,b.get().toInt())
        assertFalse(b.hasRemaining())
        val rejected = TaxMessages.response(TaxMessages.query(request()),124,1000,1000,false)
        assertEquals(1,ByteBuffer.wrap(rejected).int); assertEquals(1,rejected.last().toInt())
    }
    @Test fun malformedLengthsIdsAndRatesNeverReachTheActor() {
        val valid = request()
        for (size in 0 until valid.size) assertFails { TaxMessages.query(valid.copyOf(size)) }
        assertFails { TaxMessages.query(valid + byteArrayOf(0)) }
        assertFails { TaxMessages.query(valid.copyOf().also { it[23] = 101 }) }
        assertFails { TaxMessages.query(valid.copyOf().also { ByteBuffer.wrap(it).putInt(6,0) }) }
        assertFails { TaxMessages.query(valid.copyOf().also { ByteBuffer.wrap(it).putInt(19,0) }) }
    }
}
