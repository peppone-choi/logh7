package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalRetreatMessagesTest {
    @Test fun commandUsesOnlyCountedUnitsAndNotificationUsesGridBaseAndModeWithoutPadding() {
        val command = TacticalRetreatMessages.Retreat(10000,listOf(1010000,1010001))
        val reply = TacticalRetreatMessages.reply(command,100,120)
        assertEquals(27,reply.size)
        assertEquals(command,TacticalRetreatMessages.retreat(reply.copyOfRange(4,reply.size)))
        assertEquals(120,ByteBuffer.wrap(reply).getInt(10))
        assertEquals(1,ByteBuffer.wrap(TacticalRetreatMessages.reply(command,100,0,false)).int)
        val packet = TacticalRetreatMessages.warped(3820,2016,command.units)
        val input=ByteBuffer.wrap(packet)
        assertEquals(29,packet.size);assertEquals(0x425,input.getShort(4).toInt())
        assertEquals(2016,input.getInt(10));assertEquals(0,input.getInt(14));assertEquals(5,input.getShort(18).toInt())
        assertEquals(2,input.get(20).toInt());assertEquals(1010000,input.getInt(21))
        assertFailsWith<IllegalArgumentException>{TacticalRetreatMessages.retreat(reply.copyOfRange(4,reply.size-1))}
        assertFailsWith<IllegalArgumentException>{TacticalRetreatMessages.retreat(reply.copyOfRange(4,reply.size)+byteArrayOf(0))}
        assertFailsWith<IllegalArgumentException>{TacticalRetreatMessages.Retreat(1,listOf(2,2))}
    }
}
