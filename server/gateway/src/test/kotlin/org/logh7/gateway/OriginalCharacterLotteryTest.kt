package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Random
import org.logh7.protocol.OriginalCharacterMessages
import kotlin.test.*

class OriginalCharacterLotteryTest {
    @TempDir lateinit var directory: Path
    private class TestClock(var time: Long = 1000) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(time)
    }
    @Test fun competingApplicationsSurviveRestartAndAllocateOneCharacterPerAccountWithoutDuplicates() {
        val clock = TestClock()
        val file = directory.resolve("characters.properties")
        val service = CharacterCreation(file, clock, 100, Random(5))
        service.apply("alpha", listOf(1001, 1002)); service.apply("beta", listOf(1001, 1002)); service.apply("gamma", listOf(1001))
        assertNull(service.character("alpha"))
        val restored = CharacterCreation(file, clock, 100, Random(5))
        assertEquals(listOf(1001L, 1002L), restored.entries("alpha"))
        clock.time += 100
        val characters = listOf("alpha", "beta", "gamma").mapNotNull(restored::character)
        assertEquals(2, characters.size); assertEquals(setOf(1001L, 1002L), characters.map { it.id }.toSet())
        assertTrue(characters.none { it.generated })
        assertTrue(restored.entries("alpha").isEmpty())
        val again = CharacterCreation(file, clock, 100, Random(5))
        for (account in listOf("alpha", "beta", "gamma")) assertEquals(restored.character(account), again.character(account))
        assertTrue(again.candidates("new").isEmpty())
        assertFails { again.apply("new", listOf(1001)) }
    }
    @Test fun rejectsUnknownOrDuplicateChoicesAndExistingOwners() {
        val service = CharacterCreation(clock = TestClock(), lotteryWindowMillis = 10)
        assertFails { service.apply("a", emptyList()) }
        assertFails { service.apply("a", listOf(1001, 1001)) }
        assertFails { service.apply("a", listOf(9999)) }
        service.apply("a", listOf(1001))
        service.apply("a", listOf(1002))
        assertEquals(listOf(1002L), service.entries("a"))
    }
    @Test fun fameRequirementIsCheckedOnServerRatherThanTrustingClientFiltering() {
        val candidate = OriginalCharacterMessages.localCandidates.first().copy(minimumFame = 10)
        val clock = TestClock()
        val service = CharacterCreation(clock = clock, lotteryWindowMillis = 10, originals = listOf(candidate), accountFame = mapOf("veteran" to 10))
        assertTrue(service.candidates("new").isEmpty())
        assertFails { service.apply("new", listOf(candidate.character.id)) }
        service.apply("veteran", listOf(candidate.character.id))
        clock.time += 10
        assertNotNull(service.character("veteran"))
        assertFails { service.apply("veteran", listOf(candidate.character.id)) }
    }
}
