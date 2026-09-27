package org.logh7.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicReference

data class WorldSnapshot(val revision: Long, val acceptedCommands: Long)
sealed interface WorldCommand { data object Probe : WorldCommand }
interface CommandSink { suspend fun submit(command: WorldCommand) }
interface SnapshotSource { fun snapshot(): WorldSnapshot }

/** Only this actor owns and mutates world state. */
class WorldEngine(scope: CoroutineScope) : CommandSink, SnapshotSource, AutoCloseable {
    private val queue = Channel<WorldCommand>(1024)
    private val published = AtomicReference(WorldSnapshot(0, 0))
    private val worker = scope.launch {
        var state = WorldSnapshot(0, 0)
        for (command in queue) {
            state = when (command) { WorldCommand.Probe -> state.copy(revision = state.revision + 1, acceptedCommands = state.acceptedCommands + 1) }
            published.set(state)
        }
    }
    override suspend fun submit(command: WorldCommand) { queue.send(command) }
    override fun snapshot(): WorldSnapshot = published.get()
    suspend fun drainAndClose() { queue.close(); worker.join() }
    override fun close() { queue.close() }
}
