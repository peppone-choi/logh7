package org.logh7.app

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.Navigator
import org.logh7.engine.PlanetStocks
import org.logh7.engine.Power
import org.logh7.engine.StrategicPerson
import org.logh7.engine.StrategicState
import org.logh7.gateway.CharacterCreation
import org.logh7.persistence.FileWorldStateStore
import org.logh7.protocol.CharacterMessages
import kotlin.test.*

class WorldCharacterStorageTest {
    @TempDir lateinit var directory: Path

    @Test fun explicitStorageWinsAndInferredStorageStaysBesidePersistentWorldState() {
        val world = directory.resolve("world")
        val navigation = directory.resolve("legacy.navigation")
        val explicit = directory.resolve("original.properties")
        fun environment(vararg values: Pair<String, Path>) = RuntimeEnvironment.load(
            values.associate { it.first to it.second.toString() }, directory)

        assertEquals(explicit, worldCharacterFile(environment("LOGH7_CHARACTERS_FILE" to explicit,
            "LOGH7_WORLD_STATE_DIR" to world), hasWorld = true))
        assertNull(worldCharacterFile(environment("LOGH7_NAVIGATION_FILE" to navigation), hasWorld = false))
        assertNull(worldCharacterFile(environment(), hasWorld = true))
        assertEquals(directory.resolve("world.characters.properties"),
            worldCharacterFile(environment("LOGH7_WORLD_STATE_DIR" to world), hasWorld = true))
        assertEquals(directory.resolve("world.characters.properties"), worldCharacterFile(environment(
            "LOGH7_NAVIGATION_FILE" to world.resolve("legacy.navigation"), "LOGH7_WORLD_STATE_DIR" to world), hasWorld = true))
        assertEquals(directory.resolve("legacy.navigation.characters.properties"), worldCharacterFile(environment(
            "LOGH7_NAVIGATION_FILE" to navigation, "LOGH7_WORLD_STATE_DIR" to world), hasWorld = true))
    }

    @Test fun maintenanceUsesAnIndependentSiblingOfThePersistentSessionOrLegacyState() {
        val session = directory.resolve("alpha/session")
        val world = directory.resolve("alpha/world")
        val characterFile = directory.resolve("legacy/characters.properties")
        assertEquals(directory.resolve("alpha/session.maintenance"), maintenanceStateFile(session, world, characterFile))
        assertEquals(directory.resolve("alpha/world.maintenance"), maintenanceStateFile(null, world, characterFile))
        assertEquals(directory.resolve("legacy/characters.properties-maintenance"), maintenanceStateFile(null, null, characterFile))
        assertNull(maintenanceStateFile(null, null, null))
    }

    @Test fun reopenedWorldRequiresItsCurrentRegistrationWhenAPlayerRemains() {
        val world = directory.resolve("world")
        val file = assertNotNull(worldCharacterFile(RuntimeEnvironment.load(
            mapOf("LOGH7_WORLD_STATE_DIR" to world.toString()), directory), hasWorld = true))
        val pilot = Navigator(2, 10000, Power.EMPIRE, 2487, 1000, 1000, 1000)
        val previous = pilot.copy(generation = 1, characterId = 10001)
        val data = CharacterMessages.Generate(4, 10000, 2, 0, 0, "Pilot", "Test", 30, 1, 1, 1,
            List(8) { 50 }, 0, 0, 0, 13, 0, 0, "Ship", 1)
        FileWorldStateStore(world).use { store ->
            requireWorldCharacterHistory(file, 2, store.navigationStore, store.stockStore, emptySet())
            assertTrue(CharacterCreation(file).carry("pilot", data))
            store.saveWorld(mapOf("pilot" to pilot, "previous" to previous), PlanetStocks(2, 0, emptyMap()))
        }
        FileWorldStateStore(world).use { store ->
            val failure = assertFailsWith<IllegalStateException> {
                requireWorldCharacterHistory(file, 2, store.navigationStore, store.stockStore, setOf("pilot"))
            }
            val current = directory.resolve("world.characters.properties.session-2")
            assertContains(failure.message.orEmpty(), current.toString())
            assertContains(failure.message.orEmpty(), "Restore the matching character backup")
            assertFalse(Files.exists(current))
            assertEquals(pilot, store.loadNavigation().getValue("pilot"))
            assertTrue(CharacterCreation(current).carry("pilot", data))
            requireWorldCharacterHistory(file, 2, store.navigationStore, store.stockStore, setOf("pilot"))
            assertEquals(data, CharacterCreation(current).character("pilot"))
        }
    }

    @Test fun retiredPilotsNeedNoRegistrationButPersistedParticipantsWithoutANavigatorDo() {
        val world = directory.resolve("world")
        val file = directory.resolve("world.characters.properties")
        val pilot = Navigator(2, 10000, Power.EMPIRE, 2487, 1000, 1000, 1000)
        FileWorldStateStore(world).use { store ->
            store.saveWorld(mapOf("retired" to pilot, "previous" to pilot.copy(generation = 1)),
                PlanetStocks(2, 0, emptyMap(), strategic = StrategicState(people = mapOf(10000 to StrategicPerson(deceasedAt = 1)))))
            requireWorldCharacterHistory(file, 2, store.navigationStore, store.stockStore, setOf("retired"))
            assertFailsWith<IllegalStateException> {
                requireWorldCharacterHistory(file, 2, store.navigationStore, store.stockStore, setOf("retired", "pending"))
            }
        }
    }
}
