package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.engine.strategy.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class DockingWireTest {
    @TempDir lateinit var directory: Path
    private fun code(packet: ByteArray) = ByteBuffer.wrap(packet).getShort(4).toInt() and 65535
    private fun through(client: SyntheticClient, end: Int): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        repeat(300) { packets += client.receive(); if (code(packets.last()) == end) return packets }
        error("Missing opcode $end")
    }
    @Test fun reconnectReportsRecoveryDeadlineForAnOverdueDock() = runBlocking<Unit> {
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Home", 2)),
            listOf(GalaxyBase(1049, 2487, 60, 0, "Odin", 0, 20f, 86400, 0, 0f, 2f)),
            Power.entries.associateWith { 2487 })
        val account = "load-000"
        val now = SessionRules.load().startSeconds
        val due = (now + 8 * 3600) * 1000
        val job = StrategyJob(0, "operation/星系内航行", now * 1000, due, due,
            PaymentResult.Paid(CpBalance(1000, 840), CpPool.MCP, 160, 160, false), JobState.WAITING)
        val wounded = Navigator(1, 10000, Power.EMPIRE, 2487, 1000, 840, 1000,
            strategy = StrategyCheckpoint(1, now * 1000, listOf(job)), dockTarget = 1049,
            hull = FlagshipHull(3, now, woundedUntil = now + 9 * 3600))
        val store = object : NavigationStore {
            private var players = mapOf(account to wounded)
            override fun load() = players
            override fun save(players: Map<String, Navigator>) { this.players = players }
        }
        LoadFixture(directory, 1, LoadMetrics(), galaxy, ticking = false, navigationStore = store).use { fixture ->
            fixture.engine.submit(WorldCommand.AdvanceTo(8 * 3600_000L / 24))
            val client = fixture.join(account, true, false).client
            client.send(SyntheticClient.opcode(0x0f00))
            val ack = ByteBuffer.wrap(through(client, 0x0b00).last())
            assertEquals(3600, ack.getInt(10))
            assertEquals(1049, ack.getInt(28))
            assertEquals(0, fixture.engine.snapshot().navigators.getValue(account).baseId)
            fixture.engine.submit(WorldCommand.AdvanceTo(9 * 3600_000L / 24))
            assertEquals(1049, ByteBuffer.wrap(through(client, 0x0b0b).last()).getInt(14))
        }
    }
    @Test fun reconnectRestoresRemainingDockWaitWithoutChargingOrCompletingIt() = runBlocking<Unit> {
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Home", 2)),
            listOf(GalaxyBase(1049, 2487, 60, 0, "Odin", 0, 20f, 86400, 0, 0f, 2f)),
            Power.entries.associateWith { 2487 })
        LoadFixture(directory, 1, LoadMetrics(), galaxy, ticking = false).use { fixture ->
            val account = fixture.account(0)
            val first = fixture.join(account, true, false).client
            first.send(SyntheticClient.opcode(0x0f00)); through(first, 0x0f01)
            first.send(PlaceMessages.docked(PlaceMessages.Dock(10000, 59, 1049, 4), 0, 0, 0).copyOfRange(4, 34))
            through(first, 0x0325)
            val queued = fixture.engine.snapshot().navigators.getValue(account)
            first.close(); fixture.awaitState(0, 0, 1)
            fixture.engine.submit(WorldCommand.AdvanceTo(2 * 3600_000L / 24))
            val second = fixture.join(account, false, false).client
            second.send(SyntheticClient.opcode(0x0f00))
            val restored = through(second, 0x0b00)
            assertEquals(0x0f01, code(restored[restored.lastIndex - 1]))
            val ack = ByteBuffer.wrap(restored.last())
            assertEquals(0, ack.int); assertEquals(21600, ack.getInt(10)); assertEquals(1049, ack.getInt(28))
            var player = fixture.engine.snapshot().navigators.getValue(account)
            assertEquals(queued.strategy!!.nextId, player.strategy!!.nextId)
            assertEquals(queued.dockReadyAt, player.dockReadyAt)
            assertEquals(915, player.mcp) // One ordinary 2-hour CP recovery; no second 160-point charge.
            assertEquals(0, player.baseId); assertEquals(5, player.mode)
            second.send(SyntheticClient.opcode(0x0f02)); through(second, 0x0b00)
            assertEquals(915, fixture.engine.snapshot().navigators.getValue(account).mcp)
            fixture.engine.submit(WorldCommand.AdvanceTo(8 * 3600_000L / 24))
            through(second, 0x0b0b)
            player = fixture.engine.snapshot().navigators.getValue(account)
            assertEquals(1049, player.baseId); assertNull(player.dockTarget)
            second.send(SyntheticClient.opcode(0x0f02))
            assertFalse(through(second, 0x0f03).any { code(it) == 0x0b00 })
        }
    }
    @Test fun encryptedDockAcknowledgesWaitBeforeArrivalAndDoesNotNotifyTwice() = runBlocking<Unit> {
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Home", 2)),
            listOf(GalaxyBase(1049, 2487, 60, 0, "Odin", 0, 20f, 86400, 0, 0f, 2f)),
            Power.entries.associateWith { 2487 })
        LoadFixture(directory, 1, LoadMetrics(), galaxy, ticking = false).use { fixture ->
            val client = fixture.join(fixture.account(0), true, false).client
            client.send(SyntheticClient.opcode(0x0f00)); through(client, 0x0f01)
            val request = PlaceMessages.docked(PlaceMessages.Dock(10000, 59, 1049, 4), 0, Int.MAX_VALUE, Int.MAX_VALUE)
                .let { it.copyOfRange(4, it.size) }
            client.send(request)
            val ack = through(client, 0x0325)
            assertEquals(listOf(0x0b00, 0x0356, 0x0325), ack.map(::code))
            assertEquals(28800, ByteBuffer.wrap(ack[0]).getInt(10))
            assertEquals(840, ByteBuffer.wrap(ack[0]).getInt(24))
            assertEquals(0, fixture.engine.snapshot().navigators.getValue(fixture.account(0)).baseId)
            client.send(request)
            val refused = through(client, 0x0325).first()
            assertEquals(1, ByteBuffer.wrap(refused).int)
            assertEquals(0, ByteBuffer.wrap(refused).getInt(28))
            val refusedTail = through(client, 0x0f1d)
            assertEquals(listOf(0x0f1d), refusedTail.map(::code))
            val notice = refusedTail.last()
            assertEquals(ChatMessages.Spot(10000, "[기항] 이동 완료까지 대기"), ChatMessages.spot(notice.copyOfRange(4, notice.size)))
            assertEquals(840, fixture.engine.snapshot().navigators.values.single().mcp)
            assertEquals(1049, fixture.engine.snapshot().navigators.values.single().dockTarget)
            fixture.engine.submit(WorldCommand.AdvanceTo(8 * 3600_000L / 24 - 1))
            client.send(SyntheticClient.opcode(0x0300))
            assertFalse(through(client, 0x0301).any { code(it) == 0x0b0b })
            assertEquals(0, fixture.engine.snapshot().navigators.getValue(fixture.account(0)).baseId)
            fixture.engine.submit(WorldCommand.AdvanceTo(8 * 3600_000L / 24))
            val arrived = through(client, 0x0b0b)
            assertTrue(arrived.any { code(it) == 0x0325 })
            assertEquals(1049, ByteBuffer.wrap(arrived.last()).getInt(14))
            val player = fixture.engine.snapshot().navigators.getValue(fixture.account(0))
            assertEquals(1049, player.baseId); assertNull(player.dockTarget)
            client.send(SyntheticClient.opcode(0x0300))
            assertFalse(through(client, 0x0301).any { code(it) == 0x0b0b })
        }
    }

    @Test fun anOfflineDockCompletesOnceForCurrentSameGridObservers() = runBlocking<Unit> {
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Home", 2), GalaxySystem(4, 2488, 4, "Away", 2)),
            listOf(GalaxyBase(1049, 2487, 60, 0, "Odin", 0, 20f, 86400, 0, 0f, 2f)),
            Power.entries.associateWith { 2487 })
        val initial = mapOf(
            "load-000" to Navigator(1, 10000, Power.EMPIRE, 2487, 1000, 1000, 1000),
            "load-001" to Navigator(1, 10001, Power.EMPIRE, 2487, 1000, 1000, 1000),
            "load-002" to Navigator(1, 10002, Power.EMPIRE, 2488, 1000, 1000, 1000))
        val store = object : NavigationStore {
            private var players = initial
            override fun load() = players
            override fun save(players: Map<String, Navigator>) { this.players = players }
        }
        LoadFixture(directory, 3, LoadMetrics(), galaxy, ticking = false, navigationStore = store).use { fixture ->
            fun join(index: Int) = fixture.join(fixture.account(index), true, false).client.also {
                it.send(SyntheticClient.opcode(0x0f00))
                through(it, 0x0f01)
            }
            val captain = join(0)
            val observer = join(1)
            val away = join(2)
            captain.send(SyntheticClient.opcode(0x0300))
            through(captain, 0x0301)
            captain.send(PlaceMessages.docked(PlaceMessages.Dock(10000, 59, 1049, 4), 0, 0, 0).copyOfRange(4, 34))
            val accepted = ByteBuffer.wrap(through(captain, 0x0b00).last())
            assertEquals(0, accepted.int)
            assertEquals(1049, accepted.getInt(28))
            through(captain, 0x0325)
            assertEquals(840, store.load().getValue(fixture.account(0)).mcp)
            captain.close()
            fixture.awaitState(2, 2, 3)

            fixture.engine.submit(WorldCommand.AdvanceTo(8 * 3600_000L / 24 - 1))
            observer.send(SyntheticClient.opcode(0x0300))
            assertFalse(through(observer, 0x0301).any { code(it) == 0x0b0b })

            fixture.engine.submit(WorldCommand.AdvanceTo(8 * 3600_000L / 24))
            val packets = through(observer, 0x0b0b)
            val notice = ByteBuffer.wrap(packets.last())
            assertEquals(10000, notice.getInt(10))
            assertEquals(1049, notice.getInt(14))
            assertEquals(4, notice.getShort(18).toInt())
            assertFalse(packets.any { code(it) == 0x0425 })
            val docked = store.load().getValue(fixture.account(0))
            assertEquals(1049, docked.baseId)
            assertNull(docked.dockTarget)
            assertEquals(docked, fixture.engine.snapshot().navigators.getValue(fixture.account(0)))

            fixture.engine.submit(WorldCommand.AdvanceTo(8 * 3600_000L / 24 + 1000))
            for (client in listOf(observer, away)) {
                client.send(SyntheticClient.opcode(0x0300))
                assertFalse(through(client, 0x0301).any { code(it) == 0x0b0b })
            }
        }
    }

    @Test fun insufficientCpExplainsTheRefusalThenAllowsRetryAfterRecovery() = runBlocking<Unit> {
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Home", 2)),
            listOf(GalaxyBase(1049, 2487, 60, 0, "Odin", 0, 20f, 86400, 0, 0f, 2f)),
            Power.entries.associateWith { 2487 })
        val store = object : NavigationStore {
            private var players = mapOf("load-000" to Navigator(1, 10000, Power.EMPIRE, 2487, 200, 100, 1000))
            override fun load() = players
            override fun save(players: Map<String, Navigator>) { this.players = players }
        }
        LoadFixture(directory, 1, LoadMetrics(), galaxy, ticking = false, navigationStore = store).use { fixture ->
            val client = fixture.join(fixture.account(0), true, false).client
            client.send(SyntheticClient.opcode(0x0f00)); through(client, 0x0f01)
            val request = PlaceMessages.docked(PlaceMessages.Dock(10000, 59, 1049, 4), 0, 0, 0)
                .let { it.copyOfRange(4, it.size) }
            client.send(request)
            val refused = through(client, 0x0f1d)
            assertEquals(listOf(0x0b00, 0x0356, 0x0325, 0x0b0b, 0x0f1d), refused.map(::code))
            assertEquals(1, ByteBuffer.wrap(refused.first()).int)
            val correction = ByteBuffer.wrap(refused[3])
            assertEquals(10000, correction.getInt(10)); assertEquals(0, correction.getInt(14))
            assertEquals(5, correction.getShort(18).toInt()); assertEquals(0, correction.getInt(20))
            val notice = refused.last()
            assertEquals(ChatMessages.Spot(10000, "[기항] MCP/PCP 부족"), ChatMessages.spot(notice.copyOfRange(4, notice.size)))
            val before = store.load().values.single()
            assertEquals(100, before.mcp); assertEquals(200, before.pcp)
            assertEquals(0, before.baseId); assertNull(before.dockTarget)

            fixture.engine.submit(WorldCommand.AdvanceTo(2 * 3600_000L / 24))
            client.send(request)
            val ack = ByteBuffer.wrap(through(client, 0x0b00).last())
            assertEquals(0, ack.int); assertEquals(28_800, ack.getInt(10))
            val reserved = store.load().values.single()
            assertEquals(15, reserved.mcp); assertEquals(275, reserved.pcp)
            assertEquals(0, reserved.baseId); assertEquals(1049, reserved.dockTarget)
        }
    }
}
