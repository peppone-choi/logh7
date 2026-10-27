package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class CharacterInformationMessagesTest {
    private val character = CharacterMessages.Generate(4,10001,3,0,0,"한글日本ABC","Pilot",30,2,3,1000008,
        List(8) { 41 + it },0,0,0,7,0,0,"기함",1)
    private val base = WorldMessages.Base(1049,2487,1,0,"Odin",0,1f,1,0,0f,1f)
    private val profile = CharacterInformationMessages.Profile(character,2487,1049,10490003,0,listOf(0,195,190),
        LogisticsDirectoryMessages.Outfit(70000,1,9,base),listOf(CharacterInformationMessages.ChargedBase(190,base)))
    private fun ByteBuffer.u8() = get().toInt() and 255
    private fun ByteBuffer.string(): String {
        val count = u8()
        if (count == 0) return ""
        val chars = CharArray(count) { char }
        assertEquals('\u0000',chars.last())
        return String(chars,0,count-1)
    }
    private fun ByteBuffer.base() {
        assertEquals(1049,int); assertEquals(2487,short.toInt()); assertEquals(0,short.toInt()); assertEquals("Odin",string())
    }
    @Test fun dedicatedRecordFollowsTheCdReaderWithoutWorldCachePaddingOrCardTargets() {
        val b = ByteBuffer.wrap(CharacterInformationMessages.response(10001,profile))
        assertEquals(0,b.int); assertEquals(0x355,b.short.toInt()); assertEquals(10001,b.int)
        assertEquals(character.surname,b.string()); assertEquals(3,b.u8())
        assertEquals(0,b.u8()); assertEquals(0,b.u8()); assertEquals(0,b.u8())
        assertEquals(30 * CharacterMessages.AGE_SECONDS_PER_YEAR,b.int)
        assertEquals(2,b.u8()); assertEquals(3,b.u8()); assertEquals(1010001,b.int); assertEquals("기함",b.string())
        assertEquals(2487,b.int); assertEquals(1049,b.int); assertEquals(10490003,b.int); assertEquals(0,b.int)
        assertEquals(0,b.int); assertEquals(character.abilities,List(8) { b.u8() })
        assertEquals(0,b.u8()); assertEquals(100,b.u8()); assertEquals(7,b.short.toInt()); assertEquals("",b.string())
        assertEquals(1000008,b.int); assertEquals(0,b.int)
        assertEquals(3,b.u8()); assertEquals(listOf(0,195,190),List(3) { b.short.toInt() })
        assertEquals(1,b.u8()); assertEquals(70000,b.int); assertEquals(9,b.u8()); assertEquals(1,b.u8())
        assertEquals(1,b.u8()); b.base()
        assertEquals(1,b.u8()); assertEquals(190,b.short.toInt()); b.base()
        assertFalse(b.hasRemaining())
    }
    @Test fun queryIsStrictAndDeniedReplyContainsNoCharacterData() {
        val query = ByteBuffer.allocate(6).putShort(0x354).putInt(10001).array()
        assertEquals(10001,CharacterInformationMessages.query(query))
        assertFails { CharacterInformationMessages.query(query + 0) }
        assertFails { CharacterInformationMessages.query(query.copyOf(5)) }
        assertFails { CharacterInformationMessages.query(byteArrayOf(3,0x55,0,0,0,1)) }
        assertFails { CharacterInformationMessages.query(byteArrayOf(3,0x54,0,0,0,0)) }
        val denied = CharacterInformationMessages.response(10001,null)
        assertEquals(1,ByteBuffer.wrap(denied).int)
        assertEquals(10001,ByteBuffer.wrap(denied).getInt(6))
        // Every field is empty/zero except the explicit unknown rank, not a fabricated person.
        assertEquals(1,denied.drop(10).count { it != 0.toByte() })
        assertTrue(denied.drop(10).any { it == 255.toByte() })
    }
    @Test fun nativeCountsAndStringsFailBeforeProducingAnUnsafeReply() {
        CharacterInformationMessages.response(10001,profile.copy(cards=List(16) { 190 },
            charged=List(5) { CharacterInformationMessages.ChargedBase(190,base.copy(id=1049+it)) }))
        for (bad in listOf(profile.copy(cards=List(17) { 190 }),profile.copy(cards=listOf(261)),
            profile.copy(charged=List(6) { CharacterInformationMessages.ChargedBase(190,base.copy(id=1049+it)) }),
            profile.copy(character=character.copy(surname="x".repeat(13))),
            profile.copy(character=character.copy(surname="bad\u0000name")),
            profile.copy(character=character.copy(abilities=List(8) { 256 })),
            profile.copy(character=character.copy(id=10002)),profile.copy(charged=profile.charged+profile.charged))) {
            assertFails { CharacterInformationMessages.response(10001,bad) }
        }
    }
}
