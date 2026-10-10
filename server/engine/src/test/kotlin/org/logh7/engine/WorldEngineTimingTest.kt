package org.logh7.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorldEngineTimingTest {
    private class Recording : ActorTimings {
        val samples = CopyOnWriteArrayList<Pair<ActorTiming, Long>>()
        val regressions = AtomicInteger()
        var afterRecord: (ActorTiming) -> Unit = {}
        override fun record(metric: ActorTiming, nanoseconds: Long) {
            samples += metric to nanoseconds
            afterRecord(metric)
        }
        override fun clockRegression() { regressions.incrementAndGet() }
        fun values(metric: ActorTiming) = samples.filter { it.first == metric }.map { it.second }
    }
    @Test fun queuedCommandAgesSevenMillisWithoutAdvancingSimulation() = runTest {
        var nanos = 0L
        val timings = Recording()
        val engine = WorldEngine(this, ticking = false, monotonicMillis = { 1234L },
            timings = timings, metricNanos = { nanos })
        engine.submit(WorldCommand.AdvanceTo(0))
        nanos = 7_000_000L
        runCurrent()
        engine.drainAndClose()
        assertEquals(listOf(7_000_000L), timings.values(ActorTiming.QUEUE_AGE))
        assertEquals(listOf(0L), timings.values(ActorTiming.SEND_WAIT))
        assertTrue(timings.values(ActorTiming.TICK_DEQUEUE_LAG).isEmpty(), "Manual AdvanceTo has no scheduled wake")
        assertEquals(0L, engine.snapshot().session!!.gameSeconds)
    }
    @Test fun full1024BufferIncludesSuspendedSubmissionInQueueAgeAndSendWait() = runTest {
        var nanos = 0L
        val timings = Recording()
        val engine = WorldEngine(this, ticking = false, timings = timings, metricNanos = { nanos })
        repeat(1024) { engine.submit(WorldCommand.Probe) }
        val blocked = launch(start = CoroutineStart.UNDISPATCHED) { engine.submit(WorldCommand.Probe) }
        assertFalse(blocked.isCompleted)
        nanos = 7_000_000L
        runCurrent()
        blocked.join()
        engine.drainAndClose()
        assertEquals(1025L, engine.snapshot().acceptedCommands)
        assertEquals(1025, timings.values(ActorTiming.QUEUE_AGE).size)
        assertEquals(listOf(7_000_000L), timings.values(ActorTiming.BACKPRESSURED_QUEUE_AGE))
        assertEquals(7_000_000L, timings.values(ActorTiming.SEND_WAIT).last())
        assertTrue(timings.values(ActorTiming.QUEUE_AGE).all { it == 7_000_000L })
    }
    @Test fun tickWakeThreeMillisAndDequeueTenMillisAreSeparateFixedDelayMeasurements() = runTest {
        var offset = 0L
        val timings = Recording()
        val rules = SessionRules.load().copy(strategyTickRealMillis = 10, tacticsTickRealMillis = 10)
        // Defer the worker by advancing the independent clock after send returns.
        timings.afterRecord = { if (it == ActorTiming.SEND_WAIT) offset += 7_000_000L }
        val engine = WorldEngine(this, rules, monotonicMillis = { testScheduler.currentTime },
            timings = timings, metricNanos = { testScheduler.currentTime * 1_000_000L + offset })
        runCurrent()
        advanceTimeBy(10)
        offset = 3_000_000L
        runCurrent()
        assertEquals(listOf(3_000_000L), timings.values(ActorTiming.TICK_WAKE_LAG))
        assertEquals(listOf(10_000_000L), timings.values(ActorTiming.TICK_DEQUEUE_LAG))
        assertEquals(listOf(7_000_000L), timings.values(ActorTiming.QUEUE_AGE))
        advanceTimeBy(10)
        runCurrent()
        assertEquals(listOf(3_000_000L, 0L), timings.values(ActorTiming.TICK_WAKE_LAG),
            "Next deadline starts after prior send, rather than catching up to an absolute schedule")
        engine.drainAndClose()
    }
    @Test fun regressingMetricClockOmitsNegativeDurationsAndKeepsActorRunning() = runTest {
        var nanos = 10L
        val timings = Recording()
        timings.afterRecord = { if (it == ActorTiming.SEND_WAIT) nanos = 5L }
        val engine = WorldEngine(this, ticking = false, timings = timings, metricNanos = { nanos })
        engine.submit(WorldCommand.Probe)
        runCurrent()
        assertEquals(1, timings.regressions.get())
        assertTrue(timings.values(ActorTiming.QUEUE_AGE).isEmpty())
        engine.submit(WorldCommand.Probe)
        engine.drainAndClose()
        assertEquals(2L, engine.snapshot().acceptedCommands)
        assertEquals(listOf(0L), timings.values(ActorTiming.QUEUE_AGE))
    }
    @Test fun regressingSendReturnClockIsExcludedSeparately() = runTest {
        val times = ArrayDeque(listOf(10L, 5L, 20L))
        val timings = Recording()
        val engine = WorldEngine(this, ticking = false, timings = timings, metricNanos = { times.removeFirst() })
        engine.submit(WorldCommand.Probe)
        engine.drainAndClose()
        assertTrue(timings.values(ActorTiming.SEND_WAIT).isEmpty())
        assertEquals(listOf(10L), timings.values(ActorTiming.QUEUE_AGE))
        assertEquals(1, timings.regressions.get())
    }
    @Test fun nullObserverNeverReadsMetricClockAndPreservesResults() = runTest {
        val engine = WorldEngine(this, ticking = false, metricNanos = { error("Unused metric clock") })
        val joined = CompletableDeferred<Admission>()
        engine.submit(WorldCommand.Join("user", Power.EMPIRE, false, joined))
        engine.submit(WorldCommand.AdvanceTo(1000))
        assertEquals(Admission.ACCEPTED, joined.await())
        engine.drainAndClose()
        assertEquals(1, engine.snapshot().session!!.online)
        assertEquals(24L, engine.snapshot().session!!.gameSeconds)
    }
    @Test fun cancelledSuspendedSubmitIsNotAppliedAndCloseDrainsThenRejectsLateSubmit() = runTest {
        val timings = Recording()
        val engine = WorldEngine(this, ticking = false, timings = timings, metricNanos = { 0L })
        repeat(1024) { engine.submit(WorldCommand.Probe) }
        val blocked = launch(start = CoroutineStart.UNDISPATCHED) { engine.submit(WorldCommand.Probe) }
        blocked.cancel()
        engine.close()
        runCurrent()
        blocked.join()
        engine.drainAndClose()
        engine.close()
        assertEquals(1024L, engine.snapshot().acceptedCommands)
        assertEquals(1024, timings.values(ActorTiming.SEND_WAIT).size)
        assertEquals(1024, timings.values(ActorTiming.QUEUE_AGE).size)
        assertTrue(timings.values(ActorTiming.BACKPRESSURED_QUEUE_AGE).isEmpty())
        assertFailsWith<ClosedSendChannelException> { engine.submit(WorldCommand.Probe) }
        assertEquals(1024, timings.values(ActorTiming.SEND_WAIT).size)
    }
    @Test fun throwingObserverAlsoCannotTurnClockRegressionIntoActorFailure() = runTest {
        var reads = 0
        val observer = object : ActorTimings {
            override fun record(metric: ActorTiming, nanoseconds: Long) { throw IllegalStateException("observer") }
            override fun clockRegression() { throw IllegalStateException("observer regression") }
        }
        val engine = WorldEngine(this, ticking = false, timings = observer,
            metricNanos = { if (reads++ == 0) 10L else 0L })
        val joined = CompletableDeferred<Admission>()
        engine.submit(WorldCommand.Join("user", Power.EMPIRE, false, joined))
        assertEquals(Admission.ACCEPTED, joined.await())
        engine.drainAndClose()
        assertEquals(0L, engine.snapshot().rejectedCommands)
        assertEquals(1, engine.snapshot().session!!.online)
    }
    @Test fun durableCommitPublishesBeforeJoinAndRestartAckAndEventsDespiteThrowingObserver() = runBlocking {
        val rules = SessionRules.load()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = CopyOnWriteArrayList<String>()
        val closed = CountDownLatch(1)
        val store = object : SessionDurability {
            override fun load() = DurableWorldState(SessionSimulation(rules).exportState())
            override fun commit(action: SessionAction, transition: SessionTransition) {
                if (action is SessionAction.Join) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    order += "join-commit"
                }
                if (action == SessionAction.Restart) order += "restart-commit"
            }
            override fun close() { closed.countDown() }
        }
        val observer = object : ActorTimings {
            override fun record(metric: ActorTiming, nanoseconds: Long) { throw IllegalStateException("observer") }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val joined = CompletableDeferred<Admission>()
        val restarted = CompletableDeferred<Boolean>()
        var joinOnlineAtAck: Int? = null
        var generationAtAck: Long? = null
        var generationAtEvent: Long? = null
        var ackAtEvent = false
        lateinit var engine: WorldEngine
        engine = WorldEngine(scope, rules, ticking = false, durability = store, timings = observer, onEvent = {
            if (it is WorldEvent.Restarted) {
                generationAtEvent = engine.snapshot().session!!.generation
                ackAtEvent = restarted.isCompleted
                order += "restart-event"
            }
        })
        joined.invokeOnCompletion { joinOnlineAtAck = engine.snapshot().session!!.online; order += "join-ack" }
        restarted.invokeOnCompletion { generationAtAck = engine.snapshot().session!!.generation; order += "restart-ack" }
        val before = engine.snapshot()
        try {
            engine.submit(WorldCommand.Join("user", Power.EMPIRE, false, joined))
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertFalse(joined.isCompleted)
            assertEquals(before, engine.snapshot())
            release.countDown()
            assertEquals(Admission.ACCEPTED, joined.await())
            engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 3, Power.ALLIANCE to 20), emptySet()))
            engine.submit(WorldCommand.RequestRestart(restarted))
            assertTrue(restarted.await())
            engine.drainAndClose()
            assertEquals(listOf("join-commit", "join-ack", "restart-commit", "restart-ack", "restart-event"), order.toList())
            assertEquals(1, joinOnlineAtAck)
            assertEquals(2L, generationAtAck)
            assertEquals(2L, generationAtEvent)
            assertTrue(ackAtEvent)
            assertEquals(0L, closed.count)
        } finally { release.countDown(); engine.close(); scope.cancel() }
    }
    @Test fun failedStorageRemainsFailClosedWithMetricsAndProducesNoAckOrEvents() = runTest {
        val rules = SessionRules.load()
        var commits = 0
        var closed = false
        val store = object : SessionDurability {
            override fun load() = DurableWorldState(SessionSimulation(rules).exportState())
            override fun commit(action: SessionAction, transition: SessionTransition) { commits++; throw IOException("Injected failure") }
            override fun close() { closed = true }
        }
        val events = mutableListOf<WorldEvent>()
        val timings = Recording()
        val engine = WorldEngine(this, rules, ticking = false, durability = store, timings = timings, onEvent = events::add)
        val before = engine.snapshot()
        val joined = CompletableDeferred<Admission>()
        val restarted = CompletableDeferred<Boolean>()
        engine.submit(WorldCommand.Join("user", Power.EMPIRE, false, joined))
        runCurrent()
        assertTrue(joined.isCompleted && joined.isCancelled)
        engine.submit(WorldCommand.RequestRestart(restarted))
        engine.submit(WorldCommand.Probe)
        assertFalse(restarted.await())
        engine.drainAndClose()
        assertEquals(1, commits)
        assertEquals(before, engine.snapshot())
        assertTrue(events.isEmpty())
        assertTrue(closed)
        assertEquals(3, timings.values(ActorTiming.QUEUE_AGE).size)
    }
}
