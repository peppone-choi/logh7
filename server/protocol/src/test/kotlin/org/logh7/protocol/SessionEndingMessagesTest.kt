package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class SessionEndingMessagesTest {
    private val character=CharacterMessages.Generate(4,10000,2,0,0,"Alpha","Pilot",18,1,1,
        1_000_001,listOf(60)+List(7){50},0,0,0,20,3,0,"AlphaShip",1)
    private val evaluation=SessionEndingMessages.Evaluation(0x01020304,0x11223344,0x89abcdef,0xffffffffL,128)
    private val statistics=SessionEndingMessages.Statistics(0x1234,0xabcd,0xffffffffL,0x10203040,0x50607080)
    private val ending=SessionEndingMessages.PowerEnding("Yang",195,7,90,1,0,1,statistics)
    private val session=SessionEndingMessages.Session(0x2345,3,"Test","2026-10-29",0x87654321,
        listOf(SessionEndingMessages.PowerResult(3,801,23,45,ending),
            SessionEndingMessages.PowerResult(2,492,67,89,null)),statistics)
    private fun bytes(e:SessionEndingMessages.Evaluation=evaluation,s:SessionEndingMessages.Session=session)=
        SessionEndingMessages.ended(character,e,s,840,920)
    private fun ByteBuffer.u8()=get().toInt() and 255
    private fun ByteBuffer.u16()=short.toInt() and 65535
    private fun ByteBuffer.u32()=int.toLong() and 0xffffffffL
    private fun ByteBuffer.string():String {
        val count=u8();if(count==0)return ""
        val chars=CharArray(count){char};assertEquals('\u0000',chars.last())
        return String(chars,0,count-1)
    }
    private fun ByteBuffer.statistics() {
        assertEquals(0x1234,u16());assertEquals(0xabcd,u16());assertEquals(0xffffffffL,u32())
        assertEquals(0x10203040L,u32());assertEquals(0x50607080L,u32())
    }
    @Test fun nativeReaderOrderPreservesUnsignedFieldsCountsAndOptionalRecordsWithoutPadding() {
        val message=bytes();val input=ByteBuffer.wrap(message)
        assertEquals(0,input.int);assertEquals(0x035a,input.u16())
        val prefix=BootstrapMessages.character(character,840,920)
        assertContentEquals(prefix.copyOfRange(6,prefix.size),message.copyOfRange(6,prefix.size))
        input.position(prefix.size)
        assertEquals(0x01020304L,input.u32());assertEquals(0x11223344L,input.u32())
        assertEquals(0x89abcdefL,input.u32());assertEquals(0xffffffffL,input.u32());assertEquals(128,input.u8())
        assertEquals(0x2345,input.u16());assertEquals(3,input.u8());assertEquals("Test",input.string())
        assertEquals("2026-10-29",input.string());assertEquals(0x87654321L,input.u32())
        assertEquals(2,input.u8());assertEquals(492,input.int);assertEquals(67,input.int);assertEquals(89,input.int)
        assertEquals(0,input.u8())
        assertEquals(3,input.u8());assertEquals(801,input.int);assertEquals(23,input.int);assertEquals(45,input.int)
        assertEquals(1,input.u8());assertEquals("Yang",input.string());assertEquals(195,input.u16())
        assertContentEquals(byteArrayOf(7,90,1,0,1),ByteArray(5){input.get()});input.statistics()
        assertEquals(1,input.u8());input.statistics();assertFalse(input.hasRemaining())
    }
    @Test fun emptyOptionalEndingsAndEmptyStringsDoNotEmitFixedNativeArrays() {
        val simple=session.copy(name="",beginDay="",powers=session.powers.map {it.copy(ending=null)},ending=null)
        val message=bytes(s=simple);val prefix=BootstrapMessages.character(character,840,920)
        val suffix=message.copyOfRange(prefix.size,message.size)
        // Awards 17 + session 2/1/1/1/4 + two powers (1/4/4/4/1) + optional-total count 1.
        assertEquals(55,suffix.size);assertEquals(0,suffix.last().toInt())
        assertEquals(0,suffix[20].toInt());assertEquals(0,suffix[21].toInt())
    }
    @Test fun nativeStringLimitsAreUtf16UnitsIncludingTheTerminator() {
        bytes(s=session.copy(name="한".repeat(12),beginDay="일".repeat(64)))
        bytes(s=session.copy(powers=session.powers.map {it.copy(ending=ending.copy(leader="A".repeat(12)))}))
        for(s in listOf(session.copy(name="a".repeat(13)),session.copy(beginDay="a".repeat(65)),
            session.copy(name="a\u0000b"),session.copy(powers=session.powers.map {it.copy(ending=ending.copy(leader="a".repeat(13)))}))) {
            assertFailsWith<IllegalArgumentException>{bytes(s=s)}
        }
    }
    @Test fun invalidWidthsAndDuplicateOrMissingPowersAreRejected() {
        for(s in listOf(session.copy(id=-1),session.copy(id=65536),session.copy(state=256),
            session.copy(gameSeconds=0x100000000),session.copy(powers=session.powers.take(1)),
            session.copy(powers=List(2){session.powers[0]}),session.copy(ending=statistics.copy(planets=65536)),
            session.copy(ending=statistics.copy(ships=-1)),session.copy(powers=session.powers.map {it.copy(characters=0x100000000)}),
            session.copy(powers=session.powers.map {it.copy(ending=ending.copy(winType=256))}))) {
            assertFailsWith<IllegalArgumentException>{bytes(s=s)}
        }
        assertFailsWith<IllegalArgumentException>{bytes(e=evaluation.copy(finalFame=-1))}
        assertFailsWith<IllegalArgumentException>{bytes(e=evaluation.copy(friendship=256))}
    }
}
