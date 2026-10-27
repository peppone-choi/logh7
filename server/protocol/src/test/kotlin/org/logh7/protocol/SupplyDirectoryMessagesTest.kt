package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class SupplyDirectoryMessagesTest {
    private fun request()=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(14).putShort(124)
        .also {it.putInt(19,2015)}.array()
    @Test fun nativeQueryValidatesGridAndReservedBytes() {
        val q=SupplyDirectoryMessages.query(request())
        assertEquals(10000,q.character);assertEquals(2015,q.grid)
        for(bad in listOf(request().copyOf(30),request().also {it[10]=1},
            request().also {ByteBuffer.wrap(it).putInt(19,5000)},request().also {it[9]=0}))
            assertFails {SupplyDirectoryMessages.query(bad)}
    }
    @Test fun nestedOutfitRecordsHaveU16CountAndExactCargoAndFreeRoom() {
        val q=SupplyDirectoryMessages.query(request())
        val base=WorldMessages.Base(1177,2015,0,0,"Home",0,20f,86400,0,0f,2f)
        val messages=SupplyDirectoryMessages.response(q,listOf(SupplyDirectoryMessages.Outfit(133,0,1,100,1000,base)))
        assertEquals(listOf(0x1200,0x120b,0x1201),messages.map {ByteBuffer.wrap(it).getShort(4).toInt()})
        val b=ByteBuffer.wrap(messages[1]);b.position(6)
        assertEquals(1,b.short.toInt());assertEquals(100,b.int);assertEquals(900,b.int)
        assertEquals(133,b.int);assertEquals(1,b.get().toInt());assertEquals(0,b.get().toInt())
        assertEquals(1,b.get().toInt());assertEquals(1177,b.int);assertEquals(2015,b.short.toInt())
        assertFails {SupplyDirectoryMessages.response(q,listOf(SupplyDirectoryMessages.Outfit(133,0,1,1001,1000,base)))}
    }
}
