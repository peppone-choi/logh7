package org.logh7.protocol

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticsMessagesTest {
    @Test fun beamSlider130IsAnUnsignedWireByteInControlAndCorps() {
        val energy=List(11){if(it==3)130 else 0}
        val control=TacticsMessages.Control(10000,1010000,energy)
        val packet=TacticsMessages.controlReply(control,270,100)
        assertEquals(130,packet[24].toInt() and 255)
        assertEquals(control,TacticsMessages.control(packet.copyOfRange(4,packet.size)))
        val corps=TacticsMessages.corps(listOf(TacticsMessages.Corps(10000,0f,energy,List(6){0})))
        assertEquals(130,corps[27].toInt() and 255)
    }
    @Test fun controlAllocationUsesItsDifferentWireOrderAndServerTimeAndReserve() {
        val control=TacticsMessages.Control(10000,1010000,(1..11).toList())
        val reply=TacticsMessages.controlReply(control,334,123456)
        assertEquals(35,reply.size)
        assertEquals(123456,java.nio.ByteBuffer.wrap(reply).getInt(6))
        assertEquals(334,java.nio.ByteBuffer.wrap(reply).getShort(22).toInt())
        assertContentEquals(byteArrayOf(4,5,6,7,8,9,10,11,1,2,3),reply.copyOfRange(24,35))
        assertEquals(control,TacticsMessages.control(reply.copyOfRange(4,reply.size)))
        assertEquals(1,java.nio.ByteBuffer.wrap(TacticsMessages.controlReply(control,334,1,false)).getInt(0))
        assertFailsWith<IllegalArgumentException>{TacticsMessages.control(reply.copyOfRange(4,34))}
        assertFailsWith<IllegalArgumentException>{control.copy(energy=List(11){256})}
    }
    @Test fun nativeCorpsStateUsesCharacterIdentityAndPackedEnergyShieldFields() {
        val corps=TacticsMessages.Corps(10000,12f,(1..11).toList(),(101..106).toList(),
            mission=3,targetKind=2,target=42,chief=true,formation=4,beam=120,gun=130,damagedShields=(1..6).toList())
        val bytes=java.nio.ByteBuffer.wrap(TacticsMessages.corps(listOf(corps)))
        assertEquals(63,bytes.limit());assertEquals(0x033f,bytes.getShort(4).toInt());assertEquals(1,bytes.getShort(6).toInt())
        assertEquals(10000,bytes.getInt(8));assertEquals(3,bytes.get(12).toInt());assertEquals(2,bytes.get(13).toInt())
        assertEquals(42,bytes.getInt(14));assertEquals(12f,bytes.getFloat(18));assertEquals(1,bytes.get(22).toInt())
        assertEquals(4,bytes.get(23).toInt());assertEquals((1..11).toList(),(24..34).map {bytes.get(it).toInt()})
        assertEquals(120,bytes.getShort(35).toInt());assertEquals(130,bytes.getShort(37).toInt())
        assertEquals(101,bytes.getShort(39).toInt());assertEquals(106,bytes.getShort(49).toInt())
        assertEquals(1,bytes.getShort(51).toInt());assertEquals(6,bytes.getShort(61).toInt())
        assertFailsWith<IllegalArgumentException>{corps.copy(energy=List(11){256})}
        assertFailsWith<IllegalArgumentException>{corps.copy(shields=List(5){0})}
        assertFailsWith<IllegalArgumentException>{TacticsMessages.corps(listOf(corps,corps))}
    }
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
        assertEquals(1.5f,state.getFloat(30));assertEquals(0,state.getInt(34));assertEquals(1,state.get(54).toInt())
        assertEquals(0f,state.getFloat(38));assertEquals(0f,state.getFloat(50))
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
