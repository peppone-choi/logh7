package org.logh7.persistence

import org.logh7.engine.*
import java.io.*
import java.security.MessageDigest

/** Explicit versioned binary format, bounded framing, no Java object deserialization. */
internal object SessionCodec {
    const val MAX_FRAME = 8 * 1024 * 1024
    private const val SCHEMA = 1
    fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun fingerprint(rules: SessionRules): String = digest(output {
        writeLong(rules.speed); writeInt(rules.capacity); writeLong(rules.cpIntervalSeconds); writeInt(rules.baseYear)
        listOf(rules.start, rules.end).forEach {
            writeInt(it.year); writeInt(it.month); writeInt(it.day); writeInt(it.hour); writeInt(it.minute); writeInt(it.second)
        }
        writeLong(rules.strategyTickRealMillis); writeLong(rules.tacticsTickRealMillis); writeLong(rules.restartDelayRealMillis)
    }).joinToString("") { "%02x".format(it) }

    data class Checkpoint(val cutSeq: Long, val genesis: DurableWorldState, val state: DurableWorldState)
    data class Entry(val seq: Long, val action: SessionAction, val transition: SessionTransition)
    private fun DataOutputStream.header(fingerprint: String, seq: Long) {
        writeInt(SCHEMA); writeUTF(fingerprint); writeLong(seq)
    }
    private fun DataInputStream.header(fingerprint: String): Long {
        require(readInt() == SCHEMA) { "Unsupported session schema" }
        require(readUTF() == fingerprint) { "Session rules fingerprint mismatch" }
        return readLong().also { require(it >= 0) }
    }
    fun checkpoint(fingerprint: String, value: Checkpoint) = output {
        header(fingerprint, value.cutSeq); world(value.genesis); world(value.state)
    }
    fun readCheckpoint(fingerprint: String, bytes: ByteArray) = input(bytes) {
        Checkpoint(header(fingerprint), world(), world())
    }
    fun entry(fingerprint: String, value: Entry) = output {
        header(fingerprint, value.seq); action(value.action); world(value.transition.state)
        optional(value.transition.admission) { writeUTF(it.name) }; writeBoolean(value.transition.rejected)
        count(value.transition.events.size); value.transition.events.forEach { event(it) }
    }
    fun readEntry(fingerprint: String, bytes: ByteArray) = input(bytes) {
        val seq = header(fingerprint); val action = action(); val state = world()
        val admission = optional { Admission.valueOf(readUTF()) }; val rejected = readBoolean()
        Entry(seq, action, SessionTransition(state, admission, List(count()) { event() }, rejected))
    }
    fun worldBytes(value: DurableWorldState) = output { world(value) }
    private fun output(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(write)
    }.toByteArray().also { require(it.size <= MAX_FRAME) }
    private fun <T> input(bytes: ByteArray, read: DataInputStream.() -> T): T =
        DataInputStream(ByteArrayInputStream(bytes)).use { stream -> read(stream).also { require(stream.available() == 0) { "Trailing session data" } } }
    private fun DataInputStream.count() = readInt().also { require(it in 0..100_000) }
    private fun DataOutputStream.count(value: Int) { require(value in 0..100_000); writeInt(value) }
    private fun <T> DataOutputStream.optional(value: T?, write: DataOutputStream.(T) -> Unit) {
        writeBoolean(value != null); if (value != null) write(value)
    }
    private fun <T> DataInputStream.optional(read: DataInputStream.() -> T): T? = if (readBoolean()) read.invoke(this) else null
    private fun DataOutputStream.powers(value: Set<Power>) {
        count(value.size); value.sortedBy { it.ordinal }.forEach { writeUTF(it.name) }
    }
    private fun DataInputStream.powers(): Set<Power> {
        val values = List(count()) { Power.valueOf(readUTF()) }; require(values.distinct().size == values.size)
        return values.toSet()
    }
    private fun DataOutputStream.end(value: SessionEnd) {
        writeUTF(value.reason.name); powers(value.affected); writeLong(value.gameSeconds)
    }
    private fun DataInputStream.end() = SessionEnd(EndReason.valueOf(readUTF()), powers(), readLong())
    private fun DataOutputStream.world(value: DurableWorldState) {
        writeLong(value.elapsed); optional(value.restartAt) { writeLong(it) }; writeLong(value.revision)
        writeLong(value.acceptedCommands); writeLong(value.rejectedCommands)
        val s = value.session
        writeLong(s.generation); writeLong(s.gameMillis); writeLong(s.strategyTicks); writeLong(s.recoveries)
        count(s.participants.size); s.participants.forEach { (id, p) ->
            writeUTF(id); writeUTF(p.power.name); writeBoolean(p.original); writeBoolean(p.online); optional(p.connection) { writeUTF(it) }
        }
        count(s.excluded.size); s.excluded.forEach { (id, power) -> writeUTF(id); writeUTF(power.name) }
        count(s.battles.size); s.battles.forEach { (id, battle) -> writeUTF(id); writeLong(battle.startMillis); writeLong(battle.ticks) }
        optional(s.ended) { end(it) }
    }
    private fun DataInputStream.world(): DurableWorldState {
        val elapsed = readLong(); val restart = optional { readLong() }; val revision = readLong()
        val accepted = readLong(); val rejected = readLong()
        val generation = readLong(); val millis = readLong(); val strategy = readLong(); val recoveries = readLong()
        val participants = linkedMapOf<String, Participant>()
        repeat(count()) {
            val id = readUTF(); val p = Participant(Power.valueOf(readUTF()), readBoolean(), readBoolean(), optional { readUTF() })
            require(participants.put(id, p) == null) { "Duplicate participant" }
        }
        val excluded = linkedMapOf<String, Power>()
        repeat(count()) { require(excluded.put(readUTF(), Power.valueOf(readUTF())) == null) { "Duplicate exclusion" } }
        val battles = linkedMapOf<String, BattleState>()
        repeat(count()) { require(battles.put(readUTF(), BattleState(readLong(), readLong())) == null) { "Duplicate battle" } }
        return DurableWorldState(SessionState(generation, millis, strategy, recoveries, participants, excluded, battles,
            optional { end() }), elapsed, restart, revision, accepted, rejected)
    }
    private fun DataOutputStream.action(value: SessionAction) { when (value) {
        SessionAction.Probe -> writeByte(0)
        is SessionAction.Advance -> { writeByte(1); writeLong(value.elapsed) }
        is SessionAction.Join -> { writeByte(2); writeUTF(value.account); writeUTF(value.power.name); writeBoolean(value.original)
            optional(value.connection) { writeUTF(it) }; optional(value.generation) { writeLong(it) } }
        is SessionAction.Disconnect -> { writeByte(3); writeUTF(value.account); optional(value.connection) { writeUTF(it) } }
        is SessionAction.Exclude -> { writeByte(4); writeUTF(value.account) }
        is SessionAction.OpenBattle -> { writeByte(5); writeUTF(value.id) }
        is SessionAction.CloseBattle -> { writeByte(6); writeUTF(value.id) }
        is SessionAction.Territory -> { writeByte(7); count(value.systems.size)
            value.systems.entries.sortedBy { it.key.ordinal }.forEach { writeUTF(it.key.name); writeInt(it.value) }; powers(value.capitals) }
        SessionAction.Restart -> writeByte(8)
        SessionAction.RecoverConnections -> writeByte(9)
    } }
    private fun DataInputStream.action(): SessionAction = when (readUnsignedByte()) {
        0 -> SessionAction.Probe
        1 -> SessionAction.Advance(readLong())
        2 -> SessionAction.Join(readUTF(), Power.valueOf(readUTF()), readBoolean(), optional { readUTF() }, optional { readLong() })
        3 -> SessionAction.Disconnect(readUTF(), optional { readUTF() })
        4 -> SessionAction.Exclude(readUTF())
        5 -> SessionAction.OpenBattle(readUTF())
        6 -> SessionAction.CloseBattle(readUTF())
        7 -> {
            val systems = linkedMapOf<Power, Int>()
            repeat(count()) { require(systems.put(Power.valueOf(readUTF()), readInt()) == null) }
            SessionAction.Territory(systems, powers())
        }
        8 -> SessionAction.Restart
        9 -> SessionAction.RecoverConnections
        else -> error("Unknown journal action")
    }
    private fun DataOutputStream.event(value: WorldEvent) { when (value) {
        is WorldEvent.StrategyTicks -> { writeByte(0); writeLong(value.first); writeLong(value.count) }
        is WorldEvent.TacticalTicks -> { writeByte(1); writeUTF(value.instance); writeLong(value.first); writeLong(value.count) }
        is WorldEvent.CpRecovery -> { writeByte(2); writeLong(value.firstGameSeconds); writeLong(value.intervalSeconds); writeLong(value.count) }
        is WorldEvent.Ended -> { writeByte(3); end(value.end) }
        is WorldEvent.Restarted -> { writeByte(4); writeLong(value.generation) }
    } }
    private fun DataInputStream.event(): WorldEvent = when (readUnsignedByte()) {
        0 -> WorldEvent.StrategyTicks(readLong(), readLong())
        1 -> WorldEvent.TacticalTicks(readUTF(), readLong(), readLong())
        2 -> WorldEvent.CpRecovery(readLong(), readLong(), readLong())
        3 -> WorldEvent.Ended(end())
        4 -> WorldEvent.Restarted(readLong())
        else -> error("Unknown journal event")
    }
}
