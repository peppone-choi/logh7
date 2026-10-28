package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalTurnMessagesTest {
    private fun command(reverse:Boolean,count:Int=2)=TacticalTurnMessages.Command(10000,
        List(count) {TacticalTurnMessages.Unit(it+1,it/10f)},if(reverse)null else -1.25f,if(reverse)255 else 0)
    @Test fun readsIndependentPackedRecordsWithDifferentTurnAndReverseTails() {
        fun bytes(hex:String)=hex.chunked(2).map {it.toInt(16).toByte()}.toByteArray()
        val turn=TacticalTurnMessages.command(bytes("040100000001000000050000271001000000033f800000bf800000"))
        assertEquals(TacticalTurnMessages.Command(10000,listOf(TacticalTurnMessages.Unit(3,1f)),-1f),turn)
        val reverse=TacticalTurnMessages.command(bytes("0403000000010000000a0000271001000000033f8000007f"))
        assertEquals(TacticalTurnMessages.Command(10000,listOf(TacticalTurnMessages.Unit(3,1f)),null,127),reverse)
    }
    @Test fun nativePackedTurnAndReverseRoundTripWithNoPadding() {
        for(reverse in listOf(false,true))for(count in listOf(1,2,32)) {
            val command=command(reverse,count)
            val bytes=TacticalTurnMessages.reply(command,0xfffffffaL,5)
            val input=ByteBuffer.wrap(bytes)
            assertEquals(19+count*8+if(reverse)1 else 4,bytes.size)
            assertEquals(0,input.int);assertEquals(if(reverse)0x0403 else 0x0401,input.short.toInt())
            assertEquals(0xfffffffaL,input.int.toLong() and 0xffffffffL);assertEquals(5,input.int)
            assertEquals(10000,input.int);assertEquals(count,input.get().toInt() and 255)
            command.units.forEach {assertEquals(it.id,input.int);assertEquals(it.direction,input.float)}
            if(reverse)assertEquals(255,input.get().toInt() and 255) else assertEquals(-1.25f,input.float)
            assertFalse(input.hasRemaining())
            assertEquals(command,TacticalTurnMessages.command(bytes.copyOfRange(4,bytes.size)))
            assertEquals(1,ByteBuffer.wrap(TacticalTurnMessages.reply(command,0,0,false)).int)
        }
    }
    @Test fun rejectsTruncationWrongCountDuplicateIdsAndInvalidDirections() {
        for(reverse in listOf(false,true)) {
            val reply=TacticalTurnMessages.reply(command(reverse),0,0)
            val valid=reply.copyOfRange(4,reply.size)
            fun rejects(change:(ByteArray)->ByteArray) {
                assertFailsWith<IllegalArgumentException> {TacticalTurnMessages.command(change(valid.copyOf()))}
            }
            rejects {it.copyOf(it.size-1)};rejects {it+0.toByte()}
            rejects {it.also {it[14]=0}}
            rejects {it.also {it[14]=33}}
            rejects {it.also {ByteBuffer.wrap(it).putInt(10,0)}}
            rejects {it.also {ByteBuffer.wrap(it).putShort(0,0x0400)}}
            rejects {it.also {ByteBuffer.wrap(it).putInt(23,1)}}
            rejects {it.also {ByteBuffer.wrap(it).putFloat(19,Float.NaN)}}
            if(!reverse)rejects {it.also {ByteBuffer.wrap(it).putFloat(it.size-4,Float.POSITIVE_INFINITY)}}
        }
        assertFailsWith<IllegalArgumentException> {TacticalTurnMessages.reply(command(false),0xffffffffL,1)}
    }
}
