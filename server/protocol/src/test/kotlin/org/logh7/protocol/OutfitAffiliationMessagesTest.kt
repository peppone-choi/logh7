package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class OutfitAffiliationMessagesTest {
    private val query=OutfitPartyMessages.Query(65535,800000,13)
    private fun members(frame:ByteArray):List<OutfitPartyMessages.Member> {
        val b=ByteBuffer.wrap(frame);assertEquals(query.outfit,b.int);assertEquals(query.base,b.int)
        assertEquals(13,b.get().toInt());assertEquals(2,b.get().toInt());b.get();b.int;b.int
        return List(b.get().toInt() and 255) {
            val id=b.int;val kind=b.get().toInt() and 255;val rank=b.get().toInt() and 255;val n=b.get().toInt() and 255
            OutfitPartyMessages.Member(id,kind,rank,if(n==0)"" else String(CharArray(n){b.char}).removeSuffix("\u0000"))
        }
    }
    @Test fun pagedCursorSupportsPirateIdsAndExactGenerationWithoutNativeAliases() {
        val owners=(800000..800060).reversed().map {OutfitAffiliationMessages.Owner(it,Int.MAX_VALUE,3,1)}
        val frames=OutfitAffiliationMessages.replies(query,2487,Long.MAX_VALUE,owners)
        assertEquals(7,frames.size)
        val header=members(frames.first()).map {it.name}
        assertEquals(listOf("FA1","60","800060","0","2487","922337203685","4775807","","",""),header)
        val rows=frames.drop(1).flatMap(::members)
        assertEquals((800001..800060).toList(),rows.map {it.id})
        assertTrue(rows.all {it.name=="2147483647" && it.kind==1 && it.rank==3})
        val more=OutfitAffiliationMessages.replies(query,2487,1,owners+OutfitAffiliationMessages.Owner(800061,0,4,0))
        assertEquals("1",members(more.first())[3].name)
        assertEquals("0",members(OutfitAffiliationMessages.replies(query,2487,1,emptyList()).first())[1].name)
        val largest=OutfitAffiliationMessages.replies(query,2487,1,listOf(OutfitAffiliationMessages.Owner(Int.MAX_VALUE,42,3,0)))
        assertEquals(Int.MAX_VALUE,members(largest.last()).single().id)
        val wire=ByteBuffer.allocate(11).putShort(0x032e).putInt(65535).putInt(Int.MAX_VALUE).put(13).array()
        assertEquals(query.copy(base=Int.MAX_VALUE),OutfitPartyMessages.query(wire))
        wire[10]=12;assertFails {OutfitPartyMessages.query(wire)}
    }
    @Test fun invalidCursorNonceIdentityAndRowsAreRejected() {
        val o=OutfitAffiliationMessages.Owner(800001,42,3,0)
        for(q in listOf(query.copy(outfit=0),query.copy(outfit=65536),query.copy(base=-1),query.copy(mode=12)))
            assertFails {OutfitAffiliationMessages.replies(q,2487,1,listOf(o))}
        assertFails {OutfitAffiliationMessages.replies(query,5000,1,listOf(o))}
        assertFails {OutfitAffiliationMessages.replies(query,2487,0,listOf(o))}
        for(row in listOf(o.copy(id=0),o.copy(country=-1),o.copy(power=42),o.copy(camp=2)))
            assertFails {OutfitAffiliationMessages.replies(query,2487,1,listOf(row))}
        assertFails {OutfitAffiliationMessages.replies(query,2487,1,listOf(o,o))}
    }
}
