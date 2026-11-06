package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class CountrySelectionMessagesTest {
    private fun query(country:Int=42,session:Int=65535,nonce:Int=32767,mode:Int=8)=
        ByteBuffer.allocate(11).putShort(0x032e).putInt(country).putInt(nonce*65536+session).put(mode.toByte()).array()
    @Test fun fullCountryIdAndNonceRoundTripWithoutChangingNativePower() {
        val request=CountrySelectionMessages.request(OutfitPartyMessages.query(query(Int.MAX_VALUE)))
        assertEquals(Int.MAX_VALUE,request.countryId);assertEquals(65535,request.sessionId);assertEquals(32767,request.nonce)
        val body=CountrySelectionMessages.reply(request,true,3,"empire")
        val b=ByteBuffer.wrap(body)
        assertEquals(Int.MAX_VALUE,b.int);assertEquals(Int.MAX_VALUE,b.int)
        assertEquals(8,b.get().toInt());assertEquals(2,b.get().toInt(),"header is a decoder envelope, not membership")
        b.get();assertEquals(0,b.int);assertEquals(0,b.int);assertEquals(10,b.get().toInt())
        val fields=(0 until 10).map {b.int;b.get();b.get();val n=b.get().toInt() and 255
            if(n==0)"" else String(CharArray(n){b.char}).removeSuffix("\u0000")}
        assertEquals(listOf("NS1","1","3","empire"),fields.take(4))
        assertTrue(fields.drop(4).all {it.isEmpty()})
    }
    @Test fun cancelDeclineAndMalformedChoicesStayDistinct() {
        val cancel=CountrySelectionMessages.request(OutfitPartyMessages.query(query(0,1,1,10)))
        assertTrue(CountrySelectionMessages.reply(cancel,true).isNotEmpty())
        val choose=CountrySelectionMessages.request(OutfitPartyMessages.query(query()))
        assertTrue(CountrySelectionMessages.reply(choose,false).isNotEmpty())
        assertFails {CountrySelectionMessages.reply(choose,true,42,"alliance")}
        assertFails {CountrySelectionMessages.reply(cancel,true,2,"empire")}
        for(bytes in listOf(query(0),query(session=0),query(nonce=0),query(mode=7),query(mode=10)))
            assertFails {CountrySelectionMessages.request(OutfitPartyMessages.query(bytes))}
        assertFails {OutfitPartyMessages.query(query(mode=4))}
    }
}
