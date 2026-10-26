package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class AppointmentMessagesTest {
    @Test fun onlyCapitalAppointmentsCanOmitTheBaseChosenByTheOriginalClient() {
        for(card in listOf(56,57,192,193)) {
            val value=request().also {ByteBuffer.wrap(it).putInt(22,card).putInt(36,0)}
            assertEquals(0,AppointmentMessages.query(value).base)
        }
        assertFails {AppointmentMessages.query(request().also {ByteBuffer.wrap(it).putInt(36,0)})}
    }
    private fun request()=ByteBuffer.allocate(40).putShort(0x0707).putInt(-1).putInt(10000)
        .putInt(Int.MAX_VALUE).putInt(Int.MAX_VALUE).putInt(10001).putInt(55)
        .putShort(59).putInt(999).putInt(0).putInt(1049).array()
    @Test fun responseUsesNativeUnpaddedRecordAndSuppressesCardAppendOnFailureOrRetry() {
        val query=AppointmentMessages.query(request())
        assertEquals(AppointmentMessages.Query(10000,10001,55,0,1049),query)
        for((accepted,changed) in listOf(true to true,true to false,false to false)) {
            val b=ByteBuffer.wrap(AppointmentMessages.response(query,123,1000,840,accepted,changed))
            assertEquals(44,b.capacity());assertEquals(if(accepted)0 else 1,b.int);assertEquals(0x0707,b.short.toInt())
            assertEquals(123,b.int);assertEquals(10000,b.int);assertEquals(1000,b.int);assertEquals(840,b.int)
            assertEquals(if(changed)10001 else 0,b.int);assertEquals(55,b.int)
            assertEquals(if(changed)55 else 0,b.short.toInt());assertEquals(if(changed)1049 else 0,b.int)
            assertEquals(0,b.int);assertEquals(1049,b.int);assertFalse(b.hasRemaining())
        }
    }
    @Test fun malformedRequestsDoNotEnterTheWorldActor() {
        val valid=request()
        for(size in 0 until valid.size)assertFails { AppointmentMessages.query(valid.copyOf(size)) }
        assertFails { AppointmentMessages.query(valid+byteArrayOf(0)) }
        for((offset,value) in listOf(6 to 0,18 to 0,22 to 261,32 to 1,36 to 0))
            assertFails { AppointmentMessages.query(valid.copyOf().also { ByteBuffer.wrap(it).putInt(offset,value) }) }
    }
}
