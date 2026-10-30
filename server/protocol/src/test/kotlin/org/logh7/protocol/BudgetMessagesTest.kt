package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

/** 금고는 잘리지 않는 64비트이며 요청 CP/금액/시각을 신뢰하지 않는다. 작성: 최병호. */
class BudgetMessagesTest {
    private fun request(phase:Int=0,base:Int=0,rate:Int=0,quote:Int=0)=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000)
        .putShort(0x7f02).putShort(18).putShort(8).putShort(rate.toShort()).putInt(quote).put(phase.toByte())
        .putInt(0).putShort(0).putInt(base).putShort(37).array()
    @Test fun strictShapeSeparatesInquiryQuoteCommitAndCancellationAndRejectsForgedMoney() {
        assertEquals(0,BudgetMessages.query(request()).phase)
        assertEquals(33,BudgetMessages.query(request(1,1049,33)).rate)
        assertEquals(7,BudgetMessages.query(request(2,1049,33,7)).quote)
        assertEquals(3,BudgetMessages.query(request(3,1049,33,7)).phase)
        val invalid=listOf(request(4),request(rate=1),request(1,0,33),request(2,1049,33),request(1,1049,101),
            request(1,1049,33,7),request().also {ByteBuffer.wrap(it).putInt(19,1)},request().also {ByteBuffer.wrap(it).putShort(23,320)},
            request().also {ByteBuffer.wrap(it).putShort(10,59)},request().also {ByteBuffer.wrap(it).putShort(8,17)},
            request().also {ByteBuffer.wrap(it).putShort(29,0)},request().copyOf(32))
        for(b in invalid)assertFailsWith<IllegalArgumentException>{BudgetMessages.query(b)}
    }
    @Test fun moneySnapshotAndQuoteUseTheNativeEnvelopeWithoutTruncatingLongValues() {
        val q=BudgetMessages.query(request());val v=BudgetMessages.View(1049,Long.MAX_VALUE,0xf0000001L,100,37,Long.MAX_VALUE,2,100)
        val messages=BudgetMessages.response(q,0,v);assertEquals(4,messages.size)
        for((index,value) in listOf(v.national,v.local,v.amount).withIndex()) {
            val b=ByteBuffer.wrap(messages[index]);assertEquals(0x1200,b.getShort(4).toInt())
            assertEquals(value,(b.getInt(18).toLong() shl 32) or (b.getInt(23).toLong() and 0xffffffffL))
            assertEquals(10+index,b.get(22).toInt());assertEquals(37,b.getShort(33).toInt())
        }
        assertEquals(402,ByteBuffer.wrap(messages[2]).getShort(27).toInt())
        assertEquals(37,ByteBuffer.wrap(messages[2]).getInt(29))
        val error=BudgetMessages.response(q,5,null)
        assertTrue(error.take(3).all {ByteBuffer.wrap(it).getShort(16).toInt()==5})
        val update=BudgetMessages.update(10000,8,v)
        assertEquals(listOf(14,15),update.map {it[22].toInt()});assertTrue(update.all {ByteBuffer.wrap(it).getShort(33).toInt()==0})
    }
}
