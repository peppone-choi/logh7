package org.logh7.gateway

import kotlin.test.*
import org.junit.jupiter.api.Test
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.OriginalCharacterMessages
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CharacterCreationTest {
    @TempDir lateinit var directory: Path
    private val request = CharacterMessages.Generate(0, 0, 2, 0, 0, "Alpha", "Pilot", 0, 0, 0, 0,
        List(8) { 0 }, 0, 0, 0, 0, 0, 0, "", 0)
    private fun decoded(bytes: ByteArray) = CharacterMessages.generate(bytes.copyOfRange(4, bytes.size))
    @Test fun newCharactersDefaultToTheirFactionsStandardBattleship() {
        val service = CharacterCreation(random = object : java.util.Random() {
            override fun nextInt(bound: Int): Int = error("Fixed grant must not roll")
        })
        for (power in 2..3) {
            val reserved = decoded(service.accept("pilot$power", request.copy(power = power,
                origin = if (power == 2) 0 else 3, surname = "Pilot$power")))
            val prepared = decoded(service.accept("pilot$power", reserved.copy(category = 1,
                age = 18, birthMonth = 1, birthDay = 1, face = 1_000_001)))
            assertEquals(0, prepared.shipType)
            assertEquals(if (power == 2) 0 else 89, prepared.shipKind)
        }
    }
    @Test fun grantPolicyRejectsUnknownCrossFactionEmptyAndDuplicateCandidates() {
        for (kinds in listOf(emptyList(), listOf(199), listOf(89), listOf(0, 0))) {
            assertFailsWith<IllegalArgumentException> {
                CharacterCreation(starterKinds = mapOf(2 to kinds, 3 to listOf(89)))
            }
        }
    }
    @Test fun shipsVaryWithinFactionAndAreDrawnOnlyOnceThenPersisted() {
        for (power in 2..3) {
            var draws = 0
            val random = object : java.util.Random() {
                override fun nextInt(bound: Int) = (draws++ % bound)
            }
            val file = directory.resolve("ships-$power.properties")
            val service = CharacterCreation(file, random = random,
                starterKinds = mapOf(2 to listOf(0, 2, 3), 3 to listOf(89, 90, 93)))
            val kinds = mutableListOf<Int>()
            repeat(3) { index ->
                val account = "pilot$index"
                val reserved = decoded(service.accept(account, request.copy(power = power,
                    origin = if (power == 2) 0 else 3, surname = "Pilot$index")))
                val prepared = decoded(service.accept(account, reserved.copy(category = 1,
                    age = 18, birthMonth = 1, birthDay = 1, face = 1_000_001)))
                val repeated = decoded(service.accept(account, prepared))
                assertEquals(prepared.shipKind, repeated.shipKind)
                assertEquals(index + 1, draws)
                val allocated = decoded(service.accept(account, repeated.copy(category = 2, shipKind = 199)))
                assertEquals(prepared.shipKind, allocated.shipKind) // client cannot substitute another ship
                val named = decoded(service.accept(account, allocated.copy(category = 3, shipName = "Ship$index")))
                val finished = decoded(service.accept(account, named.copy(category = 4)))
                kinds += finished.shipKind
                assertEquals(finished, CharacterCreation(file).character(account))
            }
            assertEquals(if (power == 2) listOf(0, 2, 3) else listOf(89, 90, 93), kinds)
        }
    }
    @Test fun legacyPlaceholderMigrationKeepsItsRecordedClassAndDoesNotRerollOriginalCharacters() {
        for (power in 2..3) {
            val file = directory.resolve("legacy-ships-$power.properties")
            val legacy = request.copy(category = 4, id = 10000, power = power, age = 18, birthMonth = 1, birthDay = 1,
                face = 1_000_001, shipType = 3, shipKind = 0, shipName = "Legacy", check = 1)
            fun encode(value: CharacterMessages.Generate) = java.util.HexFormat.of()
                .formatHex(CharacterMessages.generateReply(value).drop(4).toByteArray())
            java.nio.file.Files.writeString(file, "pilot=${encode(legacy)}\noriginal=original:${encode(legacy.copy(id = 1234))}\n")
            val originalBytes = java.nio.file.Files.readAllBytes(file)
            val migrated = CharacterCreation(file)
            assertEquals(if (power == 2) 3 else 93, migrated.character("pilot")!!.shipKind)
            assertEquals(3, migrated.character("pilot")!!.shipType)
            assertEquals(0, migrated.character("original")!!.shipKind)
            assertEquals(migrated.character("pilot"), CharacterCreation(file).character("pilot"))
            assertContentEquals(originalBytes, java.nio.file.Files.readAllBytes(file))
        }
    }
    @Test fun originalSurnameRejectionDoesNotReserveAccountOrConsumeId() {
        val service = CharacterCreation()
        val candidates = service.candidates("owner")
        assertTrue(candidates.isNotEmpty())
        val failure = assertFailsWith<IllegalArgumentException> { service.accept("owner", request.copy(surname = "ヤン")) }
        assertEquals("Surname already reserved", failure.message)
        assertEquals(candidates, service.candidates("owner"))
        assertNull(service.character("owner"))
        assertEquals(10_000L, decoded(service.accept("owner", request)).id)
    }
    @Test fun halfWidthOriginalSurnameIsRejected() {
        val service = CharacterCreation()
        assertFailsWith<IllegalArgumentException> { service.accept("owner", request.copy(surname = "ｷﾙﾋｱｲｽ")) }
        assertEquals(10_000L, decoded(service.accept("owner", request)).id)
    }
    @Test fun injectedOriginalSurnameIsReservedEvenWhenCandidateIsIneligible() {
        val candidate = OriginalCharacterMessages.Candidate(request.copy(category = 4, id = 1234, surname = "Gamma"), minimumFame = 10)
        val service = CharacterCreation(originals = listOf(candidate))
        assertTrue(service.candidates("owner").isEmpty())
        assertFailsWith<IllegalArgumentException> { service.accept("owner", request.copy(surname = "gamma")) }
        val accepted = decoded(service.accept("owner", request.copy(surname = "Delta")))
        assertEquals("Delta", accepted.surname)
        assertEquals(10_000L, accepted.id)
    }
    @Test fun matchingOnlyOriginalGivenNameIsAllowed() {
        val service = CharacterCreation()
        val accepted = decoded(service.accept("owner", request.copy(givenName = "ウェンリー")))
        assertEquals("Alpha", accepted.surname)
        assertEquals("ウェンリー", accepted.givenName)
    }
    @Test fun rejectedOriginalSurnameChangePreservesExistingReservation() {
        val service = CharacterCreation()
        val reserved = decoded(service.accept("owner", request))
        assertFailsWith<IllegalArgumentException> { service.accept("owner", request.copy(surname = "ヤン")) }
        assertFailsWith<IllegalArgumentException> { service.accept("other", request.copy(surname = "alpha")) }
        val prepared = decoded(service.accept("owner", reserved.copy(category = 1, age = 18, birthMonth = 1, birthDay = 1, face = 1_000_001)))
        assertEquals(reserved.id, prepared.id)
        assertEquals("Alpha", prepared.surname)
        assertEquals(10_001L, decoded(service.accept("other", request.copy(surname = "Beta"))).id)
    }
    @Test fun previouslySavedOriginalSurnameCollisionStillLoads() {
        val file = directory.resolve("legacy-characters.properties")
        val service = CharacterCreation(file, originals = emptyList())
        val reserved = decoded(service.accept("owner", request.copy(surname = "ヤン")))
        val prepared = decoded(service.accept("owner", reserved.copy(category = 1, age = 18, birthMonth = 1, birthDay = 1, face = 1_000_001)))
        val allocated = decoded(service.accept("owner", prepared.copy(category = 2)))
        val named = decoded(service.accept("owner", allocated.copy(category = 3, shipName = "Legacy Ship")))
        val finished = decoded(service.accept("owner", named.copy(category = 4)))
        val restored = CharacterCreation(file)
        assertEquals(finished, restored.character("owner"))
        assertFailsWith<IllegalArgumentException> { restored.accept("new", request.copy(surname = "ヤン")) }
        assertEquals(10_001L, decoded(restored.accept("new", request)).id)
    }
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
