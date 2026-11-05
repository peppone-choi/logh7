package org.logh7.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.name

/**
 * Retention of the gateway packet capture (LOGH7_CAPTURE_DIR, docs/ops/alerts.md). Captures hold the key
 * exchange too, so they are sensitive diagnostics and must not grow without bound. A connection's files share
 * the name before the first '.' (`<port>-<uuid>.tsv`, `.client-to-server.bin`, `.server-to-client.bin`) and are
 * removed together: first every group older than [maxAgeSeconds], then the oldest groups until the directory is
 * within [maxBytes]. A group written within [activeSeconds] (a live connection) is never removed.
 * Author: Peppone Choi.
 */
class CaptureRetention(private val directory: Path, val maxAgeSeconds: Long, val maxBytes: Long,
    private val activeSeconds: Long = 60, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    init { require(maxAgeSeconds > 0 && maxBytes > 0 && activeSeconds >= 0) }
    val removedFiles = AtomicLong()
    @Volatile var lastBytes: Long = 0
        private set

    private data class Group(val files: List<Path>, val bytes: Long, val modified: Long)

    private fun groups(): List<Group> {
        if (!Files.isDirectory(directory)) return emptyList()
        val files = Files.list(directory).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
        return files.groupBy { it.name.substringBefore('.') }.values.map { members ->
            Group(members, members.sumOf { Files.size(it) }, members.maxOf { Files.getLastModifiedTime(it).toMillis() / 1000 })
        }.sortedBy { it.modified }
    }

    /** One pass; returns the bytes left. Files that vanish meanwhile (a concurrent pass) are ignored. */
    @Synchronized fun prune(): Long {
        val at = now()
        val all = groups()
        var total = all.sumOf { it.bytes }
        for (group in all) {
            if (at - group.modified < activeSeconds) continue
            if (at - group.modified <= maxAgeSeconds && total <= maxBytes) continue
            for (file in group.files) if (Files.deleteIfExists(file)) removedFiles.incrementAndGet()
            total -= group.bytes
        }
        lastBytes = total
        return total
    }

    fun schedule(scope: CoroutineScope, everySeconds: Long = 600): Job = scope.launch {
        while (isActive) {
            try { prune() } catch (failure: Exception) {
                System.err.println("Capture retention failed: ${failure.javaClass.simpleName}: ${failure.message}")
            }
            delay(everySeconds * 1000)
        }
    }

    /** Ops gauges/counters (names without the logh7_ prefix). */
    fun metrics(): Map<String, Long> = linkedMapOf("capture_bytes" to lastBytes, "capture_limit_bytes" to maxBytes,
        "capture_removed_files_total" to removedFiles.get())
}
