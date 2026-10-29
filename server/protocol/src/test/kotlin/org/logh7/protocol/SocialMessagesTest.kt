package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

/** 연설 전용 요청 경계와 음수 결과/영속 번호의 응답. 작성: 최병호. */
class SocialMessagesTest {
    private fun request(phase:Int=0,ticket:Int=0)=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000)
        .putShort(0x7f10).putShort(14).putShort(0).putShort(0).putInt(ticket).put(phase.toByte()).putInt(0).putShort(0).putInt(0).putShort(37).array()
    @Test fun onlySpeechAndAnAuthenticatedPersonalCardShapeCanReachThePolicy() {
        assertEquals(0,SocialMessages.query(request()).phase);assertEquals(9,SocialMessages.query(request(1,9)).ticket)
        val invalid=listOf(request(2,9),request(1),request(ticket=9),request().also {ByteBuffer.wrap(it).putShort(8,10)},
            request().also {ByteBuffer.wrap(it).putShort(10,59)},request().also {ByteBuffer.wrap(it).putShort(12,1)},
            request().also {ByteBuffer.wrap(it).putInt(19,100)},request().also {ByteBuffer.wrap(it).putShort(23,320)},
            request().also {ByteBuffer.wrap(it).putInt(25,1049)},request().also {ByteBuffer.wrap(it).putShort(29,0)},request().copyOf(30))
        for(body in invalid)assertFailsWith<IllegalArgumentException>{SocialMessages.query(body)}
    }
    @Test fun negativeOutcomeCooldownAndResultIdentitySurviveTheExistingNativeRecordLayout() {
        val q=SocialMessages.query(request(1,8));val v=SocialMessages.View(9,1049,9,68,86400,8,0xf0000000L,1049,-1,-2)
        val result=SocialMessages.response(q,0,true,v);assertEquals(3,result.size)
        val current=ByteBuffer.wrap(result[0]);assertEquals(9,current.getInt(18));assertEquals(86400,current.getInt(23))
        assertEquals(9*128+68,current.getShort(27).toInt());assertEquals(37,current.getShort(33).toInt())
        val last=ByteBuffer.wrap(result[1]);assertEquals(12,last.get(22).toInt());assertEquals(8,last.getInt(18))
        assertEquals(0xf0000000L,last.getInt(23).toLong() and 0xffffffffL)
        assertEquals(127*256+126,last.getShort(27).toInt() and 65535);assertEquals(1049,last.getInt(29))
        assertEquals(11,SocialMessages.response(q,0,false,v)[1][22].toInt())
        assertEquals(8,ByteBuffer.wrap(SocialMessages.response(q,8,false,null)[1]).getShort(16).toInt())
    }
}
