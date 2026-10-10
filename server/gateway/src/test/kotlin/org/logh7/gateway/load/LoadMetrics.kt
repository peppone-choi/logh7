package org.logh7.gateway.load

import org.logh7.engine.ActorTiming
import org.logh7.engine.ActorTimings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil

/** Socket durations and actor durations have separate distributions and bounded raw storage. */
class LoadMetrics : ActorTimings {
    companion object { const val MAX_SAMPLES = 4096 }
    private class Samples {
        val raw = ArrayList<Long>()
        var count = 0L
        var max = 0L
        fun add(value: Long) {
            count++
            max = maxOf(max, value)
            if (raw.size < MAX_SAMPLES) raw.add(value)
        }
        fun distribution(): Distribution? {
            if (raw.isEmpty()) return null
            val sorted = raw.sorted()
            fun percentile(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceAtLeast(0)]
            return Distribution(count, raw.size, count - raw.size, percentile(.50), percentile(.95), percentile(.99), max)
        }
    }
    private val samples = linkedMapOf<String, Samples>()
    private val actorSamples = ActorTiming.entries.associateWith { Samples() }
    private var clockRegressions = 0L
    private val errors = ConcurrentLinkedQueue<String>()
    private val observations = linkedMapOf<String, Long>()
    fun <T> measure(operation: String, run: () -> T): T {
        val start = System.nanoTime()
        try { return run() } catch (failure: Throwable) { failed(operation, failure); throw failure }
        finally { socketDuration(operation, System.nanoTime() - start) }
    }
    @Synchronized private fun socketDuration(operation: String, value: Long) {
        if (value < 0) clockRegressions++ else samples.getOrPut(operation) { Samples() }.add(value)
    }
    @Synchronized override fun record(metric: ActorTiming, nanoseconds: Long) {
        if (nanoseconds < 0) clockRegressions++ else actorSamples.getValue(metric).add(nanoseconds)
    }
    @Synchronized override fun clockRegression() { clockRegressions++ }
    @Synchronized fun observe(name: String, value: Long) { observations[name] = value }
    @Synchronized fun increment(name: String) { observations[name] = (observations[name] ?: 0) + 1 }
    data class Distribution(val count: Long, val sampled: Int, val dropped: Long,
        val p50: Long, val p95: Long, val p99: Long, val max: Long)
    @Synchronized fun distribution(operation: String): Distribution? = samples[operation]?.distribution()
    @Synchronized fun actorDistribution(metric: ActorTiming): Distribution? = actorSamples.getValue(metric).distribution()
    @Synchronized fun regressionCount() = clockRegressions
    fun errorCount() = errors.size
    fun failed(operation: String, failure: Throwable) { errors += "$operation:${failure.javaClass.simpleName}" }
    private fun Distribution.json() = "\"count\":$count,\"sampled\":$sampled,\"dropped\":$dropped,\"p50\":$p50,\"p95\":$p95,\"p99\":$p99,\"max\":$max"
    @Synchronized fun json(): String {
        val latencies = samples.keys.sorted().joinToString(",") { key ->
            "\"$key\":{${checkNotNull(distribution(key)).json()}}"
        }
        val actor = actorSamples.entries.joinToString(",") { (metric, values) ->
            val d = values.distribution()
            val fields = d?.let { "\"status\":\"MEASURED\",${it.json()}" }
                ?: "\"status\":\"NOT_MEASURED\",\"count\":0,\"sampled\":0,\"dropped\":0,\"max\":null,\"p50\":null,\"p95\":null,\"p99\":null"
            "\"${metric.name}\":{$fields}"
        }
        val observed = observations.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
        val status = if (observations["completed"] == 1L && errors.isEmpty()) "COMPLETED" else "INCOMPLETE_OR_FAILED"
        return "{\"status\":\"$status\",\"durationUnit\":\"nanoseconds\",\"latencies\":{$latencies},\"actorTimings\":{$actor},\"clockRegressions\":$clockRegressions,\"observations\":{$observed},\"errors\":[${errors.joinToString(",") { "\"$it\"" }}],\"rekey\":\"NOT_RUN_UNSUPPORTED\"}"
    }
    @Synchronized fun write(directory: Path, name: String) {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("$name.json"), json() + "\n")
        val raw = samples.keys.sorted().flatMap { key -> samples.getValue(key).raw.map { "$key\t$it" } }
        Files.write(directory.resolve("$name-durations.tsv"), listOf("operation\tnanoseconds") + raw)
        val actorRaw = actorSamples.entries.flatMap { (metric, values) -> values.raw.map { "${metric.name}\t$it" } }
        Files.write(directory.resolve("$name-actor-durations.tsv"), listOf("metric\tnanoseconds") + actorRaw)
    }
}
