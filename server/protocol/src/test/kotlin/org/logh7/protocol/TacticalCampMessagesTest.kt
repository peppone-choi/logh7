package org.logh7.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.*
import org.junit.jupiter.api.Test

/** 032f mode 15 codec: header, paging and the stable side fingerprint. Author: Peppone Choi. */
class TacticalCampMessagesTest {
    private val query=OutfitPartyMessages.Query(77,0,TacticalCampMessages.MODE)
    private val viewer=TacticalCampMessages.Side(0,42,false)
    /** Fields of one OutfitPartyMessages.party frame: kind, number and member rows (id, kind, rank, name). */
    private data class Frame(val kind:Int,val number:Int,val rows:List<OutfitPartyMessages.Member>)
    private fun decode(body:ByteArray):Frame {
        val b=ByteBuffer.wrap(body);b.int;b.int;b.get();b.get();b.get()
        val kind=b.int;val number=b.int;val count=b.get().toInt() and 255
        val rows=List(count) {
            val id=b.int;val k=b.get().toInt() and 255;val r=b.get().toInt() and 255
            val n=b.get().toInt() and 255;OutfitPartyMessages.Member(id,k,r,String(CharArray(n) {b.char}).dropLast(if(n>0)1 else 0))
        }
        return Frame(kind,number,rows)
    }
    private fun header(bodies:List<ByteArray>)=decode(bodies.first()).rows.map {it.name}

    @Test fun sidesEncodeTheirKindOwnerAndRebellionAndRejectInvalidCombinations() {
        assertEquals((42L shl 3) or 0L,TacticalCampMessages.Side(0,42,false).code)
        assertEquals((42L shl 3) or 1L,TacticalCampMessages.Side(0,42,true).code)
        assertEquals((10004L shl 3) or 2L or 1L,TacticalCampMessages.Side(1,10004,true).code)
        assertEquals(4L,TacticalCampMessages.Side(2,0,false).code);assertEquals(6L,TacticalCampMessages.Side(3,0,false).code)
        for(bad in listOf({TacticalCampMessages.Side(0,0,false)},{TacticalCampMessages.Side(1,0,true)},
            {TacticalCampMessages.Side(2,1,false)},{TacticalCampMessages.Side(2,0,true)},{TacticalCampMessages.Side(3,0,true)},
            {TacticalCampMessages.Side(4,1,false)}))assertFailsWith<IllegalArgumentException> {bad()}
    }

    @Test fun pagesAreOrderedBoundedAndCarryTheHeaderAndCursor() {
        // 42 and 43 (same native display) are separate sides; rebels, individuals and pirates keep their own kinds.
        val units=(1..125).associate {id->(1_000_000+id) to when(id%5) {
            0->TacticalCampMessages.Side(0,42,false);1->TacticalCampMessages.Side(0,43,false);2->TacticalCampMessages.Side(0,42,true)
            3->TacticalCampMessages.Side(1,10000+id,false);else->TacticalCampMessages.Side(2,0,false)}}
        val first=TacticalCampMessages.reply(query,10,7,viewer,units)
        assertEquals(1+6,first.size,"header + six batches of ten")
        val h=header(first)
        assertEquals(listOf("UC1","60","1000060","1","10","7",""),h.take(7))
        assertEquals(TacticalCampMessages.stamp(viewer,units).toString(),h[7]+h[8])
        assertEquals((42L shl 3).toString(),h[9])
        val rows=first.drop(1).map(::decode).also {f->assertEquals((0..5).toList(),f.map {it.number});assertTrue(f.all {it.kind==1})}
            .flatMap {it.rows}
        assertEquals((1_000_001..1_000_060).toList(),rows.map {it.id})
        val r=rows.single {it.id==1_000_002};assertEquals(listOf(0,1,"42"),listOf(r.kind,r.rank,r.name))
        val independent=rows.single {it.id==1_000_003};assertEquals(listOf(1,0,"10003"),listOf(independent.kind,independent.rank,independent.name))
        val pirate=rows.single {it.id==1_000_004};assertEquals(listOf(2,0,"0"),listOf(pirate.kind,pirate.rank,pirate.name))
        // The cursor is exclusive; the last page reports no more and keeps the cursor when nothing follows.
        val last=TacticalCampMessages.reply(query.copy(base=1_000_120),10,7,viewer,units)
        assertEquals(listOf("5","1000125","0"),header(last).subList(1,4))
        assertEquals(listOf("0","1000125","0"),header(TacticalCampMessages.reply(query.copy(base=1_000_125),10,7,viewer,units)).subList(1,4))
        assertEquals(1,TacticalCampMessages.reply(query.copy(base=1_000_125),10,7,viewer,units).size)
    }

    @Test fun theStampIsTheDocumentedHashAndOnlyFollowsUnitsAndSides() {
        val units=mapOf(9 to TacticalCampMessages.Side(0,43,false),3 to TacticalCampMessages.Side(2,0,false))
        val bytes=ByteBuffer.allocate(8+24).putLong(viewer.code).putInt(3).putLong(4L).putInt(9).putLong(43L shl 3).array()
        val expected=ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes),0,8).long and Long.MAX_VALUE
        assertEquals(expected,TacticalCampMessages.stamp(viewer,units))
        assertEquals(expected,TacticalCampMessages.stamp(viewer,units.toList().reversed().toMap()),"insertion order is irrelevant")
        assertNotEquals(expected,TacticalCampMessages.stamp(viewer,units+(9 to TacticalCampMessages.Side(0,43,true))),"rebellion")
        assertNotEquals(expected,TacticalCampMessages.stamp(TacticalCampMessages.Side(0,43,false),units),"viewer side")
        assertNotEquals(expected,TacticalCampMessages.stamp(viewer,units-3),"unit left")
    }

    @Test fun invalidQueriesAndOversizedRostersAreRejected() {
        val one=mapOf(1 to viewer)
        for(bad in listOf(query.copy(outfit=0),query.copy(outfit=65536),query.copy(base=-1),query.copy(mode=13)))
            assertFailsWith<IllegalArgumentException>("$bad") {TacticalCampMessages.reply(bad,10,7,viewer,one)}
        assertFailsWith<IllegalArgumentException> {TacticalCampMessages.reply(query,5000,7,viewer,one)}
        assertFailsWith<IllegalArgumentException> {TacticalCampMessages.reply(query,10,0,viewer,one)}
        assertFailsWith<IllegalArgumentException> {TacticalCampMessages.reply(query,10,7,viewer,mapOf(0 to viewer))}
        assertFailsWith<IllegalArgumentException> {TacticalCampMessages.reply(query,10,7,viewer,(1..601).associateWith {viewer})}
        assertEquals(1+6,TacticalCampMessages.reply(query,10,7,viewer,(1..600).associateWith {viewer}).size,"600 is allowed")
    }
}
