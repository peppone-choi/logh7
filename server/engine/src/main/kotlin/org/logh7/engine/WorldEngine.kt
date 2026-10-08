package org.logh7.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicReference

data class WorldSnapshot(val revision: Long, val acceptedCommands: Long, val session: SessionSnapshot? = null, val rejectedCommands: Long = 0)
sealed interface WorldCommand {
    data object Probe : WorldCommand
    data class AdvanceTo(val elapsedRealMillis: Long) : WorldCommand
    data class Join(val account: String, val power: Power, val original: Boolean, val result: CompletableDeferred<Admission>) : WorldCommand
    data class Disconnect(val account: String) : WorldCommand
    data class Exclude(val account: String) : WorldCommand
    data class OpenBattle(val id: String) : WorldCommand
    data class CloseBattle(val id: String) : WorldCommand
    data class Territory(val systems: Map<Power, Int>, val capturedCapitals: Set<Power>) : WorldCommand
    data object Restart : WorldCommand
}
interface CommandSink { suspend fun submit(command: WorldCommand) }
interface SnapshotSource { fun snapshot(): WorldSnapshot }

/** Only this actor owns and mutates world state. */
class WorldEngine(scope: CoroutineScope, rules: SessionRules = SessionRules.load(), ticking: Boolean = true,
    monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onEvent: (WorldEvent) -> Unit = {}) : CommandSink, SnapshotSource, AutoCloseable {
    private val queue = Channel<WorldCommand>(1024)
    private val simulation = SessionSimulation(rules)
    private val published = AtomicReference(WorldSnapshot(0, 0, simulation.snapshot()))
    private val origin = monotonicMillis()
    private val worker = scope.launch {
        var state = published.get()
        var elapsed = 0L
        for (command in queue) {
            var admission: Admission? = null
            val events = try { when (command) {
                WorldCommand.Probe -> emptyList()
                is WorldCommand.AdvanceTo -> {
                    require(command.elapsedRealMillis >= elapsed)
                    simulation.advance(command.elapsedRealMillis - elapsed).also { elapsed = command.elapsedRealMillis }
                }
                is WorldCommand.Join -> { admission = simulation.join(command.account, command.power, command.original); emptyList() }
                is WorldCommand.Disconnect -> { simulation.disconnect(command.account); emptyList() }
                is WorldCommand.Exclude -> { simulation.exclude(command.account); emptyList() }
                is WorldCommand.OpenBattle -> { simulation.openBattle(command.id); emptyList() }
                is WorldCommand.CloseBattle -> { simulation.closeBattle(command.id); emptyList() }
                is WorldCommand.Territory -> simulation.updateTerritory(command.systems, command.capturedCapitals)
                WorldCommand.Restart -> { simulation.restart(); emptyList() }
            } } catch (failure: RuntimeException) {
                if (failure !is IllegalArgumentException && failure !is IllegalStateException && failure !is ArithmeticException) throw failure
                if (command is WorldCommand.Join) command.result.completeExceptionally(failure)
                state = state.copy(rejectedCommands = state.rejectedCommands + 1)
                emptyList()
            }
            state = state.copy(revision = state.revision + 1,
                acceptedCommands = state.acceptedCommands + if (command == WorldCommand.Probe) 1 else 0, session = simulation.snapshot())
            published.set(state)
            if (command is WorldCommand.Join && admission != null) command.result.complete(admission)
            events.forEach(onEvent)
        }
    }
    private val ticker = if (ticking) scope.launch {
        while (isActive) { delay(minOf(rules.strategyTickRealMillis, rules.tacticsTickRealMillis)); queue.send(WorldCommand.AdvanceTo(monotonicMillis() - origin)) }
    } else null
    override suspend fun submit(command: WorldCommand) {
        queue.send(if (command is WorldCommand.Territory) command.copy(systems = command.systems.toMap(), capturedCapitals = command.capturedCapitals.toSet()) else command)
    }
    override fun snapshot(): WorldSnapshot = published.get()
    suspend fun drainAndClose() { ticker?.cancelAndJoin(); queue.close(); worker.join() }
    override fun close() { ticker?.cancel(); queue.close() }
}
