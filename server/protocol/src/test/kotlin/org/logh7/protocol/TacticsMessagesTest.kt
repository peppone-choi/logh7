package org.logh7.protocol

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticsMessagesTest {
    private val unit=TacticsMessages.Unit(42,32,7,10000,9000,800f,2,12f,-3f,20f,1.5f)
    @Test fun nativeTacticalRecordsArePackedWithoutNativeAlignment() {
        val definitions=java.nio.ByteBuffer.wrap(TacticsMessages.definitions(2017,listOf(unit)))
        assertEquals(50,definitions.limit());assertEquals(1,definitions.getShort(6).toInt())
        assertEquals(42,definitions.getInt(8));assertEquals(32,definitions.getShort(12).toInt())
        assertEquals(5,definitions.get(14).toInt());assertEquals(2017,definitions.getInt(15))
        assertEquals(7,definitions.getInt(19));assertEquals(2,definitions.getShort(36).toInt())
        assertEquals(9000,definitions.getInt(38));assertEquals(800f,definitions.getFloat(46))
        val state=java.nio.ByteBuffer.wrap(TacticsMessages.states(listOf(unit)))
        assertEquals(55,state.limit());assertEquals(42,state.getInt(8));assertEquals(10000,state.getInt(14))
        assertEquals(12f,state.getFloat(18));assertEquals(-3f,state.getFloat(22));assertEquals(20f,state.getFloat(26))
        assertEquals(1.5f,state.getFloat(30));assertEquals(42,state.getInt(34));assertEquals(1,state.get(54).toInt())
        val position=java.nio.ByteBuffer.wrap(TacticsMessages.positions(listOf(unit)))
        assertEquals(28,position.limit());assertEquals(42,position.getInt(8));assertEquals(1.5f,position.getFloat(24))
        val base=java.nio.ByteBuffer.wrap(TacticsMessages.basePositions(listOf(TacticsMessages.Base(1000,12f,-3f,20f))))
        assertEquals(23,base.limit());assertEquals(1,base.get(6).toInt());assertEquals(1000,base.getInt(7))
        assertEquals(20f,base.getFloat(19))
    }
    @Test fun tacticalIdQueriesRejectTruncationDuplicatesAndOverCapacity() {
        fun query(vararg ids:Int)=java.nio.ByteBuffer.allocate(4+ids.size*4).putShort(0x0348).putShort(ids.size.toShort())
            .also {out->ids.forEach(out::putInt)}.array()
        assertEquals(listOf(1,42),TacticsMessages.queryIds(query(1,42),0x0348))
        assertEquals(emptyList(),TacticsMessages.queryIds(byteArrayOf(3,74,0),0x034a,true))
        assertFailsWith<IllegalArgumentException>{TacticsMessages.queryIds(query(1).copyOf(7),0x0348)}
        assertFailsWith<IllegalArgumentException>{TacticsMessages.queryIds(query(1,1),0x0348)}
        assertFailsWith<IllegalArgumentException>{TacticsMessages.queryIds(query(0),0x0348)}
        assertFailsWith<IllegalArgumentException>{TacticsMessages.queryIds(query(1),0x033a)}
        assertFailsWith<IllegalArgumentException>{TacticsMessages.queryIds(query(*(1..601).toList().toIntArray()),0x0348)}
        assertFailsWith<IllegalArgumentException>{TacticsMessages.positions(listOf(unit.copy(x=Float.NaN)))}
    }
    @Test fun nativeNotifyOmitsPaddingBetweenStateAndGrid() {
        assertContentEquals(byteArrayOf(0,0,0,0,15,31,1,0,0,7,-31),TacticsMessages.notify(2017,true))
        assertContentEquals(byteArrayOf(0,0,0,0,15,31,0,0,0,7,-31),TacticsMessages.notify(2017,false))
        assertContentEquals(byteArrayOf(0,0,0,0,3,23,7,-31,1),NavigationMessages.grid(2017,true))
        assertFailsWith<IllegalArgumentException> {TacticsMessages.notify(5000,true)}
    }
}
