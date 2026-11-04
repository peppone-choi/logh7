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
import org.logh7.protocol.CharacterMessages
import org.logh7.engine.Flagships
import java.nio.file.Files
import java.util.Properties
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
    @Test fun awardedOriginalsKeepReviewedPortraitsAndFactionHullKindsAcrossRestart() {
        val clock=TestClock()
        val file=directory.resolve("portraits.properties")
        val service=CharacterCreation(file,clock,10,Random(5))
        service.apply("empire",listOf(1001));service.apply("alliance",listOf(1002));clock.time+=10
        val awarded=listOf("empire","alliance").map {checkNotNull(service.character(it))}
        assertEquals(listOf(26,100074),awarded.map {it.face}) // reviewed npc-face-matches.json slots
        assertEquals(listOf(3,93),awarded.map {it.shipKind}) // native faction destroyer kinds
        for(character in awarded) {
            assertFalse(character.generated)
            assertEquals(character.power,Flagships.find(character.shipKind)?.power)
            assertEquals(character.shipType,Flagships.find(character.shipKind)?.type)
        }
        val reloaded=CharacterCreation(file,clock)
        assertEquals(awarded,listOf("empire","alliance").map {reloaded.character(it)})
    }
    @Test fun oldOriginalPlaceholdersAreDisplayedConsistentlyWithoutRewritingTheArchive() {
        val file=directory.resolve("legacy-originals.properties")
        val properties=Properties()
        val legacy=OriginalCharacterMessages.localCandidates.map {it.character.copy(face=1000001,shipKind=0,generated=false)}
        legacy.forEachIndexed {i,c->
            val encoded=CharacterMessages.generateReply(c)
            properties.setProperty("legacy$i","original:"+java.util.HexFormat.of().formatHex(encoded.copyOfRange(4,encoded.size)))
        }
        Files.newBufferedWriter(file).use {properties.store(it,"Legacy originals")}
        val before=Files.readAllBytes(file)
        val service=CharacterCreation(file)
        val restored=legacy.indices.map {checkNotNull(service.character("legacy$it"))}
        assertEquals(listOf(26,100074),restored.map {it.face})
        assertEquals(listOf(3,93),restored.map {it.shipKind})
        restored.zip(legacy).forEach {(actual,old)->
            assertEquals(old.id,actual.id);assertEquals(old.power,actual.power)
            assertEquals(old.rank,actual.rank);assertEquals(old.abilities,actual.abilities)
            assertEquals(old.age,actual.age);assertFalse(actual.generated)
        }
        assertContentEquals(before,Files.readAllBytes(file))
    }
    @Test fun displayCorrectionPreservesGeneratedCharactersAndUnrecognizedOriginalIdentities() {
        val source=OriginalCharacterMessages.localCandidates.first().character
        val generated=source.copy(face=1000001,shipKind=3,generated=true)
        val service=CharacterCreation()
        assertTrue(service.carry("player",generated))
        assertEquals(generated,service.character("player"))
        val lookalike=source.copy(surname="다른",face=1000001,shipKind=0,generated=false)
        assertEquals(lookalike,KoreanDisplayNames.original(lookalike))
        assertEquals(27,KoreanDisplayNames.original(source.copy(face=27)).face)
        assertEquals(0,KoreanDisplayNames.original(source.copy(shipType=0,shipKind=0)).shipKind)
    }
}
