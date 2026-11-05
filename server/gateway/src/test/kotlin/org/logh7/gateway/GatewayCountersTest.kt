package org.logh7.gateway

import org.junit.jupiter.api.Test
import kotlin.test.*

class GatewayCountersTest {
    @Test fun countersAndGaugesAreExportedUnderSeparateNames() {
        val counters = GatewayCounters()
        counters.notificationOverflow.incrementAndGet()
        counters.notificationsQueued.addAndGet(3); counters.notificationsQueued.decrementAndGet()
        val snapshot = counters.snapshot()
        assertTrue(snapshot.keys.all { it.endsWith("_total") }, "the counter map holds only counters")
        assertEquals(1L, snapshot["notification_overflow_total"])
        assertEquals(mapOf("notifications_queued" to 2L), counters.gauges())
    }
}
