package org.logh7.engine

enum class Power { EMPIRE, ALLIANCE }
enum class EndReason { CAPITAL_CAPTURED, SYSTEMS_REDUCED, TIME_LIMIT }
data class SessionEnd(val reason: EndReason, val affected: Set<Power>, val gameSeconds: Long,
    val affectedCountries: Set<CountryId> = affected.map { it.country }.toSet(),
    /** null is a legacy result without a recorded roster; empty means every country was eliminated. */
    val remainingCountries: Set<CountryId>? = null) {
    init {
        require(affected == Power.entries.filter { it.country in affectedCountries }.toSet())
        require(affectedCountries.intersect(remainingCountries.orEmpty()).isEmpty())
    }
}
data class CountryDefeat(val reason: EndReason, val gameSeconds: Long, val captor: CountryId? = null) {
    init { require(reason != EndReason.TIME_LIMIT && gameSeconds >= 0) }
}
data class Participant(val country: CountryId?, val original: Boolean, val online: Boolean = true, val connection: String? = null) {
    constructor(power: Power, original: Boolean, online: Boolean = true, connection: String? = null) :
        this(power.country, original, online, connection)
    val power: Power get() = country.legacyPower()
}
enum class Admission { ACCEPTED, FULL, ENDED, FACTION_CHANGED, ORIGINAL_RETURN_FORBIDDEN, IDENTITY_CHANGED, ALREADY_ONLINE, STALE_SESSION, COUNTRY_ELIMINATED }
data class BattleState(val startMillis: Long, val ticks: Long)
/** Full domain state. Connection fields are cleared only during recovery, never during live cloning. */
data class SessionState(val generation: Long, val gameMillis: Long, val strategyTicks: Long,
    val recoveries: Long, val participants: Map<String, Participant>, val excluded: Map<String, CountryId?>,
    val battles: Map<String, BattleState>, val ended: SessionEnd?,
    val countries: Set<CountryId> = emptySet(), val eliminated: Map<CountryId, CountryDefeat> = emptyMap())
data class SessionSnapshot(val generation: Long, val gameSeconds: Long, val strategyTicks: Long,
    val cpRecoveryEvents: Long, val tacticalTicks: Map<String, Long>, val participants: Int, val online: Int,
    val ended: SessionEnd?, val countries: Set<CountryId> = emptySet(),
    val eliminated: Map<CountryId, CountryDefeat> = emptyMap(),
    val elapsedGameSeconds: Long = gameSeconds)
sealed interface WorldEvent {
    data class StrategyTicks(val first: Long, val count: Long) : WorldEvent
    data class TacticalTicks(val instance: String, val first: Long, val count: Long) : WorldEvent
    data class CpRecovery(val firstGameSeconds: Long, val intervalSeconds: Long, val count: Long) : WorldEvent
    data class Ended(val end: SessionEnd) : WorldEvent
    data class CountryEliminated(val country: CountryId, val defeat: CountryDefeat) : WorldEvent
    data class Restarted(val generation: Long) : WorldEvent
}

