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
}
