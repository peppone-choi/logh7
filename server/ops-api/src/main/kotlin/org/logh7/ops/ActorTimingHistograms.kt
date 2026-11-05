package org.logh7.ops

import org.logh7.engine.ActorTiming
import org.logh7.engine.ActorTimings
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Cumulative Prometheus histograms of the world actor's own timings (queue age, tick wake/dequeue lag,
 * send wait), for the queue/lag alerts in deploy/prometheus/game-alerts.yml. Lock-free and allocation-free
 * on [record], as the engine requires of an [ActorTimings]. Author: Peppone Choi.
 */
class ActorTimingHistograms : ActorTimings {
    private class Histogram {
        /** Non-cumulative per bucket, the last slot being above every bound. Count and +Inf are derived from
         * one read of these slots, so an export never shows a finite bucket above +Inf (PR #278 review). */
        val buckets = AtomicLongArray(BOUNDS.size + 1)
        val sumNanos = AtomicLong()
    }
    private val histograms = ActorTiming.entries.associateWith { Histogram() }
    val clockRegressions = AtomicLong()

    override fun record(metric: ActorTiming, nanoseconds: Long) {
        val histogram = histograms.getValue(metric)
        val index = BOUNDS.indexOfFirst { nanoseconds <= it }.let { if (it < 0) BOUNDS.size else it }
        histogram.buckets.incrementAndGet(index)
        histogram.sumNanos.addAndGet(nanoseconds)
    }

    override fun clockRegression() { clockRegressions.incrementAndGet() }

    /** Prometheus text: logh7_actor_<metric>_seconds_bucket/_sum/_count for every timing. */
    fun exposition(): String = buildString {
        for ((metric, histogram) in histograms) {
            val name = "logh7_actor_" + metric.name.lowercase() + "_seconds"
            append("# HELP ").append(name).append(" World actor ").append(metric.name.lowercase().replace('_', ' ')).append(".\n")
            append("# TYPE ").append(name).append(" histogram\n")
            val slots = LongArray(BOUNDS.size + 1) { histogram.buckets.get(it) }  // one read per slot
            var cumulative = 0L
            for (i in BOUNDS.indices) {
                cumulative += slots[i]
                append(name).append("_bucket{le=\"").append(LABELS[i]).append("\"} ").append(cumulative).append('\n')
            }
            val count = cumulative + slots[BOUNDS.size]
            append(name).append("_bucket{le=\"+Inf\"} ").append(count).append('\n')
            append(name).append("_sum ").append(histogram.sumNanos.get() / 1e9).append('\n')
            append(name).append("_count ").append(count).append('\n')
        }
        append("# HELP logh7_actor_clock_regressions_total Metric-clock regressions seen by the world actor.\n")
        append("# TYPE logh7_actor_clock_regressions_total counter\n")
        append("logh7_actor_clock_regressions_total ").append(clockRegressions.get()).append('\n')
    }

    private companion object {
        /** Upper bounds: 1 ms .. 30 s. */
        val SECONDS = listOf(0.001, 0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0)
        val BOUNDS = SECONDS.map { (it * 1e9).toLong() }.toLongArray()
        val LABELS = SECONDS.map { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
    }
}
