package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class AppointmentDirectoryMessagesTest {
    private fun request()=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(18).putShort(123).array()
    @Test fun nativeCardListHasTargetZeroAndByteVacancyCountBetweenBeginAndEnd() {
        val query=AppointmentDirectoryMessages.query(request())
        val packets=AppointmentDirectoryMessages.cards(query,14)
        assertEquals(listOf(0x1200,0x1208,0x1201),packets.map { ByteBuffer.wrap(it).getShort(4).toInt() })
        assertContentEquals(request().copyOfRange(2,31),packets[0].copyOfRange(6,35))
        val b=ByteBuffer.wrap(packets[1],6,9)
        assertEquals(1,b.short.toInt());assertEquals(55,b.short.toInt());assertEquals(0,b.int);assertEquals(14,b.get().toInt())
        val empty=AppointmentDirectoryMessages.cards(query,0)[1]
        assertEquals(8,empty.size);assertEquals(0,ByteBuffer.wrap(empty).getShort(6).toInt())
    }
    @Test fun unsupportedSearchesFiltersAndMalformedRecordsAreRejected() {
        val valid=request()
        for(size in 0 until valid.size)assertFails { AppointmentDirectoryMessages.query(valid.copyOf(size)) }
        for((offset,value) in listOf(6 to 19,8 to 0,10 to 1))assertFails {
            AppointmentDirectoryMessages.query(valid.copyOf().also { ByteBuffer.wrap(it).putShort(offset,value.toShort()) })
        }
        assertFails { AppointmentDirectoryMessages.cards(AppointmentDirectoryMessages.query(valid),256) }
    }
    @Test fun baseSelectionCarriesItsIdentityAndRejectsTruncatedNativeValues() {
        val query=AppointmentDirectoryMessages.query(request())
        val base=AppointmentDirectoryMessages.Base(1049,2487,"Home")
        val b=ByteBuffer.wrap(AppointmentDirectoryMessages.bases(query,listOf(base))[1]).position(6)
        assertEquals(1,b.short.toInt());assertEquals(1,b.get().toInt())
        assertEquals(1049,b.int);assertEquals(2487,b.short.toInt());assertEquals(1049,b.short.toInt())
        assertEquals(5,b.get().toInt());assertEquals("Home\u0000",(1..5).map { b.char }.joinToString(""))
        assertFalse(b.hasRemaining())
        for(invalid in listOf(base.copy(id=65536),base.copy(grid=5000),base.copy(name="x\u0000y")))
            assertFails { AppointmentDirectoryMessages.bases(query,listOf(invalid)) }
    }
    @Test fun peopleIncludeActualRolesFaceAndAgeWithoutObjectPadding() {
        val query=AppointmentDirectoryMessages.query(request())
        val person=CharacterMessages.Generate(4,10001,2,0,0,"Test","Guest",70,1,1,123,List(8){50},0,0,0,10,0,0,"Ship",0)
        val b=ByteBuffer.wrap(AppointmentDirectoryMessages.people(query,listOf(
            AppointmentDirectoryMessages.Person(person,listOf(0,59,55))))[1]).position(6)
        assertEquals(1,b.get().toInt());assertEquals(10001,b.int)
        val nameLength=b.get().toInt();assertEquals("Test\u0000",(1..nameLength).map { b.char }.joinToString(""))
        assertEquals(10,b.short.toInt());assertEquals(0,b.short.toInt());assertEquals(3,b.get().toInt())
        assertEquals(listOf(0,59,55),(1..3).map { b.short.toInt() })
        assertEquals(0,b.get().toInt());assertEquals(0,b.get().toInt());assertEquals(123,b.int)
        assertEquals(70L*CharacterMessages.AGE_SECONDS_PER_YEAR,b.int.toLong() and 0xffffffffL)
        assertFalse(b.hasRemaining())
    }
}
