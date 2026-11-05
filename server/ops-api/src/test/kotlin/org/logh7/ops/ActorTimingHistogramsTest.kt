package org.logh7.ops

import org.logh7.engine.ActorTiming
import kotlin.test.*

class ActorTimingHistogramsTest {
    @Test fun cumulativeBucketsSumAndCountFollowPrometheusHistogramRules() {
        val h = ActorTimingHistograms()
        h.record(ActorTiming.QUEUE_AGE, 500_000)          // 0.5 ms
        h.record(ActorTiming.QUEUE_AGE, 2_000_000_000)    // 2 s
        h.record(ActorTiming.QUEUE_AGE, 60_000_000_000)   // 60 s: above every bound, only in +Inf
        h.clockRegression()
        val lines = h.exposition().lines()
        fun value(series: String) = lines.single { it.startsWith("$series ") }.substringAfter(' ')
        val name = "logh7_actor_queue_age_seconds"
        assertEquals("1", value("${name}_bucket{le=\"0.001\"}"))
        assertEquals("1", value("${name}_bucket{le=\"1\"}"))
        assertEquals("2", value("${name}_bucket{le=\"2.5\"}"), "2.5 keeps its fraction in the label")
        assertEquals("2", value("${name}_bucket{le=\"30\"}"))
        assertEquals("3", value("${name}_bucket{le=\"+Inf\"}")); assertEquals("3", value("${name}_count"))
        assertEquals(62.0005, value("${name}_sum").toDouble(), 1e-9)
        assertEquals("0", value("logh7_actor_tick_dequeue_lag_seconds_count"))
        assertEquals("1", value("logh7_actor_clock_regressions_total"))
        assertTrue("# TYPE ${name} histogram" in lines)
    }
}