/** Deterministic state, mutated only by WorldEngine's actor. Batched events preserve missed ticks. */
class SessionSimulation(private val rules: SessionRules, private var generation: Long = 1) {
    init { require(generation > 0) }
    private var gameMillis = rules.startSeconds * 1000
    private var strategyTicks = 0L
    private var recoveries = 0L
    private val participants = mutableMapOf<String, Participant>()
    private val excluded = mutableMapOf<String, CountryId?>()
    private data class Battle(val startMillis: Long, var ticks: Long = 0)
    private val battles = mutableMapOf<String, Battle>()
    private var ended: SessionEnd? = null
    private val countries = linkedSetOf<CountryId>()
    private val eliminated = linkedMapOf<CountryId, CountryDefeat>()
    fun exportState() = SessionState(generation, gameMillis, strategyTicks, recoveries,
        participants.toMap(), excluded.toMap(), battles.mapValues { BattleState(it.value.startMillis, it.value.ticks) },
        ended?.detached(), countries.toSet(), eliminated.toMap())
    companion object {
        fun restore(rules: SessionRules, state: SessionState, clearConnections: Boolean = true): SessionSimulation {
            require(state.generation > 0 && state.gameMillis in rules.startSeconds * 1000..rules.endSeconds * 1000)
            val elapsed = state.gameMillis - rules.startSeconds * 1000
            require(state.strategyTicks == elapsed / (rules.strategyTickRealMillis * rules.speed))
            require(state.recoveries == elapsed / (rules.cpIntervalSeconds * 1000))
            require(state.participants.size <= rules.capacity && state.participants.keys.all { it.isNotBlank() })
            require(state.excluded.keys.all { it.isNotBlank() })
            state.participants.forEach { (id, participant) ->
                require(participant.online || participant.connection == null)
                if (id in state.excluded) require(!participant.original && state.excluded[id] == participant.country)
            }
            state.battles.forEach { (id, battle) ->
                require(id.isNotBlank() && battle.startMillis in rules.startSeconds * 1000..state.gameMillis)
                require(battle.ticks == (state.gameMillis - battle.startMillis) / (rules.tacticsTickRealMillis * rules.speed))
            }
            require(state.countries.size <= 350 && state.countries.containsAll(state.eliminated.keys))
            state.eliminated.forEach { (country, defeat) ->
                require(defeat.gameSeconds in rules.startSeconds..state.gameMillis / 1000)
                require(defeat.captor == null || defeat.captor in state.countries && defeat.captor != country)
            }
            if (state.countries.isNotEmpty() && state.ended == null)
                require(state.countries.size - state.eliminated.size > 1)
            state.ended?.let {
                require(it.gameSeconds == state.gameMillis / 1000)
                if (it.reason == EndReason.TIME_LIMIT) require(it.affectedCountries.isEmpty() && state.gameMillis == rules.endSeconds * 1000)
                else require(it.affectedCountries.isNotEmpty())
                if (state.countries.isNotEmpty()) {
                    require(it.remainingCountries == state.countries - state.eliminated.keys)
                    if (it.reason != EndReason.TIME_LIMIT) {
                        require(it.affectedCountries == state.eliminated.keys && checkNotNull(it.remainingCountries).size <= 1)
                    }
                }
            }
            require(state.gameMillis != rules.endSeconds * 1000 || state.ended != null)
            return SessionSimulation(rules, state.generation).apply {
                gameMillis = state.gameMillis; strategyTicks = state.strategyTicks; recoveries = state.recoveries
                participants.putAll(state.participants.mapValues { if (clearConnections) it.value.copy(online = false, connection = null) else it.value.copy() })
                excluded.putAll(state.excluded)
                state.battles.forEach { (id, battle) -> battles[id] = Battle(battle.startMillis, battle.ticks) }
                ended = state.ended?.detached()
                countries.addAll(state.countries); eliminated.putAll(state.eliminated)
            }
        }
    }
    fun snapshot() = SessionSnapshot(generation, gameMillis / 1000, strategyTicks, recoveries,
        battles.mapValues { it.value.ticks }, participants.size, participants.values.count { it.online }, ended?.detached(),
        countries.toSet(), eliminated.toMap(), elapsedGameSeconds = gameMillis / 1000 - rules.startSeconds)
    fun join(account: String, power: Power, original: Boolean, connection: String? = null): Admission {
        return join(account, power.country, original, connection)
    }
    fun join(account: String, country: CountryId?, original: Boolean, connection: String? = null): Admission {
        require(account.isNotBlank())
        if (ended != null) return Admission.ENDED
        if (country in eliminated) return Admission.COUNTRY_ELIMINATED
        participants[account]?.let {
            if (it.country != country || it.original != original) return Admission.IDENTITY_CHANGED
            if (it.online && it.connection != null && it.connection != connection) return Admission.ALREADY_ONLINE
            participants[account] = it.copy(online = true, connection = connection)
            return Admission.ACCEPTED
        }
        if (account in excluded) {
            if (original) return Admission.ORIGINAL_RETURN_FORBIDDEN
            if (excluded[account] != country) return Admission.FACTION_CHANGED
        }
        if (participants.size >= rules.capacity) return Admission.FULL
        participants[account] = Participant(country, original, connection = connection)
        return Admission.ACCEPTED
    }
    fun disconnect(account: String, connection: String? = null) {
        participants[account]?.let { if (connection == null || it.connection == connection) participants[account] = it.copy(online = false, connection = null) }
    }
    fun exclude(account: String) { participants.remove(account)?.let { excluded[account] = it.country } }
    /** U-25 亡命 (user decision 2026-11-01): the only sanctioned faction change of a participant. */
    fun defect(account: String, power: Power) {
        defect(account, power.country)
    }
    fun defect(account: String, country: CountryId?) {
        val current = checkNotNull(participants[account]) { "Unknown participant" }
        require(current.country !in eliminated && country !in eliminated)
        require(current.country != country)
        participants[account] = current.copy(country = country)
    }
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
        require(countries.isEmpty()) { "Legacy territory cannot replace country elimination state" }
        if (capturedCapitals.isNotEmpty()) return listOf(finish(EndReason.CAPITAL_CAPTURED, capturedCapitals.toSet()))
        val reduced = controlledSystems.filterValues { it <= 3 }.keys
        return if (reduced.isEmpty()) emptyList() else listOf(finish(EndReason.SYSTEMS_REDUCED, reduced.toSet()))
    }
    /** User decision 2026-11-06: only the defeated country leaves; the last survivor ends the session.
     * Reduction eligibility is explicit so a newly founded, one-base country is not defeated at birth. */
    fun updateCountryTerritory(controlledSystems: Map<CountryId, Int>, capturedCapitals: Map<CountryId, CountryId?>,
        reductionEligible: Set<CountryId> = emptySet()): List<WorldEvent> {
        require(controlledSystems.size in 2..350 && controlledSystems.values.all { it >= 0 })
        require(controlledSystems.keys.containsAll(countries + capturedCapitals.keys + reductionEligible))
        require(capturedCapitals.all { (country, captor) -> captor == null || captor != country && captor in controlledSystems })
        if (ended != null) return emptyList()
        countries.addAll(controlledSystems.keys)
        val events = mutableListOf<WorldEvent>()
        for (country in countries.sortedBy { it.value }) {
            if (country in eliminated) continue
            val reason = when {
                country in capturedCapitals -> EndReason.CAPITAL_CAPTURED
                country in reductionEligible && controlledSystems.getValue(country) <= 3 -> EndReason.SYSTEMS_REDUCED
                else -> continue
            }
            val defeat = CountryDefeat(reason, gameMillis / 1000, capturedCapitals[country])
            eliminated[country] = defeat
            events += WorldEvent.CountryEliminated(country, defeat)
        }
        if (countries.size - eliminated.size <= 1) {
            val reason = if (events.filterIsInstance<WorldEvent.CountryEliminated>().any { it.defeat.reason == EndReason.CAPITAL_CAPTURED })
                EndReason.CAPITAL_CAPTURED else EndReason.SYSTEMS_REDUCED
            events += finish(reason, Power.entries.filter { it.country in eliminated }.toSet())
        }
        return events
    }
    private fun finish(reason: EndReason, affected: Set<Power>): WorldEvent.Ended {
        val affectedCountries = if (reason == EndReason.TIME_LIMIT) emptySet() else
            if (countries.isEmpty()) affected.map { it.country }.toSet() else eliminated.keys.toSet()
        val end = SessionEnd(reason, affected, gameMillis / 1000, affectedCountries,
            if(countries.isEmpty()) null else countries - eliminated.keys)
        ended = end
        return WorldEvent.Ended(end.detached())
    }
    fun restart(nextGeneration: Long = Math.addExact(generation, 1)) {
        require(ended != null) { "Only an ended session can restart" }
        require(nextGeneration == Math.addExact(generation, 1))
        generation = nextGeneration
        gameMillis = rules.startSeconds * 1000; strategyTicks = 0; recoveries = 0
        participants.clear(); excluded.clear(); battles.clear(); ended = null; countries.clear(); eliminated.clear()
    }
}

private fun SessionEnd.detached() = copy(affected = affected.toSet(), affectedCountries = affectedCountries.toSet(),
    remainingCountries = remainingCountries?.toSet())
