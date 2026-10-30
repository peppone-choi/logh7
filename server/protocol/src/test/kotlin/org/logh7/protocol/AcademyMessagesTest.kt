package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

/** 사관학교 요청 경계(명령·카드·능력 인자)와 강의·경험치 응답 배치. 작성: Peppone Choi. */
class AcademyMessagesTest {
    private fun request(command:Int=93,card:Int=0,argument:Int=6,phase:Int=0,ticket:Int=0)=ByteBuffer.allocate(31).putShort(0x1200)
        .putInt(10000).putShort(0x7f11).putShort(command.toShort()).putShort(card.toShort()).putShort(0).putInt(ticket)
        .put(phase.toByte()).putInt(0).putShort(argument.toShort()).putInt(0).putShort(37).array()
    @Test fun onlyAcademyCommandsWithAnAbilityOrContentArgumentReachThePolicy() {
        val q=AcademyMessages.query(request(27,38,3,1,9));assertEquals(27,q.command);assertEquals(38,q.card);assertEquals(3,q.argument)
        assertEquals(9,q.ticket);assertFalse(AcademyMessages.matches(request().also {ByteBuffer.wrap(it).putShort(6,0x7f10)}))
        val invalid=listOf(request(14),request(argument=8),request(phase=1),request(ticket=9),request(card=261),
            request().also {ByteBuffer.wrap(it).putShort(12,1)},request().also {ByteBuffer.wrap(it).putInt(19,1)},
            request().also {ByteBuffer.wrap(it).putInt(25,1049)},request().also {ByteBuffer.wrap(it).putShort(29,0)},request().copyOf(30))
        for(body in invalid)assertFailsWith<IllegalArgumentException>{AcademyMessages.query(body)}
    }
    @Test fun lectureUsesAndExperienceFitTheNativeRecord() {
        val q=AcademyMessages.query(request(94,0,5,1,4))
        val result=AcademyMessages.response(q,0,true,AcademyMessages.View(5,1049,1,7000,3,5,51,23,0,5));assertEquals(3,result.size)
        val current=ByteBuffer.wrap(result[0]);assertEquals(10,current.get(22).toInt());assertEquals(5,current.getInt(18))
        assertEquals(7000,current.getInt(23));assertEquals(2*256+3,current.getShort(27).toInt());assertEquals(1049,current.getInt(29))
        assertEquals(37,current.getShort(33).toInt())
        val last=ByteBuffer.wrap(result[1]);assertEquals(12,last.get(22).toInt());assertEquals(5,last.getInt(18))
        assertEquals(51*128+23,last.getShort(27).toInt());assertEquals(5,last.getInt(29))
        assertEquals(11,AcademyMessages.response(q,0,false,null)[1][22].toInt())
        assertEquals(9,ByteBuffer.wrap(AcademyMessages.response(q,9,false,null)[1]).getShort(16).toInt())
    }
}
