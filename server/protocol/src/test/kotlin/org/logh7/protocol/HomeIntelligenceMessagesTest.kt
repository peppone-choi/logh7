package org.logh7.protocol

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HomeIntelligenceMessagesTest {
    @Test fun queryAndReplyUseTheSameBaseAndTicket() {
        val b=ByteBuffer.allocate(31)
        b.putShort(0,0x1200).putInt(2,10001).putShort(6,0x7f14)
            .putShort(8,1177).putInt(10,9).putShort(29,1)
        val q=HomeIntelligenceMessages.query(b.array())
        val report=HomeIntelligenceMessages.View(0xf000_0000L,5_000_000_000,73,54)
        val reply=ByteBuffer.wrap(HomeIntelligenceMessages.response(q,report))
        assertEquals(0x1200,reply.getShort(4).toInt())
        assertEquals(10001,reply.getInt(6))
        assertEquals(0x7f14,reply.getShort(10).toInt())
        assertEquals(1177,reply.getShort(12).toInt())
        assertEquals(9,reply.getInt(14))
        assertEquals(1,reply.get(18).toInt())
        assertEquals(report.observedAt,Integer.toUnsignedLong(reply.getInt(19)))
        assertEquals(report.treasury,reply.getLong(23))
        assertEquals(report.approval,reply.get(31).toInt())
        assertEquals(report.peace,reply.get(32).toInt())
        assertEquals(1,reply.getShort(33).toInt())
        val empty=HomeIntelligenceMessages.response(q,null)
        assertTrue(empty.sliceArray(18..32).all {it==0.toByte()})
        b.put(14,1)
        assertFailsWith<IllegalArgumentException> {HomeIntelligenceMessages.query(b.array())}
    }
}
