package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class DismissalMessagesTest {
    @Test fun nativeFieldsRejectRelocationAndNeverEchoForgedBalances() {
        val bytes=ByteBuffer.allocate(31).putShort(0x0708).putInt(-1).putInt(10000).putInt(-1).putInt(-1)
            .putInt(10001).putInt(175).putInt(0).put(0).array()
        val q=DismissalMessages.query(bytes);assertEquals(DismissalMessages.Query(10000,10001,175),q)
        val ok=ByteBuffer.wrap(DismissalMessages.response(q,20,400,840,true))
        assertEquals(0,ok.int);assertEquals(0x0708,ok.short.toInt());assertEquals(20,ok.int)
        assertEquals(10000,ok.int);assertEquals(400,ok.int);assertEquals(840,ok.int);assertEquals(10001,ok.int)
        assertEquals(175,ok.int);assertEquals(0,ok.int);assertEquals(0,ok.get().toInt());assertFalse(ok.hasRemaining())
        val denied=ByteBuffer.wrap(DismissalMessages.response(q,20,400,840,false))
        assertEquals(1,denied.int);assertEquals(0,denied.getInt(22))
        for(bad in listOf(bytes.copyOf(30),bytes+0,bytes.copyOf().also {it[30]=1},bytes.copyOf().also {it[29]=1}))
            assertFails {DismissalMessages.query(bad)}
    }
}
