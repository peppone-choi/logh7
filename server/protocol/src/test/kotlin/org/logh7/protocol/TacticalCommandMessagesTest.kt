package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalCommandMessagesTest {
    private fun packet(op:Int,tail:ByteArray)=ByteBuffer.allocate(14+tail.size).putShort(op.toShort()).putLong(-1).putInt(10000).put(tail).array()
    private fun ids(vararg values:Int)=ByteBuffer.allocate(1+4*values.size).put(values.size.toByte()).apply {values.forEach(::putInt)}.array()
    private fun int(value:Int)=ByteBuffer.allocate(4).putInt(value).array()
    @Test fun cannonWireDegreesBecomeEngineRadiansWhileReplyKeepsNativeDegrees() {
        for(degrees in listOf(0f,90f,-90f,180f,450f)) {
            val c=TacticalCommandMessages.command(packet(0x419,
                ByteBuffer.allocate(8).putInt(1000).putFloat(degrees).array()))
            assertEquals(Math.toRadians((degrees%360f).toDouble()).toFloat(),c.direction,0.000001f)
            val reply=TacticalCommandMessages.reply(c,123,5)
            assertEquals(degrees,ByteBuffer.wrap(reply).getFloat(reply.size-4))
        }
    }
    @Test fun everyNewNativeCommandFamilyHasAnIndependentPackedFixture() {
        val fixtures=mapOf(
            0x407 to (int(1010000)+ByteArray(12)+int(1010001)),
            0x408 to (int(1010000)+byteArrayOf(1,0)+int(1000)),
            0x409 to int(1010000),0x40b to (int(1010000)+ids(3,4)),
            0x40d to ByteBuffer.allocate(42).put(2).putInt(3).putFloat(.2f).putFloat(1f).putFloat(2f).putFloat(0f)
                .putInt(4).putFloat(.4f).putFloat(3f).putFloat(4f).putFloat(0f).put(6).array(),
            0x40e to (ids(3)+byteArrayOf(1)+int(99)),0x419 to (int(1000)+int(0)),
            0x41b to (int(1000)+ids(3,4)),0x41c to (int(1000)+ids(3,4)),
            0x41d to int(1000),0x41e to int(1000),
            0x41f to ByteBuffer.allocate(33).putInt(1000).putFloat(.5f).putFloat(0f).putFloat(0f).putFloat(0f)
                .put(1).putFloat(1f).putFloat(2f).putFloat(0f).array(),
            0x420 to (ids(3,4)+int(1010000)),0x421 to (ids(1010000)+byteArrayOf(4,1)+int(99)),
            0x422 to (int(3)+int(1000)))
        assertEquals(TacticalCommandMessages.opcodes,fixtures.keys)
        for((op,tail) in fixtures) {
            val bytes=packet(op,tail);val c=TacticalCommandMessages.command(bytes)
            assertEquals(op,c.opcode);assertEquals(10000,c.character)
            val reply=TacticalCommandMessages.reply(c,123,5)
            assertEquals(0,ByteBuffer.wrap(reply).int);assertEquals(123,ByteBuffer.wrap(reply).getInt(6))
            assertEquals(5,ByteBuffer.wrap(reply).getInt(10))
            assertContentEquals(bytes.copyOfRange(10,bytes.size),reply.copyOfRange(14,reply.size))
            assertEquals(1,ByteBuffer.wrap(TacticalCommandMessages.reply(c,123,0,false)).int)
            if(op!=0x407) {
                assertFails {TacticalCommandMessages.command(bytes.copyOf(bytes.size-1))}
                assertFails {TacticalCommandMessages.command(bytes+0.toByte())}
            }
        }
    }
    @Test fun malformedSelectionNeverBecomesACommand() {
        for(tail in listOf(ids(3,3)+int(1010000),ids(0)+int(1010000),byteArrayOf(33)+ByteArray(136)))
            assertFails {TacticalCommandMessages.command(packet(0x420,tail))}
        assertFails {TacticalCommandMessages.command(packet(0x419,int(1000)+int(0x7fc00000)))}
        assertFails {TacticalCommandMessages.command(packet(0x41f,ByteArray(33)))}
    }
    @Test fun completionNotificationsUseTheirOwnNativeLayouts() {
        val fought=ByteBuffer.wrap(TacticalCommandMessages.fought(50,1010000,1010001))
        assertEquals(21,fought.remaining());assertEquals(0x427,fought.getShort(4).toInt());assertEquals(1010001,fought.getInt(14))
        val air=ByteBuffer.wrap(TacticalCommandMessages.airBattle(50,1,99,7,3,morale=80))
        assertEquals(28,air.remaining());assertEquals(0x428,air.getShort(4).toInt());assertEquals(7,air.getShort(20).toInt())
        assertEquals(3,air.getShort(22).toInt());assertEquals(80,air.get(27).toInt())
        val moved=ByteBuffer.wrap(TacticalCommandMessages.fortressMoved(50,1000,TacticalCommandMessages.Point(1f,2f)))
        assertEquals(26,moved.remaining());assertEquals(0x435,moved.getShort(4).toInt());assertEquals(2f,moved.getFloat(18))
    }
}
