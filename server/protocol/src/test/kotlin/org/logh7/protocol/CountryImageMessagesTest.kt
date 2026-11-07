package org.logh7.protocol

import java.nio.ByteBuffer
import java.util.Base64
import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryImageMessagesTest {
    @Test fun thumbnailsAreChunkedBoundedAndClearingIsAnExplicitPayload() {
        val query=OutfitPartyMessages.Query(42,99,17)
        for(flags in 0..3) {
            val image=ByteArray(4096){it.toByte()}
            val packets=CountryImageMessages.replies(query,Long.MAX_VALUE,if(flags and 1!=0)image else null,if(flags and 2!=0)image else null)
            val text=StringBuilder();var header=emptyList<String>();var member=1
            for((page,packet) in packets.withIndex()) {
                val input=ByteBuffer.wrap(packet);assertEquals(0,input.int);assertEquals(0x032f,input.short.toInt() and 65535)
                assertEquals(42,input.int);assertEquals(99,input.int);assertEquals(17,input.get().toInt());assertEquals(2,input.get().toInt());input.get()
                assertEquals(if(page==0)0 else 1,input.int);assertEquals(if(page==0)0 else page-1,input.int)
                val count=input.get().toInt() and 255;assertTrue(count in 1..10)
                val fields=List(count) {index->
                    assertEquals(if(page==0)index+1 else member++,input.int)
                    assertEquals(if(page==0)index else 0,input.get().toInt());assertEquals(0,input.get().toInt())
                    val size=input.get().toInt() and 255;assertTrue(size in 0..13)
                    buildString {repeat(size){append(input.char)}}.removeSuffix("\u0000")
                }
                if(page==0)header=fields else fields.forEach(text::append)
            }
            assertEquals("CI1",header[0]);assertEquals(Long.MAX_VALUE.toString(),header[1]+header[2])
            val decoded=Base64.getDecoder().decode(text.toString());assertEquals(1,decoded[0].toInt());assertEquals(flags,decoded[1].toInt())
            assertEquals(2+Integer.bitCount(flags)*4096,decoded.size)
            if(flags!=0)assertContentEquals(image,decoded.copyOfRange(2,4098))
            assertEquals(decoded.size,header[3].toInt());assertTrue(packets.size<=93)
        }
        assertFailsWith<IllegalArgumentException>{CountryImageMessages.replies(query.copy(base=0),0,null,null)}
        assertFailsWith<IllegalArgumentException>{CountryImageMessages.replies(query,0,ByteArray(4095),null)}
    }
}
