package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class SupplementDirectoryMessagesTest {
    private fun raw()=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(34).putShort(0)
        .also {it.putShort(23,119)}.array()
    @Test fun selectedKindAndDestroyedBoatsUseTheNativeSevenByteRecord() {
        val q=SupplementDirectoryMessages.query(raw());assertEquals(10000,q.character);assertEquals(119,q.kind)
        val messages=SupplementDirectoryMessages.response(q,listOf(SupplementDirectoryMessages.Unit(641,119,10)))
        assertEquals(listOf(0x1200,0x1207,0x1201),messages.map {ByteBuffer.wrap(it).getShort(4).toInt()})
        assertContentEquals(byteArrayOf(0,1,0,0,2,-127,119,0,10),messages[1].copyOfRange(6,messages[1].size))
        assertContentEquals(raw().copyOfRange(2,31),messages[0].copyOfRange(6,messages[0].size))
    }
    @Test fun unsupportedFieldsKindsAndOversizedOrWrongKindResultsFailClosed() {
        for(index in listOf(8,10,22,25,30))assertFails {SupplementDirectoryMessages.query(raw().also {it[index]=1})}
        assertFails {SupplementDirectoryMessages.query(raw().copyOf(30))}
        assertFails {SupplementDirectoryMessages.query(raw().also {it[24]=(-1).toByte()})}
        val q=SupplementDirectoryMessages.query(raw())
        assertFails {SupplementDirectoryMessages.response(q,List(601){SupplementDirectoryMessages.Unit(it+1,119,1)})}
        assertFails {SupplementDirectoryMessages.response(q,listOf(SupplementDirectoryMessages.Unit(1,32,1)))}
    }
}
