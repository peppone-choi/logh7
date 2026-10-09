package org.logh7.engine

/** Engine-owned seam: persistence must force the record before returning from commit. */
interface SessionDurability : AutoCloseable {
    fun load(): DurableWorldState
    fun commit(action: SessionAction, transition: SessionTransition)
}

data class DurableWorldState(val session: SessionState, val elapsed: Long = 0, val restartAt: Long? = null,
    val revision: Long = 0, val acceptedCommands: Long = 0, val rejectedCommands: Long = 0) {
    fun snapshot(rules: SessionRules) = WorldSnapshot(revision, acceptedCommands,
        SessionSimulation.restore(rules, session, false).snapshot(), rejectedCommands)
}

/** Journal commands contain no futures or object identity. */
sealed interface SessionAction {
    data object Probe : SessionAction
    data class Advance(val elapsed: Long) : SessionAction
    data class Join(val account: String, val power: Power, val original: Boolean,
        val connection: String?, val generation: Long?) : SessionAction
    data class Disconnect(val account: String, val connection: String?) : SessionAction
    data class Exclude(val account: String) : SessionAction
    data class OpenBattle(val id: String) : SessionAction
    data class CloseBattle(val id: String) : SessionAction
    data class Territory(val systems: Map<Power, Int>, val capitals: Set<Power>) : SessionAction
    data object Restart : SessionAction
    data object RecoverConnections : SessionAction
}

data class SessionTransition(val state: DurableWorldState, val admission: Admission? = null,
    val events: List<WorldEvent> = emptyList(), val rejected: Boolean = false)

/** The same deterministic transition is used for live candidate computation and verified replay. */
fun transitionSession(rules: SessionRules, before: DurableWorldState, action: SessionAction): SessionTransition {
    require(before.elapsed >= 0 && before.revision >= 0 && before.acceptedCommands >= 0 && before.rejectedCommands >= 0)
    require((before.restartAt != null) == (before.session.ended != null))
    before.restartAt?.let { require(it >= before.elapsed) }
    val simulation = SessionSimulation.restore(rules, before.session, false)
    var elapsed = before.elapsed
    var restartAt = before.restartAt
    var admission: Admission? = null
    fun restart(): List<WorldEvent> {
        simulation.restart(); restartAt = null
        return listOf(WorldEvent.Restarted(simulation.snapshot().generation))
    }
    val events = try { when (action) {
        SessionAction.Probe -> emptyList()
        is SessionAction.Advance -> {
            require(action.elapsed >= elapsed)
            val result = if (restartAt?.let { action.elapsed >= it } == true) restart()
                else simulation.advance(action.elapsed - elapsed)
            elapsed = action.elapsed
            if (result.any { it is WorldEvent.Ended }) restartAt = Math.addExact(elapsed, rules.restartDelayRealMillis)
            result
        }
        is SessionAction.Join -> {
            admission = if (action.generation != null && action.generation != before.session.generation) Admission.STALE_SESSION
                else simulation.join(action.account, action.power, action.original, action.connection)
            emptyList()
        }
        is SessionAction.Disconnect -> { simulation.disconnect(action.account, action.connection); emptyList() }
        is SessionAction.Exclude -> { simulation.exclude(action.account); emptyList() }
        is SessionAction.OpenBattle -> { simulation.openBattle(action.id); emptyList() }
        is SessionAction.CloseBattle -> { simulation.closeBattle(action.id); emptyList() }
        is SessionAction.Territory -> simulation.updateTerritory(action.systems, action.capitals).also {
            if (it.isNotEmpty()) restartAt = Math.addExact(elapsed, rules.restartDelayRealMillis)
        }
        SessionAction.Restart -> restart()
        SessionAction.RecoverConnections -> { before.session.participants.keys.forEach { simulation.disconnect(it) }; emptyList() }
    } } catch (failure: RuntimeException) {
        if (failure !is IllegalArgumentException && failure !is IllegalStateException && failure !is ArithmeticException) throw failure
        return SessionTransition(before.copy(revision = Math.addExact(before.revision, 1),
            rejectedCommands = Math.addExact(before.rejectedCommands, 1)), rejected = true)
    }
    return SessionTransition(before.copy(session = simulation.exportState(), elapsed = elapsed, restartAt = restartAt,
        revision = Math.addExact(before.revision, 1), acceptedCommands = Math.addExact(before.acceptedCommands,
            if (action == SessionAction.Probe) 1 else 0)), admission, events)
}

fun WorldCommand.sessionAction(): SessionAction = when (this) {
    WorldCommand.Probe -> SessionAction.Probe
    is WorldCommand.AdvanceTo -> SessionAction.Advance(elapsedRealMillis)
    is WorldCommand.Join -> SessionAction.Join(account, power, original, connection, generation)
    is WorldCommand.Disconnect -> SessionAction.Disconnect(account, connection)
    is WorldCommand.Exclude -> SessionAction.Exclude(account)
    is WorldCommand.OpenBattle -> SessionAction.OpenBattle(id)
    is WorldCommand.CloseBattle -> SessionAction.CloseBattle(id)
    is WorldCommand.Territory -> SessionAction.Territory(systems, capturedCapitals)
    WorldCommand.Restart, is WorldCommand.RequestRestart -> SessionAction.Restart
}
