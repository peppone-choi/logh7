package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class GroundTransferMessagesTest {
    @Test fun nativePackedLayoutUsesCpBalancesAndNoCppAlignmentPadding() {
        val packet=byteArrayOf(0x0b,0x05, 0,0,0,9, 0,0,0x27,0x10, 0,0,3,0x20,
            0,0,3,0x48, 0,0,0,12, 2, 0,0,0,99, -1,-1,-1,-1)
        val c=GroundTransferMessages.command(packet)
        assertEquals(GroundTransferMessages.Action.LOAD,c.action)
        assertEquals(10000,c.character);assertEquals(12,c.outfit)
        assertEquals(listOf(99L,0xffffffffL),c.values)
        val response=GroundTransferMessages.reply(c,9,800,840)
        assertEquals(0,ByteBuffer.wrap(response).int)
        assertContentEquals(packet,response.copyOfRange(4,response.size))
    }
    @Test fun zeroAndMaximumCountsPreserveOpaqueValuesForBothActions() {
        for(action in GroundTransferMessages.Action.entries)for(values in listOf(emptyList(),listOf(0L,0x80000000L,0xffffffffL))) {
            val c=GroundTransferMessages.Command(action,10000,12,values)
            val reply=GroundTransferMessages.reply(c,0xffffffffL,1000,900,false)
            assertEquals(1,ByteBuffer.wrap(reply).int)
            val read=GroundTransferMessages.command(reply.copyOfRange(4,reply.size))
            assertEquals(c.action,read.action);assertEquals(c.character,read.character)
            assertEquals(c.outfit,read.outfit);assertEquals(values,read.values)
        }
    }
    @Test fun truncatedExtraPaddedAndOversizedMessagesAreRejected() {
        val c=GroundTransferMessages.Command(GroundTransferMessages.Action.UNLOAD,10000,12,listOf(99L))
        val response=GroundTransferMessages.reply(c,9,800,840)
        val packet=response.copyOfRange(4,response.size)
        for(length in 0 until packet.size) assertFailsWith<IllegalArgumentException> {
            GroundTransferMessages.command(packet.copyOf(length))
        }
        for(bad in listOf(packet+0,packet.copyOf().also {it[22]=4},
            packet.copyOf().also {it[1]=6},packet.copyOf().also {ByteBuffer.wrap(it).putInt(6,0)},
            packet.copyOf().also {ByteBuffer.wrap(it).putInt(18,0)},
            packet.copyOfRange(0,23)+byteArrayOf(0,0,0)+packet.copyOfRange(23,packet.size))) {
            assertFailsWith<IllegalArgumentException> {GroundTransferMessages.command(bad)}
        }
    }
    @Test fun commandsAreImmutableAndServerBalancesCannotBeNegative() {
        val input=mutableListOf(99L)
        val c=GroundTransferMessages.Command(GroundTransferMessages.Action.LOAD,10000,12,input)
        input.clear();assertEquals(listOf(99L),c.values)
        assertFailsWith<UnsupportedOperationException> {(c.values as MutableList).clear()}
        for(values in listOf(listOf(-1L),listOf(0x100000000L),List(4){1L})) {
            assertFailsWith<IllegalArgumentException> {GroundTransferMessages.Command(c.action,10000,12,values)}
        }
        assertFailsWith<IllegalArgumentException> {GroundTransferMessages.reply(c,0,-1,0)}
        assertFailsWith<IllegalArgumentException> {GroundTransferMessages.reply(c,0,0,-1)}
        assertFailsWith<IllegalArgumentException> {GroundTransferMessages.reply(c,0x100000000L,0,0)}
    }
}
