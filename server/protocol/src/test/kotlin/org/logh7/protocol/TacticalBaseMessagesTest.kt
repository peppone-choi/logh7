package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalBaseMessagesTest {
    @Test fun moraleAndConfusionUseTheirIndependentPackedReaders() {
        val confused=TacticalStatusMessages.confusion(0x01020304,11,true)
        assertEquals("00000000043d010203040000000b",confused.joinToString(""){"%02x".format(it)})
        assertEquals(0x43e,ByteBuffer.wrap(TacticalStatusMessages.confusion(100,11,false)).getShort(4).toInt())
        val morale=TacticalStatusMessages.morale(0x01020304,11,15)
        assertEquals("000000000440010203040000000b0f",morale.joinToString(""){"%02x".format(it)})
        assertFailsWith<IllegalArgumentException>{TacticalStatusMessages.morale(100,11,101)}
    }
    @Test fun fortressKillListUsesTheNativePackedCountAndHasNoCasualtyCounters() {
        val packet=TacticalBaseMessages.destroyed(0x01020304,1000,listOf(11,13))
        assertEquals("00000000043601020304000003e8020000000b0000000d",packet.joinToString(""){"%02x".format(it)})
        assertFailsWith<IllegalArgumentException>{TacticalBaseMessages.destroyed(1,1000,emptyList())}
        assertFailsWith<IllegalArgumentException>{TacticalBaseMessages.destroyed(1,1000,listOf(1,1))}
        assertFailsWith<IllegalArgumentException>{TacticalBaseMessages.destroyed(1,1000,(1..33).toList())}
    }
    @Test fun chargedCompletionStartsTheNativeBeamWithoutAnotherWait() {
        val packet=TacticalBaseMessages.fired(100,10000,1000,.75f)
        assertEquals(0,ByteBuffer.wrap(packet).getInt(10))
        val parsed=TacticalCommandMessages.command(packet.drop(4).toByteArray())
        assertEquals(0x419,parsed.opcode);assertEquals(1000,parsed.base);assertEquals(.75f,parsed.direction)
    }
    @Test fun emergencyContainsBaseTargetAndBalancesWithoutATimeWord() {
        val opened=ByteBuffer.wrap(TacticalBaseMessages.emergency(1000,0,321,0))
        assertEquals(22,opened.capacity());assertEquals(0x438,opened.getShort(4).toInt())
        assertEquals(1000,opened.getInt(6));assertEquals(0,opened.getInt(10))
        assertEquals(321,opened.getInt(14));assertEquals(0,opened.getInt(18))
        val transferred=ByteBuffer.wrap(TacticalBaseMessages.emergency(1000,11,200,121))
        assertEquals(11,transferred.getInt(10));assertEquals(121,transferred.getInt(18))
        assertFailsWith<IllegalArgumentException>{TacticalBaseMessages.emergency(1000,0,321,12)}
        assertFailsWith<IllegalArgumentException>{TacticalBaseMessages.emergency(1000,11,-1,12)}
    }
    @Test fun partialBaseHitUsesAnExplicitSourceKindAndKeepsAbsoluteNativeLosses() {
        val packet=ByteBuffer.wrap(TacticalAttackMessages.hit(100,1000,0,11,5,10,sourceBase=true,morale=35))
        assertEquals(0x81,packet.get(15).toInt() and 255)
        assertEquals(10,packet.getShort(20).toInt());assertEquals(5,packet.getShort(22).toInt());assertEquals(35,packet.get(27).toInt())
        assertFailsWith<IllegalArgumentException>{TacticalAttackMessages.hit(100,1000,1,11,5,10,sourceBase=true)}
    }
}
