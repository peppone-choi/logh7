package org.logh7.gateway.load

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.Admission
import org.logh7.engine.ActorTiming
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LoadHarnessTest {
    @TempDir lateinit var temporary: Path
    @Test fun actorDistributionsBoundRawSamplesRetainAllCountAndMaxAndUseNearestRank() {
        val metrics = LoadMetrics()
        for (metric in ActorTiming.entries) {
            for (value in 1L..5000L) metrics.record(metric, value)
            val d = assertNotNull(metrics.actorDistribution(metric))
            assertEquals(5000L, d.count)
            assertEquals(4096, d.sampled)
            assertEquals(904L, d.dropped)
            assertEquals(5000L, d.max)
            assertEquals(2048L, d.p50)
            assertEquals(3892L, d.p95)
            assertEquals(4056L, d.p99)
        }
        metrics.record(ActorTiming.QUEUE_AGE, -1)
        assertEquals(1L, metrics.regressionCount())
        assertEquals(5000L, metrics.actorDistribution(ActorTiming.QUEUE_AGE)!!.count)
        metrics.write(temporary, "bounded")
        val raw = Files.readAllLines(temporary.resolve("bounded-actor-durations.tsv"))
        assertEquals(1 + ActorTiming.entries.size * LoadMetrics.MAX_SAMPLES, raw.size)
        assertTrue(raw.drop(1).all { it.substringAfter('\t').toLong() in 1L..4096L })
        val empty = LoadMetrics().json()
        assertEquals(ActorTiming.entries.size, Regex("NOT_MEASURED").findAll(empty).count())
        assertTrue(empty.contains("\"count\":0"))
    }
    @Test fun smallLoopbackLifecycleAlwaysRuns() = lifecycle(3, "small")
    @Test fun fiftyLoopbackClientsWhenExplicitlyEnabled() {
        assumeTrue(System.getenv("LOGH7_LOAD_HARNESS") == "1", "50-client harness requires LOGH7_LOAD_HARNESS=1")
        lifecycle(50, "fifty")
    }
    @Test fun pendingWireJoinResetLeavesOnlyAnOfflineParticipant() {
        val metrics = LoadMetrics()
        val root = output("pending-reset")
        try {
            LoadFixture(root, 2, metrics).use { fixture ->
                val account = fixture.account(0)
                val game = fixture.game(account, create = true)
                val held = fixture.holdNextAdmission(account)
                game.send(SyntheticClient.opcode(0x0205))
                assertEquals(Admission.ACCEPTED, held.awaitApplied())
                assertFalse(held.reply.isDone) // Real actor applied; Gateway has not received its ACK result.
                game.reset()
                fixture.awaitState(online = 0, connections = 0, participants = 1)
                held.release()
                fixture.awaitState(online = 0, connections = 0, participants = 1)
                metrics.observe("pendingResetParticipants", 1)
                metrics.observe("pendingResetOnline", 0)
                metrics.observe("pendingResetChannels", fixture.gateway.connections().tracked.toLong())
                metrics.observe("pendingResetBindings", fixture.access.trackedConnections().toLong())
                assertEquals(0, metrics.errorCount())
            }
            assertActorTimings(metrics)
            metrics.observe("completed", 1)
        } catch (failure: Throwable) {
            if (metrics.errorCount() == 0) metrics.failed("harness", failure)
            throw failure
        } finally { metrics.write(root, "pending-reset"); println(metrics.json()) }
    }
    @Test fun privateGatewayCloseAlsoReleasesConnectedChildrenAndBindings() {
        val metrics = LoadMetrics(); val fixture = LoadFixture(output("gateway-close"), 1, metrics)
        try {
            val joined = fixture.join(fixture.account(0), create = true); assertTrue(joined.accepted)
            fixture.awaitState(1, 1, 1)
            fixture.gateway.close()
            joined.client.expectClosed()
            fixture.awaitState(0, 0, 1)
            assertEquals(0, metrics.errorCount())
        } finally { fixture.close() }
    }
    private fun lifecycle(count: Int, name: String) {
        val root = output(name); val metrics = LoadMetrics()
        try {
            LoadFixture(root, count, metrics).use { fixture ->
                fixture.badLogin(); fixture.awaitState(0, 0, 0)
                val first = parallel(count) { index -> fixture.join(fixture.account(index), create = true).also { assertTrue(it.accepted) } }
                fixture.awaitState(count, count, count)
                fixture.await("initial admission observations") { fixture.decisions.count { it == Admission.ACCEPTED } == count }
                assertEquals(count, fixture.decisions.count { it == Admission.ACCEPTED })
                metrics.observe("initialAccepted", count.toLong()); metrics.observe("initialOnline", fixture.engine.snapshot().session!!.online.toLong())
                metrics.observe("initialChannels", fixture.gateway.connections().tracked.toLong())
                assertTrue(fixture.captureFragmentObserved(), "A split TCP input chunk must be observed in the private capture")
                metrics.observe("fragmentedChunkObserved", 1)
                val duplicate = fixture.join(fixture.account(0), create = false)
                assertFalse(duplicate.accepted); duplicate.client.close()
                fixture.awaitState(count, count, count)
                fixture.await("duplicate admission observation") { Admission.ALREADY_ONLINE in fixture.decisions }
                assertTrue(Admission.ALREADY_ONLINE in fixture.decisions)
                val full = fixture.join(fixture.account(count), create = true)
                assertFalse(full.accepted); full.client.close()
                fixture.awaitState(count, count, count)
                fixture.await("full admission observation") { Admission.FULL in fixture.decisions }
                assertTrue(Admission.FULL in fixture.decisions)
                metrics.measure("resetAll") { parallel(count) { first[it].client.reset() } }
                metrics.observe("clientResetsRequested", count.toLong())
                fixture.awaitState(0, 0, count)
                metrics.observe("afterResetOnline", 0); metrics.observe("afterResetChannels", fixture.gateway.connections().tracked.toLong())
                metrics.observe("afterResetBindings", fixture.access.trackedConnections().toLong())
                metrics.observe("afterResetOfflineParticipants", fixture.engine.snapshot().session!!.participants.toLong())
                val second = parallel(count) { index -> fixture.join(fixture.account(index), create = false).also { assertTrue(it.accepted) } }
                fixture.awaitState(count, count, count)
                fixture.await("reconnect admission observations") { fixture.decisions.count { it == Admission.ACCEPTED } == count * 2 }
                assertEquals(count * 2, fixture.decisions.count { it == Admission.ACCEPTED })
                metrics.observe("reconnectAccepted", count.toLong()); metrics.observe("reconnectOnline", fixture.engine.snapshot().session!!.online.toLong())
                val clock = parallel(count) { fixture.clock(second[it].client) }
                metrics.observe("clockSecondsMin", clock.min()); metrics.observe("clockSecondsMax", clock.max())
                metrics.measure("endAndCleanup") {
                    fixture.endSession()
                    parallel(count) { second[it].client.expectClosed(); second[it].client.close() }
                    fixture.awaitState(0, 0, count)
                }
                metrics.observe("finalOnline", fixture.engine.snapshot().session!!.online.toLong())
                metrics.observe("finalActiveChannels", fixture.gateway.connections().active.toLong())
                metrics.observe("finalTrackedChannels", fixture.gateway.connections().tracked.toLong())
                metrics.observe("finalAdmissionBindings", fixture.access.trackedConnections().toLong())
                metrics.observe("finalRetainedOfflineParticipants", fixture.engine.snapshot().session!!.participants.toLong())
                assertEquals(0L, fixture.engine.snapshot().session!!.online.toLong())
                assertEquals(0, fixture.gateway.connections().active)
                assertEquals(0, fixture.gateway.connections().tracked)
                assertEquals(0, fixture.access.trackedConnections())
                assertEquals(0, metrics.errorCount())
            }
            assertActorTimings(metrics)
            metrics.observe("completed", 1)
        } catch (failure: Throwable) {
            if (metrics.errorCount() == 0) metrics.failed("harness", failure)
            throw failure
        } finally { metrics.write(root, name); println(metrics.json()) }
    }
    private fun assertActorTimings(metrics: LoadMetrics) {
        val queue = assertNotNull(metrics.actorDistribution(ActorTiming.QUEUE_AGE))
        val send = assertNotNull(metrics.actorDistribution(ActorTiming.SEND_WAIT))
        assertEquals(queue.count, send.count, "Drained fixture must have measured every accepted send and dequeue")
        assertTrue(queue.count > 0)
        assertEquals(0L, metrics.regressionCount())
        for (metric in ActorTiming.entries) {
            val d = metrics.actorDistribution(metric) ?: continue
            assertTrue(d.sampled <= LoadMetrics.MAX_SAMPLES)
            assertEquals(d.count, d.sampled + d.dropped)
            assertTrue(d.p50 <= d.p95 && d.p95 <= d.p99 && d.p99 <= d.max)
        }
        val wake = metrics.actorDistribution(ActorTiming.TICK_WAKE_LAG)
        val tick = metrics.actorDistribution(ActorTiming.TICK_DEQUEUE_LAG)
        val cancelledWake = (wake?.count ?: 0L) - (tick?.count ?: 0L)
        assertTrue(cancelledWake in 0L..1L, "Closing may cancel the last woken ticker before its send")
        metrics.observe("cancelledTickWake", cancelledWake)
    }
    private fun output(name: String): Path {
        val external = System.getenv("LOGH7_LOAD_REPORT_DIR")?.let { Path.of(it).toAbsolutePath() }
        val directory = (external ?: temporary).resolve(name)
        require(!Files.exists(directory)) { "Load output must be a fresh directory: $directory" }
        return Files.createDirectories(directory)
    }
    private fun <T> parallel(count: Int, run: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(minOf(count, 8))
        try {
            val futures = pool.invokeAll((0 until count).map { index -> Callable { run(index) } }, 30, TimeUnit.SECONDS)
            return futures.map { check(!it.isCancelled) { "Load batch timed out" }; it.get() }
        } finally { pool.shutdownNow(); check(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }
}
