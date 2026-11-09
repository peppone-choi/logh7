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
import org.logh7.engine.CountryId
import org.logh7.engine.OriginalCandidateEdit
import org.logh7.engine.OriginalCandidateProfile
import org.logh7.engine.OriginalStartingOffice
import org.logh7.engine.PublishedOriginalCandidates
import org.logh7.engine.ShipStyleId
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
    @Test fun awardedFlagshipNameDoesNotCollideWithRegisteredOrPendingShips() {
        val clock = TestClock()
        val file = directory.resolve("ship-names.properties")
        val service = CharacterCreation(file, clock, 100)
        fun nameShip(account: String, name: String): CharacterMessages.Generate {
            fun step(request: CharacterMessages.Generate) =
                CharacterMessages.generate(service.accept(account, request).let { it.copyOfRange(4, it.size) })
            val reserved = step(CharacterMessages.Generate(0, 0, 2, 0, 0, account, "Pilot", 0, 0, 0, 0,
                List(8) { 0 }, 0, 0, 0, 0, 0, 0, "", 0))
            val prepared = step(reserved.copy(category = 1, age = 18, birthMonth = 1, birthDay = 1, face = 1_000_001))
            val allocated = step(prepared.copy(category = 2))
            return step(allocated.copy(category = 3, shipName = name))
        }
        val registered = nameShip("Alpha", "LOCAL 1001")
        service.accept("Alpha", registered.copy(category = 4))
        val pending = nameShip("Beta", "Local 1001-1")
        service.apply("winner", listOf(1001))
        clock.time += 100

        assertEquals("Local 1001-2", service.character("winner")?.shipName)
        service.accept("Beta", pending.copy(category = 4))
        val restored = CharacterCreation(file, clock)
        assertEquals("LOCAL 1001", restored.character("Alpha")?.shipName)
        assertEquals("Local 1001-1", restored.character("Beta")?.shipName)
        assertEquals("Local 1001-2", restored.character("winner")?.shipName)
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
        assertEquals(listOf(10,20),service.candidates("empire").map {it.character.rank})
        assertEquals(10,CharacterCreation.originalDefinitions().single {it.id==1001L}.profile.rank)
        service.apply("empire",listOf(1001));service.apply("alliance",listOf(1002));clock.time+=10
        val awarded=listOf("empire","alliance").map {checkNotNull(service.character(it))}
        assertEquals(listOf(26,100074),awarded.map {it.face}) // reviewed npc-face-matches.json slots
        assertEquals(listOf(3,93),awarded.map {it.shipKind}) // native faction destroyer kinds
        assertEquals(listOf(10,20),awarded.map {it.rank}) // manual p.60 confirms Kircheis only
        for(character in awarded) {
            assertFalse(character.generated)
            assertEquals(character.power,Flagships.find(character.shipKind)?.power)
            assertEquals(character.shipType,Flagships.find(character.shipKind)?.type)
        }
        val reloaded=CharacterCreation(file,clock)
        assertEquals(awarded,listOf("empire","alliance").map {reloaded.character(it)})
    }
    @Test fun onlyTheExplicitlyBoundAwardRetainsItsStartingOfficeAfterRestart() {
        val clock = TestClock()
        val file = directory.resolve("starting-office.properties")
        val country = CountryId(2)
        val office = OriginalStartingOffice.REINHARD_FLEET_2
        val data = OriginalCandidateProfile("Reinhard", "Fixture", 26, 5, List(8) { 50 })
        val published = PublishedOriginalCandidates()
        published.publish(listOf(OriginalCandidateEdit(1003, 1, data.copy(startingOffice = office), country),
            OriginalCandidateEdit(1004, 1, data, country)))
        val selection = CharacterCreation.CountrySelection(country, 2, ShipStyleId("empire"))
        val service = CharacterCreation(file, clock, 10, publishedOriginals = published)
        service.apply("marked", listOf(1003), selection)
        service.apply("unmarked", listOf(1004), selection)
        clock.time += 10
        assertEquals(office, service.profiles().getValue("marked").startingOffice)
        val restored = CharacterCreation(file, clock)
        assertEquals(office, restored.profiles().getValue("marked").startingOffice)
        assertNull(restored.profiles().getValue("unmarked").startingOffice)
    }

    @Test fun oldOriginalPlaceholdersAreDisplayedConsistentlyWithoutRewritingTheArchive() {
        val file=directory.resolve("legacy-originals.properties")
        val properties=Properties()
        val legacy=OriginalCharacterMessages.localCandidates.map {it.character.copy(face=1000001,shipKind=0,rank=20,generated=false)}
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
