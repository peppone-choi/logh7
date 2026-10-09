package org.logh7.persistence

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Owns and forcibly terminates only the JVMs it starts, with bounded waits. */
class SessionCrashTest {
    @TempDir lateinit var directory: Path
    private val rules = SessionRules.load()
    @Test fun killBeforeAppendRestartsFromPriorCommittedState() = crash(StorePoint.BEFORE_APPEND)
    @Test fun killAfterAppendRestartsWithUnacknowledgedRecordVisibleInThisProcessCrash() = crash(StorePoint.AFTER_APPEND)
    @Test fun killAfterForceBeforePublishRestartsFromForcedState() = crash(StorePoint.AFTER_FORCE)
    @Test fun killAfterCheckpointForceBeforeRenameReplaysOldCut() = crash(StorePoint.CHECKPOINT_FORCED)
    @Test fun killAfterCheckpointRenameRestartsFromNewCut() = crash(StorePoint.CHECKPOINT_RENAMED)
    private fun crash(point: StorePoint) {
        val before = FileSessionStore(directory, rules, 7).use { store ->
            listOf(SessionAction.Join("original", Power.EMPIRE, true, "dead-connection", 7),
                SessionAction.Join("excluded", Power.ALLIANCE, false, "dead-2", 7),
                SessionAction.Exclude("excluded"), SessionAction.Advance(101), SessionAction.OpenBattle("z"),
                SessionAction.Advance(451)).forEach { action -> store.commit(action, transitionSession(rules, store.load(), action)) }
            store.checkpoint(); store.load()
        }
        val action = SessionAction.Advance(1001)
        val committed = if (point == StorePoint.BEFORE_APPEND) before else transitionSession(rules, before, action).state
        val writer = child("write", point.name)
        val reader = Executors.newSingleThreadExecutor()
        try {
            val marker = reader.submit<String?> { writer.inputStream.bufferedReader().readLine() }.get(15, TimeUnit.SECONDS)
            assertEquals("READY:${point.name}", marker)
            assertTrue(writer.isAlive)
            writer.destroyForcibly()
            assertTrue(writer.waitFor(10, TimeUnit.SECONDS))
            assertNotEquals(0, writer.exitValue())
        } finally {
            if (writer.isAlive) { writer.destroyForcibly(); writer.waitFor(10, TimeUnit.SECONDS) }
            reader.shutdownNow()
        }
        val recovered = transitionSession(rules, committed, SessionAction.RecoverConnections).state
        val joined = transitionSession(rules, recovered, SessionAction.Join("original", Power.EMPIRE, true, "new-connection", 7))
        assertEquals(Admission.ACCEPTED, joined.admission)
        val next = transitionSession(rules, joined.state, SessionAction.Advance(1002))
        val restart = child("recover", hash(committed), hash(next.state))
        try {
            assertTrue(restart.waitFor(15, TimeUnit.SECONDS))
            val output = restart.inputStream.bufferedReader().readText()
            assertEquals(0, restart.exitValue(), output)
            assertEquals("RECOVERED:${hash(committed)}:NEXT:${hash(next.state)}", output.trim())
        } finally { if (restart.isAlive) { restart.destroyForcibly(); restart.waitFor(10, TimeUnit.SECONDS) } }
    }
    private fun child(vararg args: String): Process = ProcessBuilder(listOf(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx96m", "-cp",
        checkNotNull(System.getProperty("logh7.test.runtimeClasspath")), SessionCrashProcess::class.java.name,
        directory.toString()) + args).redirectErrorStream(true).start()
}

private fun hash(state: DurableWorldState) = SessionCodec.digest(SessionCodec.worldBytes(state)).joinToString("") { "%02x".format(it) }

/** Synthetic JVM fixture, not the archived original client or installer. */
object SessionCrashProcess {
    @JvmStatic fun main(args: Array<String>) {
        val directory = Path.of(args[0]); val rules = SessionRules.load()
        if (args[1] == "write") {
            val point = StorePoint.valueOf(args[2]); var armed = false
            FileSessionStore(directory, rules, failPoint = {
                if (armed && it == point) {
                    println("READY:${point.name}"); System.out.flush()
                    check(System.`in`.read() == -1) { "Unexpected fixture input" }
                    error("Fixture must be killed at the failpoint")
                }
            }).use { store ->
                armed = true
                val action = SessionAction.Advance(1001)
                store.commit(action, transitionSession(rules, store.load(), action))
                store.checkpoint()
                error("Failpoint was not reached")
            }
        } else {
            check(args[1] == "recover")
            val raw = FileSessionStore(directory, rules).use { it.load() }
            check(hash(raw) == args[2]) { "Full state differs after child process kill" }
            runBlocking {
                val engine = WorldEngine(this, rules, ticking = false, durability = FileSessionStore(directory, rules))
                check(engine.snapshot().session!!.online == 0)
                val result = CompletableDeferred<Admission>()
                engine.submit(WorldCommand.Join("original", Power.EMPIRE, true, result, "new-connection", 7))
                check(result.await() == Admission.ACCEPTED)
                engine.submit(WorldCommand.AdvanceTo(1002)); engine.drainAndClose()
            }
            val next = FileSessionStore(directory, rules).use { it.load() }
            check(hash(next) == args[3]) { "Full subsequent state differs after restart" }
            println("RECOVERED:${hash(raw)}:NEXT:${hash(next)}")
        }
    }
}
