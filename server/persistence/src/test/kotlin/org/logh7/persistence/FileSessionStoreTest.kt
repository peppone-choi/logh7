package org.logh7.persistence

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.EngineGameAdmission
import org.logh7.protocol.CharacterMessages
import java.io.IOException
import java.nio.file.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class FileSessionStoreTest {
    @TempDir lateinit var directory: Path
    private val rules = SessionRules.load()
    private val fingerprint get() = SessionCodec.fingerprint(rules)
    private val journal get() = directory.resolve("session.journal")
    private fun apply(store: FileSessionStore, action: SessionAction): SessionTransition =
        transitionSession(rules, store.load(), action).also { store.commit(action, it) }
    private fun seed(count: Int = 3) {
        FileSessionStore(directory, rules).use { store -> repeat(count) { apply(store, SessionAction.Advance((it + 1L) * 333)) } }
    }
    private fun writeJournal(frames: List<ByteArray>) {
        Files.write(journal, frames.fold(ByteArray(0)) { bytes, frame -> bytes + FileSessionStore.frame(frame) })
    }
    private fun assertBlockedUnchanged(path: Path = journal) {
        val bytes = Files.readAllBytes(path)
        assertFails { FileSessionStore(directory, rules).close() }
        assertContentEquals(bytes, Files.readAllBytes(path))
    }
    @Test fun checkpointAndWholeJournalReplayMatchFullStateAndSubsequentEvents() {
        val expected: DurableWorldState
        val after: SessionTransition
        FileSessionStore(directory, rules, 7).use { store ->
            apply(store, SessionAction.Join("original", Power.EMPIRE, true, "c-1", 7))
            apply(store, SessionAction.Join("excluded", Power.ALLIANCE, false, "c-2", 7))
            apply(store, SessionAction.Exclude("excluded"))
            apply(store, SessionAction.Advance(101)); apply(store, SessionAction.OpenBattle("z"))
            store.checkpoint()
            apply(store, SessionAction.Advance(118)); apply(store, SessionAction.OpenBattle("a"))
            apply(store, SessionAction.Advance(300_451))
            assertEquals(Admission.FACTION_CHANGED, apply(store, SessionAction.Join("excluded", Power.EMPIRE, false, "c-3", 7)).admission)
            expected = store.load()
            after = transitionSession(rules, expected, SessionAction.Advance(3_600_452))
        }
        FileSessionStore(directory, rules).use { store ->
            assertEquals(expected, store.load())
            assertContentEquals(SessionCodec.worldBytes(expected), SessionCodec.worldBytes(store.load()))
            assertEquals(after, apply(store, SessionAction.Advance(3_600_452)))
        }
        val checkpoint = directory.resolve("session.checkpoint")
        val saved = Files.readAllBytes(checkpoint)
        Files.write(checkpoint, FileSessionStore.frame(SessionCodec.checkpoint(fingerprint,
            SessionCodec.Checkpoint(0, DurableWorldState(SessionSimulation(rules, 7).exportState()),
                DurableWorldState(SessionSimulation(rules, 7).exportState())))))
        FileSessionStore(directory, rules).use { assertEquals(after.state, it.load()) }
        Files.write(checkpoint, saved)
    }
    @Test fun endedSessionAndScheduledRestartRestoreGenerationWithoutDoubleAdvance() {
        var ended: DurableWorldState
        FileSessionStore(directory, rules, 9).use { store ->
            apply(store, SessionAction.Advance(333))
            apply(store, SessionAction.Territory(mapOf(Power.EMPIRE to 3, Power.ALLIANCE to 20), emptySet()))
            ended = store.load(); store.checkpoint()
        }
        FileSessionStore(directory, rules).use { store ->
            assertEquals(ended, store.load())
            assertNotNull(ended.session.ended)
            assertEquals(Admission.ENDED, apply(store, SessionAction.Join("late", Power.EMPIRE, false, "late", 9)).admission)
            val reset = apply(store, SessionAction.Advance(checkNotNull(ended.restartAt)))
            assertEquals(listOf(WorldEvent.Restarted(10)), reset.events)
            assertEquals(10L, reset.state.session.generation)
            assertEquals(rules.startSeconds * 1000, reset.state.session.gameMillis)
            assertEquals(Admission.STALE_SESSION, apply(store, SessionAction.Join("old", Power.EMPIRE, false, "old", 9)).admission)
            store.checkpoint()
        }
        FileSessionStore(directory, rules).use { assertEquals(10L, it.load().session.generation) }
    }
    @Test fun exactAdjacentDuplicateIsIdempotent() {
        seed(); val frames = FileSessionStore.readFrames(journal)
        writeJournal(listOf(frames[0], frames[0], frames[1], frames[1], frames[2]))
        FileSessionStore(directory, rules).use { assertEquals(3L, it.load().revision); assertEquals(999 * rules.speed, it.load().session.gameMillis) }
    }
    @Test fun conflictingDuplicateAndMissingOrReorderedSequenceAreRejected() {
        seed(); val frames = FileSessionStore.readFrames(journal)
        val original = SessionCodec.readEntry(fingerprint, frames[0])
        val different = SessionCodec.entry(fingerprint, original.copy(action = SessionAction.Probe))
        listOf(listOf(frames[0], different) + frames.drop(1), listOf(frames[0], frames[2]), listOf(frames[1], frames[0], frames[2])).forEach {
            writeJournal(it); assertBlockedUnchanged()
        }
    }
    @Test fun checksumSchemaRulesAndOutcomeMismatchAreRejected() {
        seed(); val frames = FileSessionStore.readFrames(journal)
        val pristine = Files.readAllBytes(journal)
        val checksumBad = pristine.clone(); checksumBad[12] = (checksumBad[12].toInt() xor 1).toByte()
        Files.write(journal, checksumBad); assertBlockedUnchanged()
        Files.write(journal, pristine)
        val schemaBad = frames[0].clone(); schemaBad[3] = 2
        writeJournal(listOf(schemaBad) + frames.drop(1)); assertBlockedUnchanged()
        Files.write(journal, pristine)
        assertFails { FileSessionStore(directory, rules.copy(speed = rules.speed + 1)).close() }
        assertContentEquals(pristine, Files.readAllBytes(journal))
        val entry = SessionCodec.readEntry(fingerprint, frames[0])
        val admissionBad = entry.copy(transition = entry.transition.copy(admission = Admission.FULL))
        val eventBad = entry.copy(transition = entry.transition.copy(events = listOf(WorldEvent.Restarted(99))))
        val stateBad = entry.copy(transition = entry.transition.copy(state = entry.transition.state.copy(revision = 50)))
        listOf(admissionBad, eventBad, stateBad).forEach {
            writeJournal(listOf(SessionCodec.entry(fingerprint, it)) + frames.drop(1)); assertBlockedUnchanged()
        }
    }
    @Test fun checkpointCutStateAndChecksumAreVerified() {
        seed()
        FileSessionStore(directory, rules).use { it.checkpoint() }
        val path = directory.resolve("session.checkpoint")
        val original = Files.readAllBytes(path)
        val cp = SessionCodec.readCheckpoint(fingerprint, FileSessionStore.readFrames(path).single())
        listOf(cp.copy(cutSeq = 4), cp.copy(state = cp.state.copy(elapsed = 0)), cp.copy(cutSeq = 0)).forEach {
            Files.write(path, FileSessionStore.frame(SessionCodec.checkpoint(fingerprint, it))); assertBlockedUnchanged(path)
        }
        val invalid = original.clone(); invalid[invalid.lastIndex] = (invalid.last().toInt() xor 1).toByte()
        Files.write(path, invalid); assertBlockedUnchanged(path)
    }
    @Test fun partialTailsNeverGetTruncated() {
        seed(); val original = Files.readAllBytes(journal)
        val last = FileSessionStore.frame(FileSessionStore.readFrames(journal).last())
        listOf(byteArrayOf(0), last.copyOf(7), last.copyOf(last.size - 1)).forEach { suffix ->
            Files.write(journal, original + suffix); assertBlockedUnchanged()
        }
    }
    @Test fun journalWithoutCheckpointAndConcurrentWriterAreRejected() {
        FileSessionStore(directory, rules).use { assertFails { FileSessionStore(directory, rules).close() } }
        FileSessionStore(directory, rules).use { assertEquals(0L, it.load().revision) }
        Files.delete(directory.resolve("session.checkpoint")); assertBlockedUnchanged()
    }
    @Test fun checkpointFilesystemFailureKeepsCommittedJournalRecoverable() {
        FileSessionStore(directory, rules).use { store ->
            apply(store, SessionAction.Advance(333))
            Files.delete(directory.resolve("session.checkpoint")); Files.createDirectory(directory.resolve("session.checkpoint"))
            assertFailsWith<IOException> { store.checkpoint() }
            assertEquals(333L, store.load().elapsed)
        }
        // Repair only our deliberate fixture obstruction; production boot never removes bad files.
        Files.delete(directory.resolve("session.checkpoint"))
        val initial = DurableWorldState(SessionSimulation(rules).exportState())
        Files.write(directory.resolve("session.checkpoint"), FileSessionStore.frame(SessionCodec.checkpoint(fingerprint, SessionCodec.Checkpoint(0, initial, initial))))
        FileSessionStore(directory, rules).use { assertEquals(333L, it.load().elapsed) }
    }
    @Test fun actualGatewayAdmissionWaitsForForceAndBootClearsConnectionsDurably() = runBlocking {
        val appended = CountDownLatch(1); val release = CountDownLatch(1)
        val store = FileSessionStore(directory, rules, failPoint = { point ->
            if (point == StorePoint.AFTER_APPEND) { appended.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = WorldEngine(scope, rules, ticking = false, durability = store)
        try {
            val future = EngineGameAdmission(engine, scope).join("account", character(), "c-1", 1)
            assertTrue(appended.await(10, TimeUnit.SECONDS))
            assertFalse(future.isDone); assertEquals(0, engine.snapshot().session!!.participants)
            release.countDown()
            assertEquals(Admission.ACCEPTED, withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) })
            assertEquals(1, engine.snapshot().session!!.online)
            engine.submit(WorldCommand.AdvanceTo(451)); engine.drainAndClose()
            val recovered = WorldEngine(scope, rules, ticking = false, durability = FileSessionStore(directory, rules))
            assertEquals(0, recovered.snapshot().session!!.online)
            assertEquals(1, recovered.snapshot().session!!.participants)
            val second = EngineGameAdmission(recovered, scope).join("account", character(), "c-2", 1)
            assertEquals(Admission.ACCEPTED, withContext(Dispatchers.IO) { second.get(10, TimeUnit.SECONDS) })
            recovered.submit(WorldCommand.AdvanceTo(452)); recovered.drainAndClose()
            FileSessionStore(directory, rules).use {
                assertEquals(452L, it.load().elapsed)
                assertEquals(452 * rules.speed, it.load().session.gameMillis)
            }
        } finally { release.countDown(); engine.close(); scope.cancel() }
    }
    @Test fun appendFailureProducesZeroSuccessfulAdmissionAndNoStateOrEventsAndLatches() = runBlocking {
        val events = mutableListOf<WorldEvent>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = FileSessionStore(directory, rules, failPoint = { if (it == StorePoint.AFTER_APPEND) throw IOException("Injected force boundary failure") })
        val engine = WorldEngine(scope, rules, ticking = false, onEvent = { events += it }, durability = store)
        val before = engine.snapshot()
        try {
            val access = EngineGameAdmission(engine, scope)
            val failed = access.join("account", character(), "c-1", 1)
            assertFails { withContext(Dispatchers.IO) { failed.get(10, TimeUnit.SECONDS) } }
            assertEquals(before, engine.snapshot()); assertTrue(events.isEmpty())
            val bytes = Files.readAllBytes(journal)
            val second = access.join("another", character(), "c-2", 1)
            assertFails { withContext(Dispatchers.IO) { second.get(10, TimeUnit.SECONDS) } }
            assertEquals(before, engine.snapshot()); assertContentEquals(bytes, Files.readAllBytes(journal))
            val restart = CompletableDeferred<Boolean>()
            engine.submit(WorldCommand.RequestRestart(restart)); assertFalse(restart.await())
            assertEquals(before, engine.snapshot()); assertContentEquals(bytes, Files.readAllBytes(journal))
            engine.drainAndClose()
            // An uncertain write may survive even though it was never acknowledged. Replay, never retry in place.
            FileSessionStore(directory, rules).use { assertEquals(1, it.load().session.participants.size) }
        } finally { engine.close(); scope.cancel() }
    }
    private fun character() = CharacterMessages.Generate(0, 1, 2, 0, 0, "Test", "Account", 30,
        1, 1, 0, List(8) { 0 }, 0, 0, 0, 0, 0, 0, "", 0)
}
