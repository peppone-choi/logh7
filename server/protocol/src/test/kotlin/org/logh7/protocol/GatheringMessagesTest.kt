package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

/** 7f13 회담·담화 확장: 요청 검증, 두 결과 레코드, 초대 통지. 작성: Peppone Choi. */
class GatheringMessagesTest {
    private fun body(command:Int=13,value:Int=0,phase:Int=1,ticket:Int=5,target:Int=10001)=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000)
        .putShort(GatheringMessages.SEARCH.toShort()).putShort(command.toShort()).putShort(0).putShort(value.toShort()).putInt(ticket)
        .put(phase.toByte()).putInt(0).putShort(0).putInt(target).putShort(1).array()
    @Test fun requestsAreBoundedAndResultsCarryGradeAndBiasedDeltas() {
        val q=GatheringMessages.query(body());assertEquals(13,q.command);assertEquals(0,q.value);assertEquals(10001,q.target)
        for(bad in listOf(body(command=14),body(value=3),body(phase=0,ticket=5),body(target=10000)))
            assertFailsWith<IllegalArgumentException> {GatheringMessages.query(bad)}
        val out=GatheringMessages.response(q,0,true,GatheringMessages.View(6,10001,55,86400,0,0,2,1,1,5))
        assertEquals(3,out.size)
        val second=ByteBuffer.wrap(out[1]);assertEquals(12,second.get(6+16).toInt());assertEquals(2,second.getShort(6+21).toInt())
        assertEquals(GatheringMessages.packDeltas(1,1,5),second.getInt(6+23));assertEquals((129 shl 16) or (129 shl 8) or 133,GatheringMessages.packDeltas(1,1,5))
        val notice=ByteBuffer.wrap(GatheringMessages.invited(10001,13,10000,1440))
        assertEquals(0x1200,notice.getShort(4).toInt());assertEquals(10001,notice.getInt(6));assertEquals(13,notice.get(6+16).toInt())
        assertEquals(1440,notice.getInt(6+17));assertEquals(10000,notice.getInt(6+23))
    }
}
