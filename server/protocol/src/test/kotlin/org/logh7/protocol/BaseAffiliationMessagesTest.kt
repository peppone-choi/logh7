package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class BaseAffiliationMessagesTest {
    private val query=OutfitPartyMessages.Query(0,65535,12)
    private fun members(bytes:ByteArray):List<OutfitPartyMessages.Member> {
        val b=ByteBuffer.wrap(bytes);assertEquals(0,b.int);assertEquals(65535,b.int)
        assertEquals(12,b.get().toInt());assertEquals(2,b.get().toInt());b.get();b.int;b.int
        return List(b.get().toInt() and 255) {
            val id=b.int;val kind=b.get().toInt() and 255;val rank=b.get().toInt() and 255
            val n=b.get().toInt() and 255
            OutfitPartyMessages.Member(id,kind,rank,if(n==0)"" else String(CharArray(n){b.char}).removeSuffix("\u0000"))
        }
    }
    @Test fun fullSnapshotHasAnExplicitHeaderAndSortedBoundedChunks() {
        val owners=(1..350).reversed().map {BaseAffiliationMessages.Owner(it,if(it%2==0)42 else Int.MAX_VALUE,3,it%2)}
        val frames=BaseAffiliationMessages.replies(query,owners)
        assertEquals(36,frames.size);assertEquals(listOf("BA1","350")+List(8){""},members(frames.first()).map {it.name})
        val rows=frames.drop(1).flatMapIndexed {index,frame->
            val b=ByteBuffer.wrap(frame);assertEquals(1,b.getInt(11));assertEquals(index,b.getInt(15));members(frame)}
        assertEquals((1..350).toList(),rows.map {it.id});assertTrue(rows.all {it.rank==3 && it.name in setOf("42","2147483647")})
        assertEquals(1,BaseAffiliationMessages.replies(query,emptyList()).size)
        assertEquals(1,members(BaseAffiliationMessages.replies(query,owners.take(11)).last()).size)
    }
    @Test fun rejectsAmbiguousOrOutOfBoundsData() {
        val owner=BaseAffiliationMessages.Owner(1,42,3,0)
        for(q in listOf(query.copy(outfit=42),query.copy(base=0),query.copy(mode=11)))
            assertFails {BaseAffiliationMessages.replies(q,listOf(owner))}
        for(o in listOf(owner.copy(id=0),owner.copy(id=65536),owner.copy(country=-1),owner.copy(power=42),owner.copy(camp=2)))
            assertFails {BaseAffiliationMessages.replies(query,listOf(o))}
        assertFails {BaseAffiliationMessages.replies(query,listOf(owner,owner))}
        assertFails {BaseAffiliationMessages.replies(query,(1..351).map {owner.copy(id=it)})}
    }
}
