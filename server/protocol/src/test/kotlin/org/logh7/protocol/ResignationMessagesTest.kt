package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class ResignationMessagesTest {
    private fun request()=ByteBuffer.allocate(27).putShort(0x0709).putInt(0).putInt(10001).putInt(0).putInt(0).putInt(57).putInt(0).put(0).array()
    @Test fun observedNativeRequestAndResponseRetainFieldOrder() {
        val q=ResignationMessages.query(request());assertEquals(ResignationMessages.Query(10001,57),q)
        val response=ByteBuffer.wrap(ResignationMessages.response(q,123,1000,920,true))
        assertEquals(31,response.capacity());assertEquals(0,response.int);assertEquals(0x709,response.short.toInt())
        assertEquals(123,response.int);assertEquals(10001,response.int);assertEquals(1000,response.int);assertEquals(920,response.int)
        assertEquals(57,response.int);assertEquals(0,response.int);assertEquals(0,response.get().toInt())
        assertEquals(1,ByteBuffer.wrap(ResignationMessages.response(q,123,1000,1000,false)).int)
    }
    @Test fun malformedRequestsCannotChooseAnotherSpotOrMovedCharacters() {
        for(bytes in listOf(request().dropLast(1).toByteArray(),request()+byteArrayOf(0),
            request().also {it[26]=1},request().also {ByteBuffer.wrap(it).putInt(22,1234)},
            request().also {ByteBuffer.wrap(it).putInt(6,0)},request().also {ByteBuffer.wrap(it).putInt(18,261)}))
            assertFailsWith<IllegalArgumentException> {ResignationMessages.query(bytes)}
    }
}
