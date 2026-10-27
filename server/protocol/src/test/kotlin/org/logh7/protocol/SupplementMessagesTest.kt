package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class SupplementMessagesTest {
    private fun query()=SupplementMessages.Query(LogisticsMessages.Header(123,10000,9999,9999),1,1049,1,0,
        listOf(SupplementMessages.Units(32,listOf(1,2))),listOf(LogisticsMessages.TroopMove(1003,0,2)),100,
        LogisticsMessages.Inventory(listOf(LogisticsMessages.ShipStock(32,2,600)),listOf(LogisticsMessages.TroopStock(1003,0,5))),100,5,60000)
    @Test fun nativeOrderAndCountsRoundTripWithoutCppPadding() {
        val q=query();val bytes=SupplementMessages.encode(q)
        assertEquals(48+3+8+15,bytes.size);assertEquals(q,SupplementMessages.query(bytes))
        assertEquals("0c050000007b00002710010000270f0000270f000004190000000100",bytes.take(28).joinToString(""){"%02x".format(it)})
        val empty=q.copy(units=emptyList(),troops=emptyList(),result=LogisticsMessages.Inventory(emptyList(),emptyList()))
        assertEquals(48,SupplementMessages.encode(empty).size)
    }
    @Test fun allTruncationsTrailingBytesAndOversizedNestedListsAreRejected() {
        val bytes=SupplementMessages.encode(query())
        for(n in bytes.indices)assertFails {SupplementMessages.query(bytes.copyOf(n))}
        assertFails {SupplementMessages.query(bytes+0)}
        assertFails {SupplementMessages.query(bytes.copyOf().also {it[28]=100})}
        assertFails {SupplementMessages.query(bytes.copyOf().also {it[31]=100})}
    }
    @Test fun denialClearsForgedOutcomesBalancesAndSelection() {
        val response=SupplementMessages.response(query(),500,700,800,false)
        val b=ByteBuffer.wrap(response);assertEquals(1,b.int)
        val actual=SupplementMessages.query(response.copyOfRange(4,response.size))
        assertEquals(LogisticsMessages.Header(500,10000,700,800),actual.header)
        assertTrue(actual.units.isEmpty());assertTrue(actual.result.ships.isEmpty());assertEquals(0,actual.resultSupplies)
        assertEquals(0,actual.supplies);assertEquals(0,actual.maxCrew)
    }
}
