package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

/** 회견 요청 경계와 우호도 응답 배치, 같은 스폿 목록 요청. 작성: Peppone Choi. */
class MeetingMessagesTest {
    private fun request(target:Int=10001,phase:Int=0,ticket:Int=0)=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000)
        .putShort(0x7f12).putShort(87).putShort(0).putShort(0).putInt(ticket).put(phase.toByte()).putInt(0).putShort(0).putInt(target).putShort(37).array()
    @Test fun onlyAMeetingWithAnotherPersonReachesThePolicy() {
        val q=MeetingMessages.query(request(10001,1,4));assertEquals(10001,q.target);assertEquals(4,q.ticket)
        val invalid=listOf(request(0),request(10000),request(phase=1),request(ticket=3),request().also {ByteBuffer.wrap(it).putShort(8,14)},
            request().also {ByteBuffer.wrap(it).putShort(10,59)},request().also {ByteBuffer.wrap(it).putShort(23,1)},
            request().also {ByteBuffer.wrap(it).putShort(29,0)},request().copyOf(30))
        for(body in invalid)assertFailsWith<IllegalArgumentException>{MeetingMessages.query(body)}
    }
    @Test fun friendshipAndPairWaitFitTheNativeRecord() {
        val q=MeetingMessages.query(request(10001,1,4))
        val r=MeetingMessages.response(q,0,true,MeetingMessages.View(5,10001,53,86400,3));assertEquals(3,r.size)
        val current=ByteBuffer.wrap(r[0]);assertEquals(10,current.get(22).toInt());assertEquals(5,current.getInt(18))
        assertEquals(86400,current.getInt(23));assertEquals(53,current.getShort(27).toInt());assertEquals(10001,current.getInt(29))
        val last=ByteBuffer.wrap(r[1]);assertEquals(12,last.get(22).toInt());assertEquals(10001,last.getInt(18));assertEquals(3,last.getInt(29))
        assertEquals(9,ByteBuffer.wrap(MeetingMessages.response(q,9,false,null)[1]).getShort(16).toInt())
    }
    @Test fun theSameSpotDirectoryIsAPreviewOnlyPersonalCardRequest() {
        fun directory(preview:Int,card:Int=0,cursor:Int=0)=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(0x7f00).putShort(87)
            .putShort(card.toShort()).putShort(0).putInt(cursor).put(preview.toByte()).putInt(0).putShort(0).putInt(0).putShort(9).array()
        val q=StrategyActionMessages.query(directory(1,cursor=10003));assertTrue(q.preview);assertEquals(10003,q.target)
        assertTrue(StrategyActionMessages.selections(q,listOf(StrategyActionMessages.Selection(10004,"Kim",8))).isNotEmpty())
        assertFailsWith<IllegalArgumentException>{StrategyActionMessages.query(directory(0))}
        assertFailsWith<IllegalArgumentException>{StrategyActionMessages.query(directory(1,59))}
    }
}
