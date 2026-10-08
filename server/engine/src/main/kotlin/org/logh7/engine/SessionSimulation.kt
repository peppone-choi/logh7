package org.logh7.engine

enum class Power { EMPIRE, ALLIANCE }
enum class EndReason { CAPITAL_CAPTURED, SYSTEMS_REDUCED, TIME_LIMIT }
data class SessionEnd(val reason: EndReason, val affected: Set<Power>, val gameSeconds: Long)
data class Participant(val power: Power, val original: Boolean, val online: Boolean = true, val connection: String? = null)
enum class Admission { ACCEPTED, FULL, ENDED, FACTION_CHANGED, ORIGINAL_RETURN_FORBIDDEN, IDENTITY_CHANGED, ALREADY_ONLINE, STALE_SESSION }
data class SessionSnapshot(val generation: Long, val gameSeconds: Long, val strategyTicks: Long,
    val cpRecoveryEvents: Long, val tacticalTicks: Map<String, Long>, val participants: Int, val online: Int,
    val ended: SessionEnd?)
sealed interface WorldEvent {
    data class StrategyTicks(val first: Long, val count: Long) : WorldEvent
    data class TacticalTicks(val instance: String, val first: Long, val count: Long) : WorldEvent
    data class CpRecovery(val firstGameSeconds: Long, val intervalSeconds: Long, val count: Long) : WorldEvent
    data class Ended(val end: SessionEnd) : WorldEvent
}

/** Deterministic state, mutated only by WorldEngine's actor. Batched events preserve missed ticks. */
class SessionSimulation(private val rules: SessionRules) {
    private var generation = 1L
    private var gameMillis = rules.startSeconds * 1000
    private var strategyTicks = 0L
    private var recoveries = 0L
    private val participants = mutableMapOf<String, Participant>()
    private val excluded = mutableMapOf<String, Power>()
    private data class Battle(val startMillis: Long, var ticks: Long = 0)
    private val battles = mutableMapOf<String, Battle>()
    private var ended: SessionEnd? = null
    fun snapshot() = SessionSnapshot(generation, gameMillis / 1000, strategyTicks, recoveries,
        battles.mapValues { it.value.ticks }, participants.size, participants.values.count { it.online }, ended)
    fun join(account: String, power: Power, original: Boolean, connection: String? = null): Admission {
        require(account.isNotBlank())
        if (ended != null) return Admission.ENDED
        participants[account]?.let {
            if (it.power != power || it.original != original) return Admission.IDENTITY_CHANGED
            if (it.online && it.connection != null && it.connection != connection) return Admission.ALREADY_ONLINE
            participants[account] = it.copy(online = true, connection = connection)
            return Admission.ACCEPTED
        }
        excluded[account]?.let {
            if (original) return Admission.ORIGINAL_RETURN_FORBIDDEN
            if (it != power) return Admission.FACTION_CHANGED
        }
        if (participants.size >= rules.capacity) return Admission.FULL
        participants[account] = Participant(power, original, connection = connection)
        return Admission.ACCEPTED
    }
    fun disconnect(account: String, connection: String? = null) {
        participants[account]?.let { if (connection == null || it.connection == connection) participants[account] = it.copy(online = false, connection = null) }
    }
    fun exclude(account: String) { participants.remove(account)?.let { excluded[account] = it.power } }
    fun openBattle(id: String) {
        require(id.isNotBlank() && ended == null)
        require(id !in battles)
        battles[id] = Battle(gameMillis)
    }
    fun closeBattle(id: String) { battles.remove(id) }
    fun advance(realMillis: Long): List<WorldEvent> {
        require(realMillis >= 0)
        if (ended != null || realMillis == 0L) return emptyList()
        val remaining = rules.endSeconds * 1000 - gameMillis
        val delta = if (realMillis > remaining / rules.speed) remaining else realMillis * rules.speed
        gameMillis += delta
        val elapsed = gameMillis - rules.startSeconds * 1000
        val events = mutableListOf<WorldEvent>()
        val nextStrategy = elapsed / (rules.strategyTickRealMillis * rules.speed)
        if (nextStrategy > strategyTicks) events += WorldEvent.StrategyTicks(strategyTicks + 1, nextStrategy - strategyTicks)
        strategyTicks = nextStrategy
        val nextRecovery = elapsed / (rules.cpIntervalSeconds * 1000)
        if (nextRecovery > recoveries) events += WorldEvent.CpRecovery(rules.startSeconds + (recoveries + 1) * rules.cpIntervalSeconds,
            rules.cpIntervalSeconds, nextRecovery - recoveries)
        recoveries = nextRecovery
        battles.forEach { (id, battle) ->
            val nextTicks = (gameMillis - battle.startMillis) / (rules.tacticsTickRealMillis * rules.speed)
            if (nextTicks > battle.ticks) events += WorldEvent.TacticalTicks(id, battle.ticks + 1, nextTicks - battle.ticks)
            battle.ticks = nextTicks
        }
        if (gameMillis == rules.endSeconds * 1000) events += finish(EndReason.TIME_LIMIT, emptySet())
        return events
    }
    fun updateTerritory(controlledSystems: Map<Power, Int>, capturedCapitals: Set<Power>): List<WorldEvent> {
        require(controlledSystems.keys == Power.entries.toSet() && controlledSystems.values.all { it >= 0 })
        if (ended != null) return emptyList()
        if (capturedCapitals.isNotEmpty()) return listOf(finish(EndReason.CAPITAL_CAPTURED, capturedCapitals.toSet()))
        val reduced = controlledSystems.filterValues { it <= 3 }.keys
        return if (reduced.isEmpty()) emptyList() else listOf(finish(EndReason.SYSTEMS_REDUCED, reduced.toSet()))
    }
    private fun finish(reason: EndReason, affected: Set<Power>): WorldEvent.Ended {
        val end = SessionEnd(reason, affected, gameMillis / 1000)
        ended = end
        return WorldEvent.Ended(end)
    }
    fun restart() {
        require(ended != null) { "Only an ended session can restart" }
        generation++
        gameMillis = rules.startSeconds * 1000; strategyTicks = 0; recoveries = 0
        participants.clear(); excluded.clear(); battles.clear(); ended = null
    }
}
