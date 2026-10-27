package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class CompletenessSupplyMessagesTest {
    private fun request(fromCount:Int=0,toCount:Int=0)=ByteBuffer.allocate(32+(fromCount+toCount)*8)
        .putShort(0x0c01).putInt(-1).putInt(10000).putInt(-1).putInt(-1).putInt(1).putInt(2).putInt(Int.MAX_VALUE).also {b->
            b.put(fromCount.toByte());repeat(fromCount) {b.putInt(-1).putInt(-1)}
            b.put(toCount.toByte());repeat(toCount) {b.putInt(-1).putInt(-1)}
        }.array()
    @Test fun clientOutcomeFieldsAreIgnoredWithinTheNativeBounds() {
        val expected=CompletenessSupplyMessages.Query(10000,1,2)
        assertEquals(expected,CompletenessSupplyMessages.query(request()))
        assertEquals(expected,CompletenessSupplyMessages.query(request(26,70)))
        for(bad in listOf(request(27,0),request(0,71),request().copyOf(31),request()+0,
            request().copyOf().also {ByteBuffer.wrap(it).putInt(22,1)})) assertFails {CompletenessSupplyMessages.query(bad)}
    }
    @Test fun responseWritesServerBalancesAndExactPerUnitResults() {
        val q=CompletenessSupplyMessages.Query(10000,1,2)
        val from=(1..26).map {CompletenessSupplyMessages.UnitSupply(it,100)}
        val to=(101..170).map {CompletenessSupplyMessages.UnitSupply(it,200)}
        val bytes=CompletenessSupplyMessages.response(q,42,1000,840,14000,from,to,true)
        val b=ByteBuffer.wrap(bytes)
        assertEquals(0,b.int);assertEquals(0x0c01,b.short.toInt());assertEquals(42,b.int)
        assertEquals(10000,b.int);assertEquals(1000,b.int);assertEquals(840,b.int)
        assertEquals(1,b.int);assertEquals(2,b.int);assertEquals(14000,b.int)
        assertEquals(26,b.get().toInt());repeat(26) {assertEquals(it+1,b.int);assertEquals(100,b.int)}
        assertEquals(70,b.get().toInt());repeat(70) {assertEquals(it+101,b.int);assertEquals(200,b.int)}
        assertFalse(b.hasRemaining())
        assertFails {CompletenessSupplyMessages.response(q,42,1000,840,0,from,from,true)}
        assertFails {CompletenessSupplyMessages.response(q,42,1000,840,0,from,to,false)}
        val denied=ByteBuffer.wrap(CompletenessSupplyMessages.response(q,42,1000,1000,0,emptyList(),emptyList(),false))
        assertEquals(1,denied.int);assertEquals(0,denied.getInt(26))
    }
}
