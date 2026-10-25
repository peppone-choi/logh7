package org.logh7.engine.strategy

import java.util.Collections

class CardPermissions(mapping: Map<String, Set<String>>, val maximumCards: Int = 16) {
    private val mapping = mapping.mapValues { (_, commands) -> commands.toSet() }.toMap()
    init { require(maximumCards == 16 && this.mapping.keys.all { it.isNotBlank() }) }
    fun allows(cards: List<String>, command: String): Boolean {
        if (cards.size > maximumCards || cards.distinct().size != cards.size || cards.any { it.isBlank() }) return false
        return cards.any { command in mapping[it].orEmpty() }
    }
}
/** All quantities are supplied by authoritative policies; no table-hour convention is assumed. */
data class CommandResolution(val cost: Long, val pool: CpPool?, val waitGameMillis: Long, val durationGameMillis: Long,
    val tableMillisPerUnit: Long? = null)
fun interface CommandResolver { fun resolve(command: CommandDefinition): CommandResolution? }
enum class JobState { WAITING, RUNNING, COMPLETED }
data class StrategyJob(val id: Long, val command: String, val acceptedAtGameMillis: Long,
    val startsAtGameMillis: Long, val completesAtGameMillis: Long, val payment: PaymentResult.Paid, val state: JobState)
enum class JobEventKind { STARTED, COMPLETED }
data class StrategyJobEvent(val job: Long, val kind: JobEventKind, val gameMillis: Long)

/** Persist with the command's balances and effects, never as an independent job receipt. */
class StrategyCheckpoint(val nextId: Long = 0, val lastGameMillis: Long = 0, jobs: List<StrategyJob> = emptyList()) {
    val jobs: List<StrategyJob> = Collections.unmodifiableList(ArrayList(jobs))
    override fun equals(other: Any?) = other is StrategyCheckpoint && nextId == other.nextId &&
        lastGameMillis == other.lastGameMillis && jobs == other.jobs
    override fun hashCode() = 31 * (31 * nextId.hashCode() + lastGameMillis.hashCode()) + jobs.hashCode()
    init {
        require(nextId >= 0 && lastGameMillis >= 0 && jobs.size <= 128)
        require(jobs.map { it.id }.distinct().size == jobs.size)
        require(jobs.all { it.id in 0 until nextId && it.command.isNotBlank() && it.command.length <= 256 &&
            it.acceptedAtGameMillis in 0..lastGameMillis && it.startsAtGameMillis >= it.acceptedAtGameMillis &&
            it.completesAtGameMillis >= it.startsAtGameMillis && it.payment.spent >= 0 &&
            it.payment.experienceEligibleCp in 0..it.payment.spent &&
            (if (it.payment.spent == 0L) it.payment.chargedPool == null && !it.payment.substituted else it.payment.chargedPool != null) &&
            it.payment.experienceEligibleCp == (if (it.payment.substituted) 0 else it.payment.spent) &&
            (it.state != JobState.RUNNING || it.startsAtGameMillis <= lastGameMillis) &&
            (it.state != JobState.COMPLETED || it.completesAtGameMillis <= lastGameMillis) })
    }
}
sealed interface SubmissionResult {
    data class Accepted(val job: StrategyJob) : SubmissionResult
    data class Rejected(val reason: StrategyRejection) : SubmissionResult
}

