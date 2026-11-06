package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class FacilityGuideMessagesTest {
    private fun fields(data:ByteArray):List<String> {
        val b=ByteBuffer.wrap(data)
        assertEquals(105038,b.int);assertEquals(6*16+page,b.int);assertEquals(6,b.get().toInt());assertEquals(3,b.get().toInt())
        assertEquals(0,b.get().toInt());assertEquals(0,b.int);assertEquals(0,b.int);assertEquals(10,b.get().toInt())
        val fields=(0..9).map {index->
            assertEquals(index+1,b.int);assertEquals(index,b.get().toInt());assertEquals(0,b.get().toInt())
            val length=b.get().toInt();if(length==0)"" else buildString {repeat(length-1){append(b.char)};assertEquals('\u0000',b.char)}
        }
        assertEquals(0,b.get().toInt());assertEquals(0,b.get().toInt());assertEquals(0,b.int);assertEquals(0,b.int);repeat(9){assertEquals(0,b.get().toInt())};assertFalse(b.hasRemaining())
        return fields
    }
    private var page=0
    @Test fun everyPageRetainsFacilityNonceAndRevisionAndReassemblesTheMaximumTexts() {
        val name="가".repeat(24);val purpose="용".repeat(200);val usage="조".repeat(200);val commands="명".repeat(192)
        val parts=(0..8).map {index->
            page=index
            val row=fields(FacilityGuideMessages.reply(OutfitPartyMessages.Query(105038,6*16+index,6),3,Long.MAX_VALUE,name,purpose,usage,commands))
            assertEquals(Long.MAX_VALUE.toString(),row.take(2).joinToString(""));row.drop(2).joinToString("")
        }
        assertEquals(name,parts[0]);assertEquals(purpose,parts.subList(1,4).joinToString(""))
        assertEquals(usage,parts.subList(4,7).joinToString(""));assertEquals(commands,parts.subList(7,9).joinToString(""))
    }
    @Test fun invalidPageIdentityAndTextCannotEnterTheNativeDecoder() {
        val good=OutfitPartyMessages.Query(105038,16,6)
        for(query in listOf(good.copy(base=15),good.copy(base=25),good.copy(outfit=38),good.copy(outfit=105040),good.copy(mode=0)))
            assertFailsWith<IllegalArgumentException> {FacilityGuideMessages.reply(query,2,0,"Name","","","")}
        for(text in listOf("가".repeat(201),"줄\n바꿈","\uD800"))
            assertFailsWith<IllegalArgumentException> {FacilityGuideMessages.reply(good,2,0,"Name",text,"","")}
    }
}
