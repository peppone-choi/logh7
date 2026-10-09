package org.logh7.persistence

import org.logh7.engine.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*

enum class StorePoint { BEFORE_APPEND, AFTER_APPEND, AFTER_FORCE, CHECKPOINT_FORCED, CHECKPOINT_RENAMED }

/** Local single-writer journal. A bad/partial tail blocks boot; it is never silently truncated. */
class FileSessionStore(private val directory: Path, private val rules: SessionRules,
    initialGeneration: Long = 1, private val checkpointEvery: Long = 256,
    private val failPoint: (StorePoint) -> Unit = {}) : SessionDurability {
    private val fingerprint = SessionCodec.fingerprint(rules)
    private val journalPath = directory.resolve("session.journal")
    private val checkpointPath = directory.resolve("session.checkpoint")
    private val lockChannel: FileChannel
    private val lock: java.nio.channels.FileLock
    private val journal: FileChannel
    private var state: DurableWorldState
    private var genesis: DurableWorldState
    private var sequence = 0L
    private var failed = false
    private var closed = false

    init {
        require(checkpointEvery > 0)
        val parent = directory.toAbsolutePath().parent
        require(Files.isDirectory(parent)) { "Create the store parent directory before starting the server" }
        Files.createDirectories(directory); forceDirectory(parent)
        lockChannel = FileChannel.open(directory.resolve("session.lock"), CREATE, WRITE)
        lock = try { checkNotNull(lockChannel.tryLock()) { "Session store already has a writer" } }
            catch (failure: Exception) { lockChannel.close(); throw failure }
        var opened: FileChannel? = null
        try {
            if (!Files.exists(checkpointPath)) {
                require(!Files.exists(journalPath)) { "Journal exists without a checkpoint; preserve files for investigation" }
                genesis = DurableWorldState(SessionSimulation(rules, initialGeneration).exportState())
                state = genesis
                writeCheckpoint()
            } else {
                val frames = readFrames(checkpointPath)
                require(frames.size == 1) { "Checkpoint must contain exactly one frame" }
                val checkpoint = SessionCodec.readCheckpoint(fingerprint, frames.single())
                genesis = checkpoint.genesis
                require(genesis == DurableWorldState(SessionSimulation(rules, genesis.session.generation).exportState())) { "Invalid genesis state" }
                require(SessionCodec.checkpoint(fingerprint, checkpoint).contentEquals(frames.single())) { "Noncanonical checkpoint" }
                state = genesis
                var cutVerified = checkpoint.cutSeq == 0L && sameState(state, checkpoint.state)
                var previous: ByteArray? = null
                if (Files.exists(journalPath)) visitFrames(journalPath) { bytes ->
                    val entry = SessionCodec.readEntry(fingerprint, bytes)
                    require(SessionCodec.entry(fingerprint, entry).contentEquals(bytes)) { "Noncanonical journal record" }
                    if (entry.seq == sequence && previous?.contentEquals(bytes) == true) return@visitFrames
                    require(entry.seq == Math.addExact(sequence, 1)) { "Missing, reordered or conflicting duplicate journal sequence" }
                    val computed = transitionSession(rules, state, entry.action)
                    require(SessionCodec.entry(fingerprint, entry.copy(transition = computed)).contentEquals(bytes)) { "Journal state/admission/event replay mismatch" }
                    state = computed.state; sequence = entry.seq; previous = bytes
                    if (sequence == checkpoint.cutSeq) {
                        require(sameState(state, checkpoint.state)) { "Checkpoint does not match journal cut" }; cutVerified = true
                    }
                }
                require(cutVerified && checkpoint.cutSeq <= sequence) { "Checkpoint cut is missing from journal" }
            }
            opened = FileChannel.open(journalPath, CREATE, WRITE, APPEND)
            opened.force(true); forceDirectory(directory)
            journal = opened
        } catch (failure: Exception) { opened?.close(); lock.release(); lockChannel.close(); throw failure }
    }

    @Synchronized override fun load(): DurableWorldState {
        check(!closed && !failed)
        // Return a detached state, so a caller cannot mutate the store's committed maps.
        return SessionSimulation.restore(rules, state.session, false).exportState().let { state.copy(session = it) }
    }
    @Synchronized override fun commit(action: SessionAction, transition: SessionTransition) {
        check(!closed && !failed) { "Store is closed or failed; reopen and verify before continuing" }
        val expected = transitionSession(rules, state, action)
        require(SessionCodec.entry(fingerprint, SessionCodec.Entry(1, action, expected)).contentEquals(
            SessionCodec.entry(fingerprint, SessionCodec.Entry(1, action, transition)))) { "Candidate differs from deterministic transition" }
        val next = Math.addExact(sequence, 1)
        val bytes = frame(SessionCodec.entry(fingerprint, SessionCodec.Entry(next, action, expected)))
        try {
            failPoint(StorePoint.BEFORE_APPEND)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) journal.write(buffer)
            failPoint(StorePoint.AFTER_APPEND)
            journal.force(true)
            failPoint(StorePoint.AFTER_FORCE)
            state = expected.state; sequence = next
            if (sequence % checkpointEvery == 0L) writeCheckpoint()
        } catch (failure: Exception) { failed = true; throw failure }
    }
    @Synchronized fun checkpoint() { check(!closed && !failed); writeCheckpoint() }
    private fun writeCheckpoint() {
        val bytes = frame(SessionCodec.checkpoint(fingerprint, SessionCodec.Checkpoint(sequence, genesis, state)))
        val temporary = Files.createTempFile(directory, "checkpoint-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true)
            }
            failPoint(StorePoint.CHECKPOINT_FORCED)
            Files.move(temporary, checkpointPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            forceDirectory(directory)
            failPoint(StorePoint.CHECKPOINT_RENAMED)
        } finally { Files.deleteIfExists(temporary) }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { journal.close() } finally { try { lock.release() } finally { lockChannel.close() } }
    }
    private fun sameState(a: DurableWorldState, b: DurableWorldState) = SessionCodec.worldBytes(a).contentEquals(SessionCodec.worldBytes(b))
    private fun forceDirectory(path: Path) { FileChannel.open(path, READ).use { it.force(true) } }
    internal companion object {
        fun frame(payload: ByteArray): ByteArray = ByteArrayOutputStream().also { raw ->
            require(payload.size in 1..SessionCodec.MAX_FRAME)
            DataOutputStream(raw).use { it.writeInt(payload.size); it.write(payload); it.write(SessionCodec.digest(payload)) }
        }.toByteArray()
        fun readFrames(path: Path): List<ByteArray> = mutableListOf<ByteArray>().also { result -> visitFrames(path) { result += it } }
        private fun visitFrames(path: Path, visit: (ByteArray) -> Unit) = Files.newInputStream(path).use { raw ->
            val input = DataInputStream(BufferedInputStream(raw))
            while (true) {
                val first = input.read(); if (first == -1) break
                val rest = input.readNBytes(3); require(rest.size == 3) { "Partial frame length" }
                val size = ByteBuffer.wrap(byteArrayOf(first.toByte()) + rest).int
                require(size in 1..SessionCodec.MAX_FRAME) { "Invalid frame length" }
                val payload = input.readNBytes(size); require(payload.size == size) { "Partial journal/checkpoint payload" }
                val checksum = input.readNBytes(32)
                require(checksum.size == 32 && checksum.contentEquals(SessionCodec.digest(payload))) { "Journal/checkpoint checksum mismatch" }
                visit(payload)
            }
        }
    }
}
