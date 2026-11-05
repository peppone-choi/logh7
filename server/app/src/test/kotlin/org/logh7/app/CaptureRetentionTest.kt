package org.logh7.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.*

class CaptureRetentionTest {
    @TempDir lateinit var directory: Path
    private val now = 1_000_000L

    private fun connection(id: String, ageSeconds: Long, bytes: Int) {
        for (suffix in listOf("tsv", "client-to-server.bin", "server-to-client.bin")) {
            val file = Files.write(directory.resolve("$id.$suffix"), ByteArray(bytes))
            Files.setLastModifiedTime(file, FileTime.fromMillis((now - ageSeconds) * 1000))
        }
    }
    private fun names() = Files.list(directory).use { it.map { p -> p.fileName.toString().substringBefore('.') }.toList() }.toSet()

    @Test fun oldGroupsGoFirstThenTheOldestUntilWithinTheLimitButLiveConnectionsStay() {
        connection("47900-old", ageSeconds = 4 * 86400, bytes = 10)   // past the 72h age
        connection("47900-a", ageSeconds = 3600, bytes = 100)
        connection("47900-b", ageSeconds = 1800, bytes = 100)
        connection("47900-live", ageSeconds = 5, bytes = 400)         // written just now: never removed
        val retention = CaptureRetention(directory, maxAgeSeconds = 72 * 3600, maxBytes = 1500, now = { now })
        val left = retention.prune()
        // 4 groups: 30 + 300 + 300 + 1200 = 1830 bytes. The old group goes by age (1800 left), then "a" (oldest)
        // to get within 1500; "b" stays because 1500 is not over the limit.
        assertEquals(setOf("47900-b", "47900-live"), names())
        assertEquals(1500, left); assertEquals(6, retention.removedFiles.get())
        assertEquals(mapOf("capture_bytes" to 1500L, "capture_limit_bytes" to 1500L, "capture_removed_files_total" to 6L), retention.metrics())
    }

    @Test fun aLiveConnectionAboveTheLimitIsKeptAndAMissingDirectoryIsHarmless() {
        connection("47902-live", ageSeconds = 0, bytes = 1000)
        val retention = CaptureRetention(directory, maxAgeSeconds = 3600, maxBytes = 100, now = { now })
        assertEquals(3000, retention.prune(), "a live capture is never cut")
        assertEquals(setOf("47902-live"), names())
        assertEquals(0, CaptureRetention(directory.resolve("absent"), 3600, 100, now = { now }).prune())
    }
}
