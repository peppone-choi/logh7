package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

/** 기존 봉투와 복원 확장·편집 단계의 구분. 작성: 최병호. */
class OutfitFormationMessagesTest {
    private fun request(command:Int=25,phase:Int=0,target:Int=0,unit:Int=0,amount:Int=0)=ByteBuffer.allocate(31)
        .putShort(0x1200).putInt(10000).putShort(0x7f01).putShort(command.toShort()).putShort(24).putShort(0)
        .putInt(target).put(phase.toByte()).putInt(unit).putShort(amount.toShort()).putInt(1049).putShort(37).array()
    @Test fun strictPhasesRejectInjectedUnitsKindsAndForeignSearches() {
        val begin=OutfitFormationMessages.query(request());assertEquals(1049,begin.base)
        assertEquals(0,begin.kind);assertEquals(24,begin.card)
        val set=OutfitFormationMessages.query(request(phase=1,target=4,unit=4056,amount=123))
        assertEquals(123,set.amount);assertEquals(4056,set.unit)
        OutfitFormationMessages.query(request(phase=2,target=4,unit=1000000))
        OutfitFormationMessages.query(request(26,6,400))
        for(body in listOf(request()+byteArrayOf(0),request(phase=3),request(phase=0,unit=1),
            request(phase=1,target=4,unit=999),request(phase=2,target=4,unit=1,amount=1),
            request(26,3,400),request(26,6),request().also {ByteBuffer.wrap(it).putShort(10,150)},
            request().also {ByteBuffer.wrap(it).putShort(12,4)},request().also {ByteBuffer.wrap(it).putInt(25,-1)}))
            assertFailsWith<IllegalArgumentException>{OutfitFormationMessages.query(body)}
        assertFalse(StrategyActionMessages.matches(request()));assertTrue(OutfitFormationMessages.matches(request()))
    }
    @Test fun directoryUsesBothRecoveredNativeShortsAndPreservesTokenAndEnvelope() {
        val q=OutfitFormationMessages.query(request())
        val messages=OutfitFormationMessages.response(q,0,false,9,1049,1234,3,
            listOf(OutfitFormationMessages.Row(101,"ship",17,6)))
        val begin=ByteBuffer.wrap(messages[0]);assertEquals(0x1200,begin.getShort(4).toInt())
        assertEquals(0,begin.getShort(16).toInt());assertEquals(9,begin.getInt(18));assertEquals(1,messages[0][22].toInt())
        assertEquals(1234,begin.getInt(23));assertEquals(3,begin.getShort(27).toInt());assertEquals(1049,begin.getInt(29))
        assertEquals(37,begin.getShort(33).toInt())
        val people=ByteBuffer.wrap(messages[1]);assertEquals(0x1202,people.getShort(4).toInt());assertEquals(1,people.get(6).toInt())
        assertEquals(101,people.getInt(7));val shorts=7+4+1+(4+1)*2
        assertEquals(17,people.getShort(shorts).toInt());assertEquals(6,people.getShort(shorts+2).toInt())
        assertEquals(0x1201,ByteBuffer.wrap(messages[2]).getShort(4).toInt())
        val committed=OutfitFormationMessages.response(q,0,true,99,1049,0,0).first()
        assertEquals(2,committed[22].toInt());assertEquals(99,ByteBuffer.wrap(committed).getInt(18))
        val denied=OutfitFormationMessages.response(q,16,false,9,1049,0,0).first()
        assertEquals(16,ByteBuffer.wrap(denied).getShort(16).toInt());assertEquals(3,denied[22].toInt())
        assertFailsWith<IllegalArgumentException>{OutfitFormationMessages.response(q,0,false,0,1049,0,0,
            List(201){OutfitFormationMessages.Row(it+1,"x",1)})}
    }
}
