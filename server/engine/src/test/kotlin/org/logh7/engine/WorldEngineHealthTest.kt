package org.logh7.engine

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class WorldEngineHealthTest {
    private class Store(val rules: SessionRules = SessionRules.load(), val failCommit: Boolean = false) : SessionDurability {
        val commits = AtomicInteger()
        override fun load() = DurableWorldState(SessionSimulation(rules).exportState())
        override fun commit(action: SessionAction, transition: SessionTransition) {
            commits.incrementAndGet()
            if (failCommit) throw IOException("Injected commit failure")
        }
        override fun close() {}
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5000) {
        while (!condition()) delay(1)
    }

    private suspend fun withEngine(rules: SessionRules = SessionRules.load(), durability: SessionDurability? = null,
        ticking: Boolean = false, monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
        onEvent: (WorldEvent) -> Unit = {}, failure: CompletableDeferred<Throwable>? = null,
        test: suspend (WorldEngine) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, thrown ->
            if (failure == null) throw thrown else failure.complete(thrown)
        })
        try {
            val engine = WorldEngine(scope, rules, ticking, monotonicMillis, onEvent, durability = durability)
            try { test(engine) } finally { engine.close() }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun newNonTickingEngineIsRunning() = runBlocking {
        withEngine { engine -> assertEquals(EngineHealth.RUNNING, engine.health()) }
    }

    @Test fun failedCommitStaysFailedWithoutPublishingOrRetryingAndStoppedTakesPriority() = runBlocking {
        val store = Store(failCommit = true)
        withEngine(durability = store) { engine ->
            val before = engine.snapshot()
            engine.submit(WorldCommand.Probe)
            awaitCondition { engine.health() == EngineHealth.STORAGE_FAILED }
            engine.submit(WorldCommand.Probe)
            // FIFO acknowledgement proves the second Probe was processed without closing the actor.
            val restarted = CompletableDeferred<Boolean>()
            engine.submit(WorldCommand.RequestRestart(restarted))
            assertFalse(withTimeout(5000) { restarted.await() })
            assertEquals(EngineHealth.STORAGE_FAILED, engine.health())
            assertEquals(before, engine.snapshot())
            assertEquals(1, store.commits.get())
            withTimeout(5000) { engine.drainAndClose() }
            assertEquals(EngineHealth.STOPPED, engine.health())
        }
    }

    @Test fun successfulDurableJoinAndProbeKeepEngineRunning() = runBlocking {
        val store = Store()
        withEngine(rules = store.rules, durability = store) { engine ->
            val joined = CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("user", Power.EMPIRE, false, joined))
            assertEquals(Admission.ACCEPTED, withTimeout(5000) { joined.await() })
            engine.submit(WorldCommand.Probe)
            awaitCondition { engine.snapshot().acceptedCommands == 1L }
            assertEquals(EngineHealth.RUNNING, engine.health())
            assertEquals(2, store.commits.get())
        }
    }

    @Test fun drainAndCloseAndAsynchronousCloseEventuallyStopWithAndWithoutTicker() = runBlocking {
        for (ticking in listOf(false, true)) for (drain in listOf(false, true)) {
            withEngine(ticking = ticking) { engine ->
                if (drain) withTimeout(5000) { engine.drainAndClose() } else {
                    engine.close()
                    awaitCondition { engine.health() == EngineHealth.STOPPED }
                    // A cancelled ticker alone may report STOPPED before the worker has drained.
                    withTimeout(5000) { engine.drainAndClose() }
                }
                assertEquals(EngineHealth.STOPPED, engine.health())
            }
        }
    }

    @Test fun nonDurableRestartEventFailureStopsActorRatherThanReportingStorageFailure() = runBlocking {
        val thrown = RuntimeException("Injected event failure")
        val failure = CompletableDeferred<Throwable>()
        withEngine(onEvent = { if (it is WorldEvent.Restarted) throw thrown }, failure = failure) { engine ->
            engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 3, Power.ALLIANCE to 4), emptySet()))
            val restarted = CompletableDeferred<Boolean>()
            engine.submit(WorldCommand.RequestRestart(restarted))
            assertTrue(withTimeout(5000) { restarted.await() })
            assertSame(thrown, withTimeout(5000) { failure.await() })
            awaitCondition { engine.health() == EngineHealth.STOPPED }
        }
    }

    @Test fun completedTickerReportsStoppedEvenWhileWorkerStillProcessesCommands() = runBlocking {
        val thrown = RuntimeException("Injected ticker clock failure")
        val failure = CompletableDeferred<Throwable>()
        val reads = AtomicInteger()
        val rules = SessionRules.load().copy(strategyTickRealMillis = 1, tacticsTickRealMillis = 1)
        withEngine(rules = rules, ticking = true,
            monotonicMillis = { if (reads.getAndIncrement() == 0) 0L else throw thrown }, failure = failure) { engine ->
            assertSame(thrown, withTimeout(5000) { failure.await() })
            awaitCondition { engine.health() == EngineHealth.STOPPED }
            engine.submit(WorldCommand.Probe)
            awaitCondition { engine.snapshot().acceptedCommands == 1L }
            assertEquals(EngineHealth.STOPPED, engine.health())
        }
    }

    @Test fun durablePostCommitEventFailureUsesExistingStorageFailureClassification() = runBlocking {
        val store = Store()
        withEngine(durability = store, onEvent = { if (it is WorldEvent.Restarted) throw RuntimeException("event") }) { engine ->
            engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 3, Power.ALLIANCE to 4), emptySet()))
            val restarted = CompletableDeferred<Boolean>()
            engine.submit(WorldCommand.RequestRestart(restarted))
            assertTrue(withTimeout(5000) { restarted.await() })
            awaitCondition { engine.health() == EngineHealth.STORAGE_FAILED }
            assertEquals(2L, engine.snapshot().session!!.generation)
            assertEquals(2, store.commits.get())
        }
    }
}
