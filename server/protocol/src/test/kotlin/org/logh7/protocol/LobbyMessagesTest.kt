package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class LobbyMessagesTest {
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
        assertEquals(listOf(2, 2, 1, 0, 1, 1), List(6) { input.get().toInt() })
        assertEquals(18 * 31_536_000, input.int)
        assertEquals(0, input.get().toInt()); assertEquals(character.abilities, List(8) { input.short.toInt() })
        assertContentEquals("Beta\u0000".toByteArray(Charsets.UTF_16BE), bytes.copyOfRange(input.position() + 1, input.position() + 11))
        assertEquals(18 * 31_536_000, ByteBuffer.wrap(BootstrapMessages.character(character), 14, 4).int)
        val original = LobbyMessages.characters(character.copy(generated = false))
        assertEquals(0, original[7 + session.size + 9].toInt())
    }
}
