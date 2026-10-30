package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalBaseInformationTest {
    @Test fun nativeQueryRejectsPaddingDuplicatesAndOutOfRangeLists() {
        val request=ByteBuffer.allocate(7).putShort(0x344).put(1).putInt(1049).array()
        assertEquals(listOf(1049),TacticsMessages.baseInformationQuery(request))
        assertTrue(TacticsMessages.baseInformationQuery(byteArrayOf(3,0x44,0)).isEmpty())
        for(size in 0 until request.size)assertFailsWith<IllegalArgumentException>{TacticsMessages.baseInformationQuery(request.copyOf(size))}
        assertFailsWith<IllegalArgumentException>{TacticsMessages.baseInformationQuery(request+0)}
        assertFailsWith<IllegalArgumentException>{TacticsMessages.baseInformationQuery(ByteBuffer.allocate(11).putShort(0x344).put(2).putInt(1049).putInt(1049).array())}
        assertFailsWith<IllegalArgumentException>{TacticsMessages.baseInformationQuery(request.copyOf().also {it[2]=17})}
        assertFailsWith<IllegalArgumentException>{TacticsMessages.baseInformationQuery(request.copyOf().also {ByteBuffer.wrap(it).putInt(3,0)})}
    }
    @Test fun nativeInformationHasByteCountAndPackedDefenseFieldsWithoutMemoryPadding() {
        val base=TacticsMessages.BaseInformation(TacticsMessages.Base(1049,1f,2f,3f),4,5,6,7)
        val expected=ByteBuffer.allocate(35).putInt(0).putShort(0x345).put(1).putInt(1049)
            .putFloat(1f).putFloat(2f).putFloat(3f).putInt(4).putShort(5).putInt(6).putShort(7).array()
        assertContentEquals(expected,TacticsMessages.baseInformation(listOf(base)))
        assertEquals(7,TacticsMessages.baseInformation(emptyList()).size)
        assertEquals(455,TacticsMessages.baseInformation((1..16).map {base.copy(position=base.position.copy(id=it))}).size)
        for(bad in listOf(base.copy(position=base.position.copy(id=0)),base.copy(position=base.position.copy(x=Float.NaN)),
            base.copy(antiaircraft=-1),base.copy(cannonAngle=65536),base.copy(cannonStart=0x100000000L),base.copy(stamina=-1))) {
            assertFailsWith<IllegalArgumentException>{TacticsMessages.baseInformation(listOf(bad))}
        }
        assertFailsWith<IllegalArgumentException>{TacticsMessages.baseInformation(listOf(base,base))}
        assertFailsWith<IllegalArgumentException>{TacticsMessages.baseInformation((1..17).map {base.copy(position=base.position.copy(id=it))})}
    }
}
