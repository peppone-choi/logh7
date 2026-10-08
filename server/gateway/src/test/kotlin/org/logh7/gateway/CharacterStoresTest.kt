package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.protocol.CharacterMessages
import java.nio.file.Path
import java.nio.file.Files
import java.util.Properties
import kotlin.test.*

class CharacterStoresTest {
    @TempDir lateinit var directory: Path
    @Test fun newGenerationStartsEmptyAndPreviousCharacterFileRemainsByteIdentical() {
        val file = directory.resolve("characters.properties")
        val character = CharacterMessages.Generate(4, 10000, 2, 0, 0, "Alpha", "Pilot", 18, 1, 1,
            1_000_001, List(8) { 50 }, 0, 0, 0, 20, 3, 0, "Ship", 1)
        val encoded = CharacterMessages.generateReply(character)
        val props = Properties().also { it.setProperty("owner", java.util.HexFormat.of().formatHex(encoded.copyOfRange(4, encoded.size))) }
        Files.newBufferedWriter(file).use { props.store(it, "test") }
        val original = Files.readAllBytes(file)
        val stores = CharacterStores(file)
        assertEquals(character, stores.get(1).character("owner"))
        assertNull(stores.get(2).character("owner"))
        assertSame(stores.get(2), stores.get(2))
        assertNull(CharacterStores(file).get(2).character("owner"))
        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals(character, stores.get(1).character("owner"))
    }
}
