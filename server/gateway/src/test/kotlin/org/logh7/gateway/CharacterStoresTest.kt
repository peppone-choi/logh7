package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.CharacterDeletionResult
import org.logh7.engine.CountryId
import org.logh7.engine.Navigator
import org.logh7.engine.OfficeHolding
import org.logh7.engine.OutfitKind
import org.logh7.engine.OutfitStock
import org.logh7.engine.OutfitWarehouse
import org.logh7.engine.PlanetStock
import org.logh7.engine.PlanetStocks
import org.logh7.engine.SessionSnapshot
import org.logh7.engine.StrategicPerson
import org.logh7.engine.StrategicState
import org.logh7.engine.WorldSnapshot
import org.logh7.protocol.CharacterMessages
import java.nio.file.Path
import java.nio.file.Files
import java.util.Properties
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class CharacterStoresTest {
    @TempDir lateinit var directory: Path

    @Test fun lobbyDeletionUsesOnlyTheAuthenticatedAccountsSelectedProfileAndWaitsForCommit() {
        val profile = CharacterMessages.Generate(4, 10000, 2, 0, 0, "Alpha", "Pilot", 18, 1, 1,
            1_000_001, List(8) { 50 }, 0, 0, 0, 13, 3, 3, "Ship", 1)
        val store = CharacterStores(null).get(1)
        assertTrue(store.carry("pilot", profile))
        val commit = CompletableFuture<CharacterDeletionResult>()
        var requests = 0
        val stock = object : GameStock {
            override fun snapshot(generation: Long): CompletableFuture<PlanetStocks> = error("Unexpected snapshot")
            override fun deleteGeneratedCharacter(account: String, generation: Long, character: Int, registeredRank: Int):
                CompletableFuture<CharacterDeletionResult> {
                assertEquals("pilot", account)
                assertEquals(1L, generation)
                assertEquals(10000, character)
                assertEquals(profile.rank, registeredRank)
                requests++
                return commit
            }
        }
        assertEquals(CharacterDeletionResult.INELIGIBLE, deleteLobbyCharacter("other", 1, 10000, 1, store, stock).join())
        assertEquals(CharacterDeletionResult.INELIGIBLE, deleteLobbyCharacter("pilot", 2, 10000, 1, store, stock).join())
        assertEquals(CharacterDeletionResult.INELIGIBLE, deleteLobbyCharacter("pilot", 1, 10001, 1, store, stock).join())
        val pending = deleteLobbyCharacter("pilot", 1, 10000, 1, store, stock)
        assertEquals(1, requests)
        assertEquals(profile, store.character("pilot"))
        commit.complete(CharacterDeletionResult.DELETED)
        assertEquals(CharacterDeletionResult.DELETED, pending.join())
        assertNull(store.character("pilot"))
    }

    @Test fun voluntaryDeletionCanBeReplayedWithoutRemovingAReplacementOrRecordingACasualty() {
        val file = directory.resolve("voluntary-deletion.properties")
        val profile = CharacterMessages.Generate(4, 10000, 2, 0, 0, "Alpha", "Pilot", 18, 1, 1,
            1_000_001, List(8) { 50 }, 0, 0, 0, 13, 3, 3, "Ship", 1)
        val store = CharacterStores(file).get(1)
        assertTrue(store.carry("pilot", profile))
        assertFalse(store.reconcileVoluntaryDeletion("pilot", setOf(10001)))
        assertTrue(store.reconcileVoluntaryDeletion("pilot", setOf(10000)))

        val reopened = CharacterStores(file).get(1)
        assertTrue(reopened.fameParticipants().first.isEmpty())
        assertTrue(reopened.fameParticipants().second.isEmpty())
        val request = reopened.accept("pilot", profile.copy(category = 0, id = 0))
        assertEquals(10001L, CharacterMessages.generate(request.copyOfRange(4, request.size)).id)
        assertTrue(reopened.carry("pilot", profile.copy(id = 10001)))
        assertFalse(reopened.reconcileVoluntaryDeletion("pilot", setOf(10000)))
        assertEquals(10001L, reopened.fameParticipants().first.getValue("pilot").character.id)

        val session = SessionSnapshot(1, 0, 0, 0, emptyMap(), 0, 0, null)
        val stocks = PlanetStocks(1, 0, emptyMap(), deletedCharacters = mapOf("pilot" to setOf(10020)))
        val world = WorldSnapshot(0, 0, session, planetStocks = stocks)
        val unpruned = CharacterStores(null).get(1)
        assertTrue(unpruned.carry("pilot", profile.copy(id = 10020)))
        assertTrue(WorldCharacters.profiles(unpruned.profiles(), world).isEmpty())
        assertEquals(unpruned.profiles(), WorldCharacters.profiles(unpruned.profiles(),
            world.copy(session = session.copy(generation = 2))))
        val recovered = CharacterStores(null, worldSnapshot = { world }).get(1)
        val next = recovered.accept("pilot", profile.copy(category = 0, id = 0))
        assertEquals(10021L, CharacterMessages.generate(next.copyOfRange(4, next.size)).id)
    }

    @Test fun retirementAndLegacyWorldHistoryBothPreventGeneratedIdReuse() {
        val file = directory.resolve("retirement.properties")
        val profile = CharacterMessages.Generate(4, 10000, 2, 0, 0, "Alpha", "Pilot", 18, 1, 1,
            1_000_001, List(8) { 50 }, 0, 0, 0, 13, 3, 3, "Ship", 1)
        val store = CharacterStores(file).get(1)
        assertTrue(store.carry("pilot", profile))
        assertTrue(store.retire("pilot", profile.id))
        assertTrue(Properties().also { Files.newBufferedReader(file).use(it::load) }.isEmpty)
        val request = profile.copy(category = 0, id = 0)
        fun reserved(store: CharacterCreation): Long {
            val reply = store.accept("pilot", request)
            return CharacterMessages.generate(reply.copyOfRange(4, reply.size)).id
        }
        assertEquals(10_001L, reserved(CharacterStores(file).get(1)))

        val session = SessionSnapshot(1, 0, 0, 0, emptyMap(), 0, 0, null)
        val stocks = PlanetStocks(1, 0, emptyMap(),
            strategic = StrategicState(people = mapOf(10040 to StrategicPerson(deceasedAt = 1))))
        val snapshot = WorldSnapshot(0, 0, session, planetStocks = stocks)
        val legacyFile = Files.writeString(directory.resolve("legacy-empty.properties"), "# LOGH7 local characters\n")
        val legacy = CharacterStores(legacyFile, worldSnapshot = { snapshot })
        assertEquals(10_041L, reserved(legacy.get(1)))
        assertEquals(10_000L, reserved(legacy.get(2)))

        val active=Navigator(1,10041,CountryId(2),2487,1000,1000,1000)
        val navigated=snapshot.copy(navigators=mapOf("live" to active,"stale" to active.copy(generation=2,characterId=10099)))
        assertEquals(10_042L,reserved(CharacterStores(null,worldSnapshot={navigated}).get(1)))
        val orphanedWorld=navigated.copy(
            planetStocks=stocks.copy(bases=mapOf(1049 to PlanetStock(1049,2,0,0,0,0)),
                strategic=stocks.strategic.copy(offices=listOf(OfficeHolding(150,1049,10042)))))
        val memory=CharacterStores(null,worldSnapshot={orphanedWorld})
        assertEquals(10_043L,reserved(memory.get(1)))
        assertEquals(10_000L,reserved(memory.get(2)))
    }

    @Test fun worldOnlyOriginalsAreUnavailableInTheirOwnGeneration() {
        val session = SessionSnapshot(1, 0, 0, 0, emptyMap(), 0, 0, null)
        val navigator = Navigator(1, 1001, CountryId(2), 2487, 1000, 1000, 1000)
        val stocks = PlanetStocks(1, 0, emptyMap(),
            strategic = StrategicState(people = mapOf(1002 to StrategicPerson())))
        val snapshot = WorldSnapshot(0, 0, session, navigators = mapOf("world-only" to navigator), planetStocks = stocks)
        val stores = CharacterStores(null, worldSnapshot = { snapshot })
        assertTrue(stores.get(1).candidates("applicant").isEmpty())
        assertFailsWith<IllegalArgumentException> { stores.get(1).apply("applicant", listOf(1001)) }
        assertEquals(setOf(1001L, 1002L), stores.get(2).candidates("applicant").map { it.character.id }.toSet())
    }

    @Test fun settlementAfterRestartSkipsWorldOccupiedPreference() {
        val file = directory.resolve("pending-originals.properties")
        val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        CharacterCreation(file, clock = clock, lotteryWindowMillis = 10).apply("applicant", listOf(1001, 1002))
        val session = SessionSnapshot(1, 0, 0, 0, emptyMap(), 0, 0, null)
        val navigator = Navigator(1, 1001, CountryId(2), 2487, 1000, 1000, 1000)
        val snapshot = WorldSnapshot(0, 0, session, navigators = mapOf("world-only" to navigator))
        val store = CharacterStores(file, worldSnapshot = { snapshot }).get(1)
        assertEquals(1002L, store.character("applicant")?.id)
        assertEquals(1002L, CharacterStores(file, worldSnapshot = { snapshot }).get(1).character("applicant")?.id)
    }

    @Test fun officeOnlyOriginalsAreUnavailableAtListingApplicationAndSettlement() {
        val session = SessionSnapshot(1, 0, 0, 0, emptyMap(), 0, 0, null)
        val stock = PlanetStocks(1, 0, mapOf(1049 to PlanetStock(1049,2,0,0,0,0)),
            outfits = mapOf(2 to OutfitStock(2,2,OutfitKind.FLEET,1049,20,aideId=1001)),
            warehouses = mapOf(20 to OutfitWarehouse(20,2,OutfitKind.FLEET,1049,2)))
        val occupied = stock.copy(strategic = StrategicState(offices = listOf(OfficeHolding(150,1049,1002))))
        val stores = CharacterStores(null, worldSnapshot = { WorldSnapshot(0,0,session,planetStocks=occupied) })
        assertTrue(stores.get(1).candidates("applicant").isEmpty())
        for(id in listOf(1001L,1002L))assertFailsWith<IllegalArgumentException> { stores.get(1).apply("applicant",listOf(id)) }
        assertEquals(setOf(1001L,1002L),stores.get(2).candidates("applicant").map { it.character.id }.toSet())

        val file=directory.resolve("pending-office-originals.properties")
        val clock=Clock.fixed(Instant.EPOCH,ZoneOffset.UTC)
        CharacterCreation(file,clock=clock,lotteryWindowMillis=10).apply("applicant",listOf(1001,1002))
        val reopened=CharacterStores(file,worldSnapshot={WorldSnapshot(0,0,session,planetStocks=stock)}).get(1)
        assertEquals(1002L,reopened.character("applicant")?.id)
    }

    @Test fun newGenerationStartsEmptyAndPreviousCharacterFileRemainsByteIdentical() {
        val file = directory.resolve("characters.properties")
        val character = CharacterMessages.Generate(4, 10000, 2, 0, 0, "Alpha", "Pilot", 18, 1, 1,
            1_000_001, List(8) { 50 }, 0, 0, 0, 20, 3, 0, "Ship", 1)
        val encoded = CharacterMessages.generateReply(character)
        val props = Properties().also { it.setProperty("owner", java.util.HexFormat.of().formatHex(encoded.copyOfRange(4, encoded.size))) }
        Files.newBufferedWriter(file).use { props.store(it, "test") }
        val original = Files.readAllBytes(file)
        val stores = CharacterStores(file)
        assertEquals(character.copy(shipKind = 3), stores.get(1).character("owner"))
        assertNull(stores.get(2).character("owner"))
        assertSame(stores.get(2), stores.get(2))
        assertNull(CharacterStores(file).get(2).character("owner"))
        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals(character.copy(shipKind = 3), stores.get(1).character("owner"))
    }
}
