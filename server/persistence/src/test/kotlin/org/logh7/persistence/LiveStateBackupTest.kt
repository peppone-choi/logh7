package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.test.*

class LiveStateBackupTest {
    @TempDir lateinit var directory: Path

    @Test fun copiesEveryStoreButLocksInTheOfflineToolLayoutAndKeepsTheNewest() {
        val state = Files.createDirectories(directory.resolve("state"))
        val characters = Files.writeString(state.resolve("characters.properties"), "a=1\n")
        Files.writeString(state.resolve("characters.properties.mail.session-1"), "mail")
        Files.writeString(state.resolve("characters.properties.mail.session-1.lock"), "")
        Files.writeString(state.resolve("other.txt"), "not ours")
        val world = Files.createDirectories(state.resolve("world"))
        Files.writeString(world.resolve("slot-a"), "world")
        val session = Files.createDirectories(state.resolve("session").resolve("g1"))
        Files.writeString(session.resolve("journal"), "journal")
        val root = directory.resolve("backups")
        val backup = LiveStateBackup(characters, world, state.resolve("session"), root, keep = 2)
        val first = backup.finish(backup.copy(LocalDateTime.of(2026, 11, 4, 12, 0, 0)))
        assertEquals("state-20261104-120000", first.fileName.toString())
        assertFalse(Files.exists(root.resolve("state-20261104-120000.partial")))
        val manifest = Files.readString(first.resolve("manifest.json"))
        assertTrue("\"format\": \"LOGH7-STATE-BACKUP-1\"" in manifest && "\"live\": true" in manifest)
        assertTrue("\"name\": \"characters.properties.mail.session-1\"" in manifest && ".lock" !in manifest && "other.txt" !in manifest)
        assertTrue("\"name\": \"g1/journal\"" in manifest)
        assertEquals("journal", Files.readString(first.resolve("session/g1/journal")))
        assertFailsWith<IllegalArgumentException> { backup.copy(LocalDateTime.of(2026, 11, 4, 12, 0, 0)) }
        backup.finish(backup.copy(LocalDateTime.of(2026, 11, 4, 12, 30, 0)))
        backup.finish(backup.copy(LocalDateTime.of(2026, 11, 4, 13, 0, 0)))
        assertEquals(listOf("state-20261104-123000", "state-20261104-130000"),
            Files.list(root).use { it.map { p -> p.fileName.toString() }.sorted().toList() })
        val other = directory.resolve("other-disk")
        val exported = backup.export(root.resolve("state-20261104-130000"), other)
        assertEquals(Files.readString(root.resolve("state-20261104-130000/manifest.json")), Files.readString(exported.resolve("manifest.json")))
        assertEquals("journal", Files.readString(exported.resolve("session/g1/journal")))
        assertFailsWith<IllegalArgumentException> { backup.export(root.resolve("state-20261104-130000"), other) }
    }
}
