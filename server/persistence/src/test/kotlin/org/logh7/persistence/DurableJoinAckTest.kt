package org.logh7.persistence

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.*
import org.logh7.protocol.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Path
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class DurableJoinAckTest {
    @TempDir lateinit var directory: Path
    @Test fun actualEncryptedJoinAckIsCreatedOnlyAfterForcedCommit() = exchange(false)
    @Test fun failedForceCannotProduceSuccessfulJoinAck() = exchange(true)
    private fun exchange(fail: Boolean) = runBlocking {
        val appended = CountDownLatch(1); val release = CountDownLatch(1); val forced = AtomicBoolean()
        val rules = SessionRules.load()
        val store = FileSessionStore(directory, rules, failPoint = {
            if (it == StorePoint.AFTER_APPEND) {
                appended.countDown(); check(release.await(10, TimeUnit.SECONDS))
                if (fail) throw IOException("Injected force failure")
            }
            if (it == StorePoint.AFTER_FORCE) forced.set(true)
        })
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val accessScope = CoroutineScope(coroutineContext + SupervisorJob())
        val engine = WorldEngine(engineScope, rules, ticking = false, durability = store)
        val before = engine.snapshot()
        var now = Instant.EPOCH
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant() = now
        }
        val characters = CharacterCreation(clock = clock, lotteryWindowMillis = 1)
        characters.apply("user", listOf(1001)); now = now.plusMillis(1)
        val tickets = SessionTickets()
        val outgoingKey = ByteArray(16) { (240 + it).toByte() }
        val game = GameExchange(GameExchange.Role.SESSION, emptyMap(), tickets, "127.0.0.1", 47903,
            Handshake(outgoingKey, 2), characters, admission = EngineGameAdmission(engine, accessScope))
        fun hex(value: String) = java.util.HexFormat.of().parseHex(value)
        fun encrypted(body: ByteArray, sequence: Long) = LegacyBlowfish(ByteArray(16) { it.toByte() }).encrypt(Envelope.encode(sequence, body))
        try {
            game.accept(0x34, hex("35eb18155d09bbb52a8d5c7fcef57d7712d9f1f6a2bebede"))
            game.accept(0x36, hex("385c1e213879e5fc44d62157e541b061e556d88f3a5ae35b"))
            val token = tickets.issue("user", SessionTickets.Purpose.GAME)
            game.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array(), 1))
            game.accept(0x30, encrypted(hex("0200"), 2))
            assertNull(game.accept(0x30, encrypted(hex("0205"), 3)))
            val pending = checkNotNull(game.takePendingJoin())
            yield()
            assertTrue(appended.await(10, TimeUnit.SECONDS)); assertFalse(pending.isDone)
            assertEquals(before, engine.snapshot()); assertFalse(forced.get())
            release.countDown()
            if (fail) {
                assertFails { withContext(Dispatchers.IO) { pending.get(10, TimeUnit.SECONDS) } }
                assertFalse(forced.get()); assertEquals(before, engine.snapshot())
                // The Gateway closes on an exceptional admission future; finishJoin is never called.
                game.close(); assertEquals(GameExchange.State.CLOSED, game.state)
            } else {
                val admission = withContext(Dispatchers.IO) { pending.get(10, TimeUnit.SECONDS) }
                assertTrue(forced.get()); assertEquals(Admission.ACCEPTED, admission)
                val frame = checkNotNull(game.finishJoin(admission))
                val body = Envelope.decode(LegacyBlowfish(outgoingKey).decrypt(frame.copyOfRange(8, frame.size)), 2).second
                assertContentEquals(hex("00000000020600"), body)
                assertEquals(1, engine.snapshot().session!!.online)
                assertEquals(Participant(Power.EMPIRE, true, true, store.load().session.participants["user"]!!.connection),
                    store.load().session.participants["user"])
                game.close()
            }
            yield(); engine.drainAndClose()
        } finally { release.countDown(); game.close(); accessScope.cancel(); engine.close(); engineScope.cancel() }
    }
}
