package org.logh7.engine
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldEngineTest {
    @Test fun queueSerializesConcurrentWriters() = runBlocking {
        val engine = WorldEngine(this, ticking = false)
        coroutineScope { repeat(10) { launch { repeat(100) { engine.submit(WorldCommand.Probe) } } } }
        engine.drainAndClose()
        assertEquals(1000L, engine.snapshot().revision)
        assertEquals(1000L, engine.snapshot().acceptedCommands)
        assertEquals(0L, engine.snapshot().session!!.gameSeconds)
    }
    @Test fun actorPublishesTimeAndRecoveryEventsWithoutLosingCatchup() = runBlocking {
        val events = mutableListOf<WorldEvent>()
        val engine = WorldEngine(this, ticking = false, onEvent = events::add)
        engine.submit(WorldCommand.AdvanceTo(300_000))
        engine.submit(WorldCommand.AdvanceTo(3_600_000))
        engine.drainAndClose()
        assertEquals(86_400L, engine.snapshot().session!!.gameSeconds)
        assertEquals(12L, events.filterIsInstance<WorldEvent.CpRecovery>().sumOf { it.count })
    }
    @Test fun invalidLifecycleInputDoesNotKillActorAndJoinPublishesBeforeReply() = runBlocking {
        val engine = WorldEngine(this, ticking = false)
        engine.submit(WorldCommand.Restart)
        val result = CompletableDeferred<Admission>()
        engine.submit(WorldCommand.Join("owner", Power.EMPIRE, false, result))
        assertEquals(Admission.ACCEPTED, result.await())
        assertEquals(1, engine.snapshot().session!!.participants)
        engine.submit(WorldCommand.AdvanceTo(1000))
        engine.submit(WorldCommand.AdvanceTo(999))
        engine.submit(WorldCommand.Probe)
        engine.drainAndClose()
        assertEquals(2L, engine.snapshot().rejectedCommands)
        assertEquals(24L, engine.snapshot().session!!.gameSeconds)
        assertTrue(engine.snapshot().acceptedCommands > 0)
    }
    @Test fun endedSessionAutomaticallyRestartsAfterConfiguredDelayAndPersistsGenerationBeforePublishing() = runBlocking {
        val store = MemorySessionGenerationStore(7)
        val rules = SessionRules.load().copy(start = GameDate(801, 7, 26, 23, 59, 36), restartDelayRealMillis = 500)
        val engine = WorldEngine(this, rules, ticking = false, generationStore = store)
        engine.submit(WorldCommand.AdvanceTo(1000))
        val invalidJoin = CompletableDeferred<Admission>()
        engine.submit(WorldCommand.Join("old", Power.EMPIRE, false, invalidJoin, generation = 6))
        assertEquals(Admission.STALE_SESSION, invalidJoin.await())
        assertEquals(EndReason.TIME_LIMIT, engine.snapshot().session!!.ended!!.reason)
        engine.submit(WorldCommand.AdvanceTo(1499))
        engine.submit(WorldCommand.AdvanceTo(1500))
        engine.drainAndClose()
        assertEquals(8L, store.current()); assertEquals(8L, engine.snapshot().session!!.generation)
        assertEquals(rules.startSeconds, engine.snapshot().session!!.gameSeconds)
        assertEquals(null, engine.snapshot().session!!.ended)
    }
    @Test fun manualRestartRejectsRunningStateAndStorageFailureKeepsEndedGeneration() = runBlocking {
        val store = object : SessionGenerationStore {
            override fun current() = 1L
            override fun advance(expected: Long): Long = throw java.io.IOException("unwritable marker")
        }
        val engine = WorldEngine(this, ticking = false, generationStore = store)
        val running = CompletableDeferred<Boolean>()
        engine.submit(WorldCommand.RequestRestart(running)); assertEquals(false, running.await())
        engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 3, Power.ALLIANCE to 4), emptySet()))
        val ended = CompletableDeferred<Boolean>()
        engine.submit(WorldCommand.RequestRestart(ended)); assertEquals(false, ended.await())
        engine.drainAndClose()
        assertEquals(1L, engine.snapshot().session!!.generation)
        assertEquals(EndReason.SYSTEMS_REDUCED, engine.snapshot().session!!.ended!!.reason)
    }
    @Test fun operatorRestartPublishesNewGenerationOnlyAfterEndedState() = runBlocking {
        val engine = WorldEngine(this, ticking = false)
        engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 3, Power.ALLIANCE to 4), emptySet()))
        val result = CompletableDeferred<Boolean>()
        engine.submit(WorldCommand.RequestRestart(result))
        assertEquals(true, result.await())
        assertEquals(2L, engine.snapshot().session!!.generation)
        assertEquals(null, engine.snapshot().session!!.ended)
        engine.drainAndClose()
    }
}
