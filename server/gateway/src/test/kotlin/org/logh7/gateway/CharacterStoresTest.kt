package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.CountryId
import org.logh7.engine.Navigator
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
import kotlin.test.*

class CharacterStoresTest {
    @TempDir lateinit var directory: Path

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
