package org.logh7.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicReference

data class WorldSnapshot(val revision: Long, val acceptedCommands: Long, val session: SessionSnapshot? = null, val rejectedCommands: Long = 0)
sealed interface WorldCommand {
    data object Probe : WorldCommand
    data class AdvanceTo(val elapsedRealMillis: Long) : WorldCommand
    data class Join(val account: String, val power: Power, val original: Boolean, val result: CompletableDeferred<Admission>, val connection: String? = null, val generation: Long? = null) : WorldCommand
    data class Disconnect(val account: String, val connection: String? = null) : WorldCommand
    data class Exclude(val account: String) : WorldCommand
    data class OpenBattle(val id: String) : WorldCommand
    data class CloseBattle(val id: String) : WorldCommand
    data class Territory(val systems: Map<Power, Int>, val capturedCapitals: Set<Power>) : WorldCommand
    data object Restart : WorldCommand
    data class RequestRestart(val result: CompletableDeferred<Boolean>) : WorldCommand
}
interface CommandSink { suspend fun submit(command: WorldCommand) }
interface SnapshotSource { fun snapshot(): WorldSnapshot }

/** Only this actor owns and mutates world state. */
class WorldEngine(scope: CoroutineScope, rules: SessionRules = SessionRules.load(), ticking: Boolean = true,
    monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onEvent: (WorldEvent) -> Unit = {}, private val generationStore: SessionGenerationStore = MemorySessionGenerationStore(),
    private val durability: SessionDurability? = null) : CommandSink, SnapshotSource, AutoCloseable {
    private val queue = Channel<WorldCommand>(1024)
    private val simulation = SessionSimulation(rules, generationStore.current())
    private var durableState = durability?.let { store ->
        try {
            val restored = store.load()
            if (restored.session.participants.values.any { it.online || it.connection != null }) {
                val recovered = transitionSession(rules, restored, SessionAction.RecoverConnections)
                store.commit(SessionAction.RecoverConnections, recovered)
                recovered.state
            } else restored
        } catch (failure: Exception) { store.close(); throw failure }
    }
    private val initialElapsed = durableState?.elapsed ?: 0L
    private val published = AtomicReference(durableState?.snapshot(rules) ?: WorldSnapshot(0, 0, simulation.snapshot()))
    private val origin = monotonicMillis()
    private val worker = scope.launch {
        var state = published.get()
        var elapsed = 0L
        var restartAt: Long? = null
        fun restart(): List<WorldEvent> {
            val snapshot = simulation.snapshot()
            check(snapshot.ended != null)
            val generation = generationStore.advance(snapshot.generation)
            simulation.restart(generation); restartAt = null
            return listOf(WorldEvent.Restarted(generation))
        }
        var storageFailure: Exception? = null
        try { for (command in queue) {
            if (durability != null) {
                try {
                    storageFailure?.let { throw java.io.IOException("Session storage is unavailable; restart and verify the journal", it) }
                    val next = transitionSession(rules, checkNotNull(durableState), command.sessionAction())
                    durability.commit(command.sessionAction(), next)
                    durableState = next.state
                    published.set(next.state.snapshot(rules))
                    if (command is WorldCommand.Join) {
                        if (next.rejected) command.result.completeExceptionally(IllegalArgumentException("Invalid join"))
                        else command.result.complete(checkNotNull(next.admission))
                    }
                    if (command is WorldCommand.RequestRestart) command.result.complete(!next.rejected)
                    next.events.forEach(onEvent)
                } catch (failure: Exception) {
                    storageFailure = failure
                    if (command is WorldCommand.Join) command.result.completeExceptionally(failure)
                    if (command is WorldCommand.RequestRestart) command.result.complete(false)
                }
                continue
            }
            var admission: Admission? = null
            var restartResult: Boolean? = null
            val events = try { when (command) {
                WorldCommand.Probe -> emptyList()
                is WorldCommand.AdvanceTo -> {
                    require(command.elapsedRealMillis >= elapsed)
                    val events = if (restartAt?.let { command.elapsedRealMillis >= it } == true) restart()
                        else simulation.advance(command.elapsedRealMillis - elapsed)
                    elapsed = command.elapsedRealMillis
                    if (events.any { it is WorldEvent.Ended }) restartAt = elapsed + rules.restartDelayRealMillis
                    events
                }
                is WorldCommand.Join -> { admission = if (command.generation != null && command.generation != simulation.snapshot().generation) Admission.STALE_SESSION else simulation.join(command.account, command.power, command.original, command.connection); emptyList() }
                is WorldCommand.Disconnect -> { simulation.disconnect(command.account, command.connection); emptyList() }
                is WorldCommand.Exclude -> { simulation.exclude(command.account); emptyList() }
                is WorldCommand.OpenBattle -> { simulation.openBattle(command.id); emptyList() }
                is WorldCommand.CloseBattle -> { simulation.closeBattle(command.id); emptyList() }
                is WorldCommand.Territory -> simulation.updateTerritory(command.systems, command.capturedCapitals).also {
                    if (it.isNotEmpty()) restartAt = elapsed + rules.restartDelayRealMillis
                }
                WorldCommand.Restart -> restart()
                is WorldCommand.RequestRestart -> restart().also { restartResult = true }
            } } catch (failure: Exception) {
                if (failure !is IllegalArgumentException && failure !is IllegalStateException && failure !is ArithmeticException && failure !is java.io.IOException) throw failure
                if (command is WorldCommand.Join) command.result.completeExceptionally(failure)
                state = state.copy(rejectedCommands = state.rejectedCommands + 1)
                if (command is WorldCommand.RequestRestart) restartResult = false
                emptyList()
            }
            state = state.copy(revision = state.revision + 1,
                acceptedCommands = state.acceptedCommands + if (command == WorldCommand.Probe) 1 else 0, session = simulation.snapshot())
            published.set(state)
            if (command is WorldCommand.Join && admission != null) command.result.complete(admission)
            if (command is WorldCommand.RequestRestart) command.result.complete(restartResult == true)
            events.forEach(onEvent)
        } } finally { durability?.close() }
    }
    private val ticker = if (ticking) scope.launch {
        while (isActive) { delay(minOf(rules.strategyTickRealMillis, rules.tacticsTickRealMillis)); queue.send(WorldCommand.AdvanceTo(Math.addExact(initialElapsed, monotonicMillis() - origin))) }
    } else null
    override suspend fun submit(command: WorldCommand) {
        queue.send(if (command is WorldCommand.Territory) command.copy(systems = command.systems.toMap(), capturedCapitals = command.capturedCapitals.toSet()) else command)
    }
    override fun snapshot(): WorldSnapshot = published.get()
    suspend fun drainAndClose() { ticker?.cancelAndJoin(); queue.close(); worker.join() }
    override fun close() { ticker?.cancel(); queue.close() }
}
