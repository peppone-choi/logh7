package org.logh7.gateway

import kotlin.test.*
import org.junit.jupiter.api.Test
import org.logh7.protocol.CharacterMessages
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CharacterCreationTest {
    @TempDir lateinit var directory: Path
    private val request = CharacterMessages.Generate(0, 0, 2, 0, 0, "Alpha", "Pilot", 0, 0, 0, 0,
        List(8) { 0 }, 0, 0, 0, 0, 0, 0, "", 0)
    private fun decoded(bytes: ByteArray) = CharacterMessages.generate(bytes.copyOfRange(4, bytes.size))
    @Test fun reservationsBelongToAccountAndSurnameIsUnique() {
        val service = CharacterCreation()
        val first = decoded(service.accept("owner", request))
        assertTrue(first.id > 0); assertEquals(1, first.check)
        assertEquals(first.id, decoded(service.accept("owner", request.copy(surname = "Beta"))).id)
        assertFails { service.accept("other", request.copy(surname = "beta")) }
        assertFails { service.accept("owner", request.copy(power = 3)) }
    }
    @Test fun completesRegistrationPersistsAndRejectsCrossFactionOrOverspending() {
        val file = directory.resolve("characters.properties")
        val service = CharacterCreation(file)
        val reserved = decoded(service.accept("owner", request))
        val prepared = decoded(service.accept("owner", reserved.copy(category = 1, age = 18, birthMonth = 1, birthDay = 1, face = 1_000_001)))
        assertEquals(List(8) { 50 }, prepared.abilities)
        assertFails { service.accept("owner", prepared.copy(category = 2, abilities = List(8) { 89 }, bonus = 0)) }
        val allocated = decoded(service.accept("owner", prepared.copy(category = 2, abilities = listOf(60) + List(7) { 50 }, bonus = 0)))
        val named = decoded(service.accept("owner", allocated.copy(category = 3, shipName = "Test Ship")))
        val finished = decoded(service.accept("owner", named.copy(category = 4)))
        assertEquals(finished, service.character("owner"))
        assertContentEquals(CharacterMessages.generateReply(finished), service.accept("owner", finished))
        val restored = CharacterCreation(file)
        assertEquals(finished, restored.character("owner"))
        assertFails { restored.accept("owner", request.copy(power = 3, origin = 3)) }
        assertFails { restored.accept("other", request.copy(surname = "alpha")) }
    }
}
