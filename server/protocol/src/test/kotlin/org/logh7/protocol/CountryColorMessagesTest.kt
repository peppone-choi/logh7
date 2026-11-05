package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryColorMessagesTest {
    @Test fun `mode four has independent color slots and an exact 64-bit revision`() {
        val query=OutfitPartyMessages.Query(2,17,4)
        val body=ByteBuffer.wrap(CountryColorMessages.reply(query,3,listOf("000000","inherit","FFFFFF","000000"),9007199254740993))
        assertEquals(2,body.int);assertEquals(17,body.int);assertEquals(4,body.get().toInt());assertEquals(3,body.get().toInt())
        body.get();body.int;body.int;assertEquals(6,body.get().toInt())
        val fields=(0..5).map {index ->
            assertEquals(index+1,body.int);assertEquals(index,body.get().toInt());assertEquals(0,body.get().toInt())
            val length=body.get().toInt() and 255
            buildString {repeat(length){append(body.char)}}.removeSuffix("\u0000")
        }
        assertEquals(listOf("000000","inherit","FFFFFF","000000","900719925474","0993"),fields)
        assertFailsWith<IllegalArgumentException>{CountryColorMessages.reply(query.copy(base=0),2,List(4){"inherit"},0)}
        assertFailsWith<IllegalArgumentException>{CountryColorMessages.reply(query,2,listOf("url(x)","inherit","inherit","inherit"),0)}
    }
}
