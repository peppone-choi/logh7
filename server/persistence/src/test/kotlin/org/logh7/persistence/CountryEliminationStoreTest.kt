package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.coroutines.*
import org.logh7.engine.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CountryEliminationStoreTest {
    @TempDir lateinit var directory: Path
    private val rules = SessionRules.load()
    private val a = CountryId(2)
    private val b = CountryId(42)
    private val c = CountryId(43)
    private val systems = mapOf(a to 8, b to 1, c to 1)
    private fun apply(store: FileSessionStore, action: SessionAction) =
        transitionSession(rules, store.load(), action).also { store.commit(action, it) }

    @Test fun checkpointAndJournalRestoreAnIndividualDefeatAndLaterFinalRestart() {
        val path = directory.resolve("world")
        lateinit var defeated: DurableWorldState
        localSessionStore(path, rules, checkpointEvery = 2).use { store ->
            apply(store, SessionAction.Join("loser", b, false, "b", 1))
            val result = apply(store, SessionAction.CountryTerritory(systems, mapOf(b to c)))
            assertEquals(1, result.events.size)
            apply(store, SessionAction.Advance(1000))
            defeated = store.load()
            assertNull(defeated.restartAt); assertNull(defeated.session.ended)
        }
        localSessionStore(path, rules).use { store ->
            assertEquals(defeated, store.load())
            assertEquals(Admission.COUNTRY_ELIMINATED, apply(store, SessionAction.Join("new", b, false, "b", 1)).admission)
            assertEquals(Admission.ACCEPTED, apply(store, SessionAction.Join("winner", a, false, "a", 1)).admission)
            apply(store, SessionAction.CountryTerritory(systems, mapOf(c to a)))
            assertEquals(setOf(a), store.load().session.ended!!.remainingCountries)
            store.checkpoint()
        }
        localSessionStore(path, rules).use { store ->
            assertEquals(setOf(b, c), store.load().session.ended!!.affectedCountries)
            apply(store, SessionAction.Advance(store.load().restartAt!!))
            assertEquals(2, store.load().session.generation)
            assertTrue(store.load().session.eliminated.isEmpty())
        }
        localSessionStore(path, rules).use { store -> assertEquals(2, store.load().session.generation) }
    }

    @Test fun forcedDefeatSurvivesAnUnacknowledgedCommitAndIsNotRepeated() {
        val path = directory.resolve("crash")
        var fail = false
        localSessionStore(path, rules, failPoint = { if (fail && it == StorePoint.AFTER_FORCE) throw IOException("lost ack") }).use { store ->
            apply(store, SessionAction.CountryTerritory(systems, emptyMap()))
            fail = true
            assertFailsWith<IOException> { apply(store, SessionAction.CountryTerritory(systems, mapOf(b to c))) }
        }
        localSessionStore(path, rules).use { store ->
            assertEquals(setOf(b), store.load().session.eliminated.keys)
            assertNull(store.load().restartAt)
            assertTrue(apply(store, SessionAction.CountryTerritory(systems, mapOf(b to a))).events.isEmpty())
            assertEquals(c, store.load().session.eliminated.getValue(b).captor)
        }
    }

    @Test fun schemaTwoRecordsReplayBeforeSchemaThreeDefeatsAndCannotSilentlyLoseThem() {
        val path = directory.resolve("mixed"); Files.createDirectories(path)
        val fingerprint = SessionCodec.fingerprint(rules)
        val genesis = DurableWorldState(SessionSimulation(rules).exportState())
        val action = SessionAction.Join("old", Power.EMPIRE, false, "old", 1)
        val old = transitionSession(rules, genesis, action)
        Files.write(path.resolve("session.checkpoint"), FileSessionStore.frame(SessionCodec.checkpoint(fingerprint,
            SessionCodec.Checkpoint(0, genesis, genesis, schema = 2))))
        Files.write(path.resolve("session.journal"), FileSessionStore.frame(SessionCodec.entry(fingerprint,
            SessionCodec.Entry(1, action, old, schema = 2))))
        localSessionStore(path, rules).use { store ->
            assertEquals(old.state, store.load())
            val next = SessionAction.CountryTerritory(systems, mapOf(b to c))
            val transition = apply(store, next)
            assertFailsWith<IllegalArgumentException> { SessionCodec.entry(fingerprint, SessionCodec.Entry(2, next, transition, schema = 2)) }
            store.checkpoint()
        }
        localSessionStore(path, rules).use { store ->
            assertEquals(setOf(b), store.load().session.eliminated.keys)
            assertEquals(CountryId(2), store.load().session.participants.getValue("old").country)
        }
    }

    @Test fun worldActorRecoversIndividualDefeatAndKeepsOtherParticipantsAndTimeRunning() = runBlocking {
        val path = directory.resolve("actor")
        localSessionStore(path, rules).use { store ->
            apply(store, SessionAction.Join("loser", b, false, "old-b", 1))
            apply(store, SessionAction.Join("live", c, false, "old-c", 1))
            apply(store, SessionAction.CountryTerritory(systems, mapOf(b to c)))
        }
        val store = localSessionStore(path, rules)
        val engine = WorldEngine(this, rules, ticking = false, durability = store)
        try {
            val lost = CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("loser", b, false, lost, "new-b", 1))
            assertEquals(Admission.COUNTRY_ELIMINATED, lost.await())
            val live = CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("live", c, false, live, "new-c", 1))
            assertEquals(Admission.ACCEPTED, live.await())
            engine.submit(WorldCommand.AdvanceTo(1000)); engine.synchronizeTime()
            assertEquals(24, engine.snapshot().session!!.gameSeconds)
            assertEquals(1, engine.snapshot().session!!.generation)
            assertNull(engine.snapshot().session!!.ended)
            assertNull(store.load().restartAt)
        } finally { engine.drainAndClose() }
    }

    @Test fun legacyEndEventsKeepTheirReplayMeaningAndCanRestartIntoTheNewSchema() {
        val fingerprint = SessionCodec.fingerprint(rules)
        for (schema in 1..2) {
            val path = directory.resolve("legacy-end-$schema"); Files.createDirectories(path)
            val genesis = DurableWorldState(SessionSimulation(rules).exportState())
            val action = SessionAction.Territory(mapOf(Power.EMPIRE to 8, Power.ALLIANCE to 8), setOf(Power.ALLIANCE))
            val end = transitionSession(rules, genesis, action)
            Files.write(path.resolve("session.checkpoint"), FileSessionStore.frame(SessionCodec.checkpoint(fingerprint,
                SessionCodec.Checkpoint(0, genesis, genesis, schema))))
            Files.write(path.resolve("session.journal"), FileSessionStore.frame(SessionCodec.entry(fingerprint,
                SessionCodec.Entry(1, action, end, schema))))
            localSessionStore(path, rules).use { store ->
                assertEquals(end.state, store.load())
                assertEquals(setOf(CountryId(3)), store.load().session.ended!!.affectedCountries)
                assertTrue(store.load().session.countries.isEmpty())
                apply(store, SessionAction.Advance(store.load().restartAt!!))
            }
            localSessionStore(path, rules).use { store ->
                assertEquals(2, store.load().session.generation)
                assertNull(store.load().session.ended)
            }
        }
    }
}
