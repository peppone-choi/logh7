package org.logh7.persistence

import org.logh7.engine.*
import java.io.*
import java.security.MessageDigest

/** Explicit versioned binary format, bounded framing, no Java object deserialization. */
internal object SessionCodec {
    const val MAX_FRAME = 8 * 1024 * 1024
    private const val SCHEMA = 3
    fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun fingerprint(rules: SessionRules): String = digest(output {
        writeLong(rules.speed); writeInt(rules.capacity); writeLong(rules.cpIntervalSeconds); writeInt(rules.baseYear)
        listOf(rules.start, rules.end).forEach {
            writeInt(it.year); writeInt(it.month); writeInt(it.day); writeInt(it.hour); writeInt(it.minute); writeInt(it.second)
        }
        writeLong(rules.strategyTickRealMillis); writeLong(rules.tacticsTickRealMillis); writeLong(rules.restartDelayRealMillis)
    }).joinToString("") { "%02x".format(it) }

    data class Checkpoint(val cutSeq: Long, val genesis: DurableWorldState, val state: DurableWorldState, val schema: Int = SCHEMA)
    data class Entry(val seq: Long, val action: SessionAction, val transition: SessionTransition, val schema: Int = SCHEMA)
    private fun DataOutputStream.header(fingerprint: String, seq: Long, schema: Int) {
        require(schema in 1..SCHEMA)
        writeInt(schema); writeUTF(fingerprint); writeLong(seq)
    }
    private fun DataInputStream.header(fingerprint: String): Pair<Int, Long> {
        val schema = readInt()
        require(schema in 1..SCHEMA) { "Unsupported session schema" }
        require(readUTF() == fingerprint) { "Session rules fingerprint mismatch" }
        return schema to readLong().also { require(it >= 0) }
    }
    fun checkpoint(fingerprint: String, value: Checkpoint) = output {
        header(fingerprint, value.cutSeq, value.schema); world(value.genesis, value.schema); world(value.state, value.schema)
    }
    fun readCheckpoint(fingerprint: String, bytes: ByteArray) = input(bytes) {
        val (schema, seq) = header(fingerprint)
        Checkpoint(seq, world(schema), world(schema), schema)
    }
    fun entry(fingerprint: String, value: Entry) = output {
        header(fingerprint, value.seq, value.schema); action(value.action, value.schema); world(value.transition.state, value.schema)
        optional(value.transition.admission) { writeUTF(it.name) }; writeBoolean(value.transition.rejected)
        count(value.transition.events.size); value.transition.events.forEach { event(it, value.schema) }
    }
    fun readEntry(fingerprint: String, bytes: ByteArray) = input(bytes) {
        val (schema, seq) = header(fingerprint); val action = action(schema); val state = world(schema)
        val admission = optional { Admission.valueOf(readUTF()) }; val rejected = readBoolean()
        Entry(seq, action, SessionTransition(state, admission, List(count()) { event(schema) }, rejected), schema)
    }
    fun worldBytes(value: DurableWorldState) = output { world(value, SCHEMA) }
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
    private fun DataOutputStream.countries(value: Set<CountryId>) {
        count(value.size); value.sortedBy { it.value }.forEach { writeInt(it.value) }
    }
    private fun DataInputStream.countries(): Set<CountryId> {
        val values = List(count()) { CountryId(readInt()) }
        require(values.distinct().size == values.size)
        return values.toSet()
    }
    private fun DataOutputStream.defeat(value: CountryDefeat) {
        writeUTF(value.reason.name); writeLong(value.gameSeconds); optional(value.captor) { writeInt(it.value) }
    }
    private fun DataInputStream.defeat() = CountryDefeat(EndReason.valueOf(readUTF()), readLong(), optional { CountryId(readInt()) })
    private fun DataOutputStream.end(value: SessionEnd, schema: Int) {
        writeUTF(value.reason.name); powers(value.affected); writeLong(value.gameSeconds)
        if (schema >= 3) { countries(value.affectedCountries); optional(value.remainingCountries) { countries(it) } }
        else require(value.affectedCountries == value.affected.map { it.country }.toSet() && value.remainingCountries == null)
    }
    private fun DataInputStream.end(schema: Int): SessionEnd {
        val reason = EndReason.valueOf(readUTF()); val affected = powers(); val seconds = readLong()
        return if (schema >= 3) SessionEnd(reason, affected, seconds, countries(), optional { countries() }) else SessionEnd(reason, affected, seconds)
    }
    private fun DataOutputStream.country(value: CountryId?, schema: Int) {
        if (schema == 1) writeUTF(value.legacyPower().name)
        else optional(value) { writeInt(it.value) }
    }
    private fun DataInputStream.country(schema: Int): CountryId? =
        if (schema == 1) Power.valueOf(readUTF()).country else optional { CountryId(readInt()) }
    private fun DataOutputStream.world(value: DurableWorldState, schema: Int) {
        writeLong(value.elapsed); optional(value.restartAt) { writeLong(it) }; writeLong(value.revision)
        writeLong(value.acceptedCommands); writeLong(value.rejectedCommands)
        val s = value.session
        writeLong(s.generation); writeLong(s.gameMillis); writeLong(s.strategyTicks); writeLong(s.recoveries)
        count(s.participants.size); s.participants.forEach { (id, p) ->
            writeUTF(id); country(p.country, schema); writeBoolean(p.original); writeBoolean(p.online); optional(p.connection) { writeUTF(it) }
        }
        count(s.excluded.size); s.excluded.forEach { (id, country) -> writeUTF(id); country(country, schema) }
        count(s.battles.size); s.battles.forEach { (id, battle) -> writeUTF(id); writeLong(battle.startMillis); writeLong(battle.ticks) }
        optional(s.ended) { end(it, schema) }
        if (schema >= 3) {
            countries(s.countries); count(s.eliminated.size)
            s.eliminated.entries.sortedBy { it.key.value }.forEach { (country, defeat) -> writeInt(country.value); defeat(defeat) }
        } else require(s.countries.isEmpty() && s.eliminated.isEmpty())
    }
    private fun DataInputStream.world(schema: Int): DurableWorldState {
        val elapsed = readLong(); val restart = optional { readLong() }; val revision = readLong()
        val accepted = readLong(); val rejected = readLong()
        val generation = readLong(); val millis = readLong(); val strategy = readLong(); val recoveries = readLong()
        val participants = linkedMapOf<String, Participant>()
        repeat(count()) {
            val id = readUTF(); val p = Participant(country(schema), readBoolean(), readBoolean(), optional { readUTF() })
            require(participants.put(id, p) == null) { "Duplicate participant" }
        }
        val excluded = linkedMapOf<String, CountryId?>()
        repeat(count()) {
            val id = readUTF(); require(id !in excluded) { "Duplicate exclusion" }
            excluded[id] = country(schema)
        }
        val battles = linkedMapOf<String, BattleState>()
        repeat(count()) { require(battles.put(readUTF(), BattleState(readLong(), readLong())) == null) { "Duplicate battle" } }
        val end = optional { end(schema) }
        val countries = if (schema >= 3) countries() else emptySet()
        val eliminated = linkedMapOf<CountryId, CountryDefeat>()
        if (schema >= 3) repeat(count()) { require(eliminated.put(CountryId(readInt()), defeat()) == null) }
        return DurableWorldState(SessionState(generation, millis, strategy, recoveries, participants, excluded, battles,
            end, countries, eliminated), elapsed, restart, revision, accepted, rejected)
    }
    private fun DataOutputStream.action(value: SessionAction, schema: Int) { when (value) {
        SessionAction.Probe -> writeByte(0)
        is SessionAction.Advance -> { writeByte(1); writeLong(value.elapsed) }
        is SessionAction.Join -> { writeByte(if (value.replacingDeceased) 12 else 2)
            writeUTF(value.account); country(value.country, schema); writeBoolean(value.original)
            optional(value.connection) { writeUTF(it) }; optional(value.generation) { writeLong(it) } }
        is SessionAction.Disconnect -> { writeByte(3); writeUTF(value.account); optional(value.connection) { writeUTF(it) } }
        is SessionAction.Exclude -> { writeByte(4); writeUTF(value.account) }
        is SessionAction.OpenBattle -> { writeByte(5); writeUTF(value.id) }
        is SessionAction.CloseBattle -> { writeByte(6); writeUTF(value.id) }
        is SessionAction.Territory -> { writeByte(7); count(value.systems.size)
            value.systems.entries.sortedBy { it.key.ordinal }.forEach { writeUTF(it.key.name); writeInt(it.value) }; powers(value.capitals) }
        SessionAction.Restart -> writeByte(8)
        SessionAction.RecoverConnections -> writeByte(9)
        is SessionAction.Defect -> { writeByte(10); writeUTF(value.account); country(value.country, schema) }
        is SessionAction.CountryTerritory -> {
            require(schema >= 3); writeByte(11); count(value.systems.size)
            value.systems.entries.sortedBy { it.key.value }.forEach { (country, systems) -> writeInt(country.value); writeInt(systems) }
            count(value.capitals.size)
            value.capitals.entries.sortedBy { it.key.value }.forEach { (country, captor) -> writeInt(country.value); optional(captor) { writeInt(it.value) } }
            countries(value.reductionEligible)
        }
    } }
    private fun DataInputStream.action(schema: Int): SessionAction = when (val opcode = readUnsignedByte()) {
        0 -> SessionAction.Probe
        1 -> SessionAction.Advance(readLong())
        2, 12 -> SessionAction.Join(readUTF(), country(schema), readBoolean(), optional { readUTF() }, optional { readLong() },
            replacingDeceased = opcode == 12)
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
        10 -> SessionAction.Defect(readUTF(), country(schema))
        11 -> {
            require(schema >= 3)
            val systems = linkedMapOf<CountryId, Int>()
            repeat(count()) { require(systems.put(CountryId(readInt()), readInt()) == null) }
            val capitals = linkedMapOf<CountryId, CountryId?>()
            repeat(count()) { val country = CountryId(readInt()); require(country !in capitals); capitals[country] = optional { CountryId(readInt()) } }
            SessionAction.CountryTerritory(systems, capitals, countries())
        }
        else -> error("Unknown journal action")
    }
    private fun DataOutputStream.event(value: WorldEvent, schema: Int) { when (value) {
        is WorldEvent.StrategyTicks -> { writeByte(0); writeLong(value.first); writeLong(value.count) }
        is WorldEvent.TacticalTicks -> { writeByte(1); writeUTF(value.instance); writeLong(value.first); writeLong(value.count) }
        is WorldEvent.CpRecovery -> { writeByte(2); writeLong(value.firstGameSeconds); writeLong(value.intervalSeconds); writeLong(value.count) }
        is WorldEvent.Ended -> { writeByte(3); end(value.end, schema) }
        is WorldEvent.Restarted -> { writeByte(4); writeLong(value.generation) }
        is WorldEvent.CountryEliminated -> { require(schema >= 3); writeByte(5); writeInt(value.country.value); defeat(value.defeat) }
    } }
    private fun DataInputStream.event(schema: Int): WorldEvent = when (readUnsignedByte()) {
        0 -> WorldEvent.StrategyTicks(readLong(), readLong())
        1 -> WorldEvent.TacticalTicks(readUTF(), readLong(), readLong())
        2 -> WorldEvent.CpRecovery(readLong(), readLong(), readLong())
        3 -> WorldEvent.Ended(end(schema))
        4 -> WorldEvent.Restarted(readLong())
        5 -> { require(schema >= 3); WorldEvent.CountryEliminated(CountryId(readInt()), defeat()) }
        else -> error("Unknown journal event")
    }
}
