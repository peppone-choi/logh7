package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryEndingMessagesTest {
    private fun request(offset:Int=0)=CountryEndingMessages.Request(OutfitPartyMessages.Query(65535,127*512+offset,14))
    private fun fields(frame:ByteArray):Pair<Pair<Int,Int>,List<String>> {
        val b=ByteBuffer.wrap(frame);assertEquals(65535,b.int);b.int
        assertEquals(14,b.get().toInt());assertEquals(2,b.get().toInt());b.get()
        val kind=b.int;val number=b.int;assertEquals(10,b.get().toInt())
        return (kind to number) to List(10) {i->
            assertEquals(i+1,b.int);assertEquals(i,b.get().toInt());assertEquals(0,b.get().toInt())
            val n=b.get().toInt() and 255
            if(n==0)"" else String(CharArray(n){b.char}).removeSuffix("\u0000")
        }
    }
    private fun row(id:Int)=CountryEndingMessages.Row(id,"가".repeat(24),0xff40e0,Int.MAX_VALUE,3,1,0,true,
        65535,65535,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE)
    @Test fun everyCountryHasItsOwnRowsAndExactLongStatistics() {
        val rows=(1..350).map(::row).reversed()
        val result=CountryEndingMessages.reply(request(),Long.MAX_VALUE,Long.MAX_VALUE,3,rows).map(::fields)
        assertEquals(7,result.size)
        assertEquals(listOf("CE1","350","0","3","922337203685","4775807","922337203685","4775807","3","0"),result.first().second)
        assertEquals(listOf(0 to 0,1 to 0,2 to 0,1 to 1,2 to 1,1 to 2,2 to 2),result.map {it.first})
        assertEquals(listOf("1","가".repeat(12),"가".repeat(12),"3","2147483647","1","0","FF40E0","1",""),result[1].second)
        assertEquals(listOf("65535","65535","922337203685","4775807","922337203685","4775807","922337203685","4775807","",""),result[2].second)
        val end=CountryEndingMessages.reply(request(348),1,0,5,rows).map(::fields)
        assertEquals(5,end.size);assertEquals(listOf("349","350"),end.filter {it.first.first==1}.map {it.second[0]})
        assertEquals(1,CountryEndingMessages.reply(request(350),1,0,5,rows).size)
        assertEquals("0",fields(CountryEndingMessages.empty(request()).single()).second[8])
    }
    @Test fun invalidRequestOrPartialInvalidResultIsRefused() {
        for(q in listOf(request().query.copy(mode=13),request().query.copy(outfit=0),request().query.copy(base=350),request().query.copy(base=65535)))
            assertFails {CountryEndingMessages.Request(q)}
        for(rows in listOf(listOf(row(1),row(1)),(1..351).map(::row)))
            assertFails {CountryEndingMessages.reply(request(),1,0,5,rows)}
        assertFails {CountryEndingMessages.reply(request(),0,0,5,listOf(row(1)))}
        assertFails {CountryEndingMessages.reply(request(),1,0,0,emptyList())}
        assertFails {row(1).copy(name="bad\nname").let {CountryEndingMessages.reply(request(),1,0,5,listOf(it))}}
        assertFails {row(1).copy(population=-1)}
    }
}
