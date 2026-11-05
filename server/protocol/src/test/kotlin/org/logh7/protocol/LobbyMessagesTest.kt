package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class LobbyMessagesTest {
    @Test fun multipleSessionsKeepDistinctIdsAndPerSessionChargedCharacterRecords() {
        val character=CharacterMessages.Generate(4,10001,2,0,0,"Beta","Pilot",18,1,1,
            1_000_001,List(8){50},0,0,0,20,3,0,"BetaShip",1)
        fun session(id:Int)=SessionEndingMessages.Session(id,1,"World$id","",123,
            listOf(2,3).map {SessionEndingMessages.PowerResult(it,0,id.toLong(),id.toLong(),null)},null)
        val sessions=listOf(session(1),session(7))
        val records=sessions.map(SessionEndingMessages::information)
        assertContentEquals(records[0]+records[1],LobbyMessages.sessions(sessions).copyOfRange(8,8+records.sumOf {it.size}))
        val entries=sessions.map {LobbyMessages.ChargedSession(character.copy(power=if(it.id==1)2 else 3),it)}
        val expected=entries.fold(byteArrayOf()) {bytes,entry->
            val single=LobbyMessages.characters(entry.character,entry.information);bytes+single.copyOfRange(7,single.size)
        }
        val actual=LobbyMessages.characters(entries)
        assertEquals(2,actual[6].toInt());assertContentEquals(expected,actual.copyOfRange(7,actual.size))
        assertContentEquals(LobbyMessages.noCharacters(),LobbyMessages.characters(emptyList()))
        assertFailsWith<IllegalArgumentException> {LobbyMessages.sessions(listOf(sessions[0],sessions[0]))}
        val stats=SessionEndingMessages.Statistics(1,1,1,1,1)
        val ending=SessionEndingMessages.PowerEnding("X".repeat(12),1,0,0,0,0,0,stats)
        val worst=(1..128).map {id->LobbyMessages.ChargedSession(character.copy(surname="X".repeat(12),
            givenName="X".repeat(12),shipName="X".repeat(12)),session(id).copy(name="X".repeat(12),beginDay="X".repeat(64),
                powers=listOf(2,3).map {SessionEndingMessages.PowerResult(it,0,1,1,ending)},ending=stats),
            SessionEndingMessages.Evaluation(1,1,1,1,1))}
        val maximum=LobbyMessages.characters(worst)
        assertTrue(((8+maximum.size+7)/8)*8+6<=Frames.MAX_PAYLOAD)
        assertFailsWith<IllegalArgumentException> {LobbyMessages.characters(worst+(129..255).map {id->
            worst[0].copy(information=worst[0].information!!.copy(id=id))})}
    }
    @Test fun chargedCharacterUsesSessionRecordAndAgeInSeconds() {
        assertContentEquals(LobbyMessages.noCharacters(), LobbyMessages.characters(null))
        val character = CharacterMessages.Generate(4, 10001, 2, 0, 0, "Beta", "Pilot", 18, 1, 1,
            1_000_001, listOf(60) + List(7) { 50 }, 0, 0, 0, 20, 3, 0, "BetaShip", 1)
        val bytes = LobbyMessages.characters(character)
        val session = LobbyMessages.sessions().copyOfRange(8, LobbyMessages.sessions().size)
        assertEquals(1, bytes[6].toInt())
        assertContentEquals(session, bytes.copyOfRange(7, 7 + session.size))
        val input = ByteBuffer.wrap(bytes).position(7 + session.size)
        assertEquals(0, input.get().toInt()); assertEquals(2, input.get().toInt()); assertEquals(1, input.get().toInt())
        assertEquals(10001, input.int)
        assertEquals(listOf(2, 0, 1, 0, 1, 1), List(6) { input.get().toInt() })
        assertEquals(18 * 31_536_000, input.int)
        assertEquals(0, input.get().toInt()); assertEquals(character.abilities, List(8) { input.short.toInt() })
        assertContentEquals("Beta\u0000".toByteArray(Charsets.UTF_16BE), bytes.copyOfRange(input.position() + 1, input.position() + 11))
        assertEquals(18 * 31_536_000, ByteBuffer.wrap(BootstrapMessages.character(character), 14, 4).int)
        val original = LobbyMessages.characters(character.copy(generated = false))
        assertEquals(0, original[7 + session.size + 9].toInt())
    }
    @Test fun endedChargedCharacterCarriesFinalSessionAndPersonalResultWithoutInventingNextSession() {
        val character=CharacterMessages.Generate(4,10001,3,0,0,"Beta","Pilot",18,1,1,
            1_000_001,List(8){50},0,0,0,20,3,0,"BetaShip",1)
        val stats=SessionEndingMessages.Statistics(7,2,900_000,31,42)
        val ending=SessionEndingMessages.PowerEnding("",0,3,0,0,255,1,stats)
        val session=SessionEndingMessages.Session(1,3,"Final","",123456,
            listOf(SessionEndingMessages.PowerResult(2,0,2,9,ending),SessionEndingMessages.PowerResult(3,0,1,9,ending)),stats)
        val evaluation=SessionEndingMessages.Evaluation(10,11,12,13,14)
        val bytes=LobbyMessages.characters(character,session,evaluation)
        val input=ByteBuffer.wrap(bytes).position(6)
        assertEquals(1,input.get().toInt());assertEquals(1,input.short.toInt());assertEquals(3,input.get().toInt())
        val nameLength=input.get().toInt();assertEquals(6,nameLength)
        assertEquals("Final\u0000",CharArray(nameLength){input.char}.concatToString())
        assertEquals(0,input.get().toInt());assertEquals(123456,input.int)
        // Final charged-character footer is a presence byte followed by four u32 and one u8.
        input.position(bytes.size-18);assertEquals(1,input.get().toInt())
        assertEquals(listOf(10,11,12,13),List(4){input.int});assertEquals(14,input.get().toInt());assertFalse(input.hasRemaining())
        val information=SessionEndingMessages.information(session)
        assertContentEquals(information,bytes.copyOfRange(7,7+information.size))
        assertContentEquals(byteArrayOf(0,2,1),bytes.copyOfRange(7+information.size,10+information.size))
        assertContentEquals(byteArrayOf(1)+information+byteArrayOf(0,0,0),
            LobbyMessages.characters(null,session).let {it.copyOfRange(6,it.size)})
        assertFailsWith<IllegalArgumentException>{LobbyMessages.characters(null,session,evaluation)}
        assertFailsWith<IllegalArgumentException>{LobbyMessages.characters(character,evaluation=evaluation)}
    }
}
