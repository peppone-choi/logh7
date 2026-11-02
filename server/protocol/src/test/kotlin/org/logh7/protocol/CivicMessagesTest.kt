package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

/** 새 opcode 없이 엄격한 복원 명령·단계·상태 봉투. 작성: Peppone Choi. */
class CivicMessagesTest {
    private fun request(command:Int=52,mode:Int=0,outfit:Int=200)=ByteBuffer.allocate(31)
        .putShort(0x1200).putInt(10000).putShort(0x7f00).putShort(command.toShort()).putShort(59).putShort(mode.toShort())
        .putInt(outfit).put(0).putInt(0).putShort(0).putInt(0).putShort(37).array()
    @Test fun strictShapeSeparatesGuardFromArmyAndRejectsClientCostTimeAndAuthorityFields() {
        for(command in listOf(52,54,56)) {
            val q=StrategyActionMessages.query(request(command));assertEquals(200,q.outfit);assertEquals(0,q.value)
            assertEquals(2,StrategyActionMessages.query(request(command,2,0)).value)
            assertEquals(1,StrategyActionMessages.query(request(command,1)).value)
        }
        assertEquals(0,StrategyActionMessages.query(request(55,outfit=0)).outfit)
        for(b in listOf(request(mode=3),request(outfit=0),request(55),request(55,outfit=0).also {ByteBuffer.wrap(it).putShort(10,39)},
            request().also {it[18]=1},request().also {ByteBuffer.wrap(it).putInt(19,86400)},
            request().also {ByteBuffer.wrap(it).putShort(23,160)},request().also {ByteBuffer.wrap(it).putInt(25,1049)},
            request().also {ByteBuffer.wrap(it).putShort(10,24)}))
            assertFailsWith<IllegalArgumentException>{StrategyActionMessages.query(b)}
    }
    @Test fun nativeResponseCarriesDurablePhaseDeadlineEffectAndTokenWithoutChangingOtherCommands() {
        val q=StrategyActionMessages.query(request())
        for(phase in 0..5) {
            val b=ByteBuffer.wrap(StrategyActionMessages.response(q,0,false,
                StrategyActionMessages.CivicStatus(phase,200,1049,0xf0000000L,1000)).first())
            assertEquals(0,b.getShort(16).toInt());assertEquals(10+phase,b.get(22).toInt())
            assertEquals(200,b.getInt(18));assertEquals(0xf0000000L,b.getInt(23).toLong() and 0xffffffffL)
            assertEquals(1000,b.getShort(27).toInt());assertEquals(1049,b.getInt(29));assertEquals(37,b.getShort(33).toInt())
        }
        val none=StrategyActionMessages.response(q,0,false,StrategyActionMessages.CivicStatus(-1,0,0,0,0)).first()
        assertEquals(3,none[22].toInt())
        val failure=StrategyActionMessages.response(q,11,false,StrategyActionMessages.CivicStatus(0,200,1049,100,0)).first()
        assertEquals(11,ByteBuffer.wrap(failure).getShort(16).toInt());assertEquals(3,failure[22].toInt())
        assertFailsWith<IllegalArgumentException>{StrategyActionMessages.response(q,0,false,StrategyActionMessages.CivicStatus(6,0,0,0,0))}
    }
}