/** A single writer owns this component. It has no system clock, game effect or network adapter. */
class StrategyPipeline(private val catalog: StrategyCatalog, private val permissions: CardPermissions,
    private val resolver: CommandResolver, enabledCommands: Set<String>, initialBalance: CpBalance,
    checkpoint: StrategyCheckpoint = StrategyCheckpoint()) {
    private val enabled = enabledCommands.toSet()
    var balance = initialBalance; private set
    private val jobs = checkpoint.jobs.toMutableList()
    private var nextId = checkpoint.nextId
    private var lastGameMillis = checkpoint.lastGameMillis
    init { require(enabled.all { it in catalog.byId } && jobs.all { it.command in catalog.byId }) }
    fun jobs(): List<StrategyJob> = Collections.unmodifiableList(ArrayList(jobs))
    /** Applied effects allow completed jobs to be dropped in the same durable transaction. */
    fun checkpoint(dropCompleted: Boolean = false) = StrategyCheckpoint(nextId, lastGameMillis,
        if (dropCompleted) jobs.filter { it.state != JobState.COMPLETED } else jobs)
    fun submit(commandId: String, ownedCards: List<String>, gameMillis: Long, inTacticalGame: Boolean): SubmissionResult {
        fun reject(reason: StrategyRejection) = SubmissionResult.Rejected(reason)
        if (gameMillis < lastGameMillis) return reject(StrategyRejection.TIME_REVERSED)
        if (inTacticalGame) return reject(StrategyRejection.IN_TACTICAL_GAME)
        if (jobs.size >= 128) return reject(StrategyRejection.QUEUE_FULL)
        val definition = catalog.byId[commandId] ?: return reject(StrategyRejection.UNKNOWN_COMMAND)
        if (!permissions.allows(ownedCards.toList(), commandId)) return reject(StrategyRejection.UNAUTHORIZED)
        if (commandId !in enabled) return reject(StrategyRejection.DISABLED)
        if (definition.cp == TableValue.Unspecified) return reject(StrategyRejection.UNRESOLVED_CP)
        if (definition.wait == TableValue.Unspecified || definition.duration == TableValue.Unspecified)
            return reject(StrategyRejection.UNRESOLVED_TIMING)
        val resolution = resolver.resolve(definition) ?: return reject(StrategyRejection.UNRESOLVED_TIMING)
        val needsUnits = listOf(definition.wait, definition.duration).any {
            it is TableValue.Range || (it is TableValue.Fixed && it.value > 0)
        }
        if (needsUnits && resolution.tableMillisPerUnit == null) return reject(StrategyRejection.UNRESOLVED_TIMING)
        fun cpMatches(value: TableValue): Boolean = when (value) {
            is TableValue.Fixed -> resolution.cost == value.value
            is TableValue.Range -> resolution.cost in value.minimum..value.maximum
            is TableValue.DistanceProportional -> resolution.cost >= 0 // Explicit resolver; no distance formula here.
            TableValue.Unspecified -> false
        }
        fun timeMatches(value: TableValue, millis: Long): Boolean = when (value) {
            is TableValue.Fixed -> if (value.value == 0L) millis == 0L else
                millis == Math.multiplyExact(value.value, resolution.tableMillisPerUnit!!)
            is TableValue.Range -> millis % resolution.tableMillisPerUnit!! == 0L &&
                millis / resolution.tableMillisPerUnit in value.minimum..value.maximum
            is TableValue.DistanceProportional -> true
            TableValue.Unspecified -> false
        }
        if (resolution.tableMillisPerUnit?.let { it <= 0 } == true) return reject(StrategyRejection.INVALID_RESOLUTION)
        val timingMatches = try { timeMatches(definition.wait, resolution.waitGameMillis) && timeMatches(definition.duration, resolution.durationGameMillis) }
            catch (_: ArithmeticException) { return reject(StrategyRejection.OVERFLOW) }
        if (!cpMatches(definition.cp) || resolution.cost < 0 || resolution.waitGameMillis < 0 || resolution.durationGameMillis < 0 || !timingMatches)
            return reject(StrategyRejection.INVALID_RESOLUTION)
        val payment = CpPayment.charge(balance, resolution.cost, resolution.pool)
        if (payment is PaymentResult.Rejected) return reject(payment.reason)
        payment as PaymentResult.Paid
        val start: Long; val complete: Long; val followingId: Long
        try {
            start = Math.addExact(gameMillis, resolution.waitGameMillis)
            complete = Math.addExact(start, resolution.durationGameMillis)
            followingId = Math.addExact(nextId, 1)
        } catch (_: ArithmeticException) { return reject(StrategyRejection.OVERFLOW) }
        val job = StrategyJob(nextId, commandId, gameMillis, start, complete, payment, JobState.WAITING)
        jobs += job; balance = payment.balance; nextId = followingId; lastGameMillis = gameMillis
        return SubmissionResult.Accepted(job)
    }
    fun advance(gameMillis: Long): List<StrategyJobEvent> {
        require(gameMillis >= lastGameMillis)
        val events = mutableListOf<StrategyJobEvent>()
        for ((index, job) in jobs.withIndex()) {
            var state = job.state
            if (state == JobState.WAITING && job.startsAtGameMillis <= gameMillis) {
                events += StrategyJobEvent(job.id, JobEventKind.STARTED, job.startsAtGameMillis); state = JobState.RUNNING
            }
            if (state == JobState.RUNNING && job.completesAtGameMillis <= gameMillis) {
                events += StrategyJobEvent(job.id, JobEventKind.COMPLETED, job.completesAtGameMillis); state = JobState.COMPLETED
            }
            jobs[index] = job.copy(state = state)
        }
        lastGameMillis = gameMillis
        return events.sortedWith(compareBy<StrategyJobEvent> { it.gameMillis }.thenBy { it.job }.thenBy { it.kind.ordinal })
    }
}
