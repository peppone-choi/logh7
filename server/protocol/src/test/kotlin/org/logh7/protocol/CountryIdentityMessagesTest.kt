package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*
class CountryIdentityMessagesTest {
    @Test fun membershipIsTextWhileTheQueryStillEchoesTheCharacterAndNonce() {
        val query=OutfitPartyMessages.Query(12345,65535,11)
        val body=CountryIdentityMessages.reply(query,Int.MAX_VALUE,3,"새 국가","신제국력",-798,Long.MAX_VALUE,true)
        val b=ByteBuffer.wrap(body);assertEquals(12345,b.int);assertEquals(65535,b.int)
        assertEquals(11,b.get().toInt());assertEquals(2,b.get().toInt())
        assertEquals(CountryIdentityMessages.REPAIR_QUOTE or CountryIdentityMessages.SUPPLY_QUOTE,b.get().toInt());b.int;b.int
        assertEquals(10,b.get().toInt())
        val fields=(0 until 10).map {b.int;b.get();b.get();val n=b.get().toInt() and 255
            if(n==0)"" else String(CharArray(n){b.char}).removeSuffix("\u0000")}
        assertEquals("2147483647",fields[1]);assertEquals("9223372036854775807",fields[6]+fields[7]);assertEquals("3",fields[8])
        assertFails {CountryIdentityMessages.reply(query.copy(base=0),42,3,"새 국가","우주력",0,0,true)}
        assertFails {CountryIdentityMessages.reply(query,0,3,"무소속","우주력",0,0,true)}
        assertFails {CountryIdentityMessages.reply(query,42,42,"새 국가","우주력",0,0,true)}
    }
}
