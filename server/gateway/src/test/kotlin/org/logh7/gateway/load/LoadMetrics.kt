package org.logh7.gateway.load

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil

/** Socket operation wall durations, not actor queue age or tick lag. */
class LoadMetrics {
    private val samples = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    private val errors = ConcurrentLinkedQueue<String>()
    private val observations = linkedMapOf<String, Long>()
    fun <T> measure(operation: String, run: () -> T): T {
        val start = System.nanoTime()
        try { return run() } catch (failure: Throwable) { failed(operation, failure); throw failure }
        finally { samples.computeIfAbsent(operation) { ConcurrentLinkedQueue() }.add(System.nanoTime() - start) }
    }
    @Synchronized fun observe(name: String, value: Long) { observations[name] = value }
    @Synchronized fun increment(name: String) { observations[name] = (observations[name] ?: 0) + 1 }
    data class Distribution(val count: Int, val p50: Long, val p95: Long, val p99: Long, val max: Long)
    fun distribution(operation: String): Distribution? {
        val sorted = samples[operation]?.sorted()?.takeIf { it.isNotEmpty() } ?: return null
        fun percentile(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceAtLeast(0)]
        return Distribution(sorted.size, percentile(.50), percentile(.95), percentile(.99), sorted.last())
    }
    fun errorCount() = errors.size
    fun failed(operation: String, failure: Throwable) { errors += "$operation:${failure.javaClass.simpleName}" }
    @Synchronized fun json(): String {
        val latencies = samples.keys.sorted().joinToString(",") { key ->
            val d = checkNotNull(distribution(key))
            "\"$key\":{\"count\":${d.count},\"p50\":${d.p50},\"p95\":${d.p95},\"p99\":${d.p99},\"max\":${d.max}}"
        }
        val observed = observations.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
        val status = if (observations["completed"] == 1L && errors.isEmpty()) "COMPLETED" else "INCOMPLETE_OR_FAILED"
        return "{\"status\":\"$status\",\"durationUnit\":\"nanoseconds\",\"latencies\":{$latencies},\"observations\":{$observed},\"errors\":[${errors.joinToString(",") { "\"$it\"" }}],\"rekey\":\"NOT_RUN_UNSUPPORTED\",\"actorQueueAge\":\"NOT_MEASURED\",\"tickLag\":\"NOT_MEASURED\"}"
    }
    fun write(directory: Path, name: String) {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("$name.json"), json() + "\n")
        // Preserve raw durations so percentile calculations can be independently checked.
        val raw = samples.keys.sorted().flatMap { key -> samples.getValue(key).map { "$key\t$it" } }
        Files.write(directory.resolve("$name-durations.tsv"), listOf("operation\tnanoseconds") + raw)
    }
}
