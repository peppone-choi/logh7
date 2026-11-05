package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryMetadataMessagesTest {
    @Test fun metadataUsesDedicatedModeWithBoundedUnicodeAndExactRevision() {
        val query=OutfitPartyMessages.Query(42,17,5)
        val name="가나다라마바사아자차카타파하"
        val body=ByteBuffer.wrap(CountryMetadataMessages.reply(query,2,name,"시험력",-310,Long.MAX_VALUE))
        assertEquals(42,body.int);assertEquals(17,body.int);assertEquals(5,body.get().toInt());assertEquals(2,body.get().toInt())
        body.get();body.int;body.int;assertEquals(6,body.get().toInt())
        val fields=(0..5).map {index->
            assertEquals(index+1,body.int);assertEquals(index,body.get().toInt());assertEquals(0,body.get().toInt())
            val n=body.get().toInt() and 255;buildString {repeat(n){append(body.char)}}.removeSuffix("\u0000")
        }
        assertEquals(listOf(name.take(12),name.drop(12),"시험력","-310","922337203685","4775807"),fields)
        assertFailsWith<IllegalArgumentException>{CountryMetadataMessages.reply(query.copy(mode=4),2,name,"시험력",0,0)}
        assertFailsWith<IllegalArgumentException>{CountryMetadataMessages.reply(query,2,"x".repeat(25),"시험력",0,0)}
        assertFailsWith<IllegalArgumentException>{CountryMetadataMessages.reply(query,2,name,"시험\n력",0,0)}
        assertFailsWith<IllegalArgumentException>{CountryMetadataMessages.reply(query,2,name,"시험력",10001,0)}
    }
}
