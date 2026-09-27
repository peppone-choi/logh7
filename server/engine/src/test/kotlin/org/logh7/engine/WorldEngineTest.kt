package org.logh7.engine
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WorldEngineTest {
    @Test fun queueSerializesConcurrentWriters() = runBlocking {
        val engine = WorldEngine(this)
        coroutineScope { repeat(10) { launch { repeat(100) { engine.submit(WorldCommand.Probe) } } } }
        engine.drainAndClose()
        assertEquals(WorldSnapshot(1000, 1000), engine.snapshot())
    }
}
