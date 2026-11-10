package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.ChatMessages
import org.logh7.protocol.NavigationMessages
import org.logh7.protocol.PlaceMessages
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class FuelSupplyWireTest {
    @TempDir lateinit var directory: Path
    private fun code(message: ByteArray) = ByteBuffer.wrap(message).getShort(4).toInt() and 65535
    private fun request(characterId: Int) = ByteBuffer.allocate(24).putShort(0x0b02)
        .putInt(Int.MAX_VALUE).putInt(Int.MAX_VALUE).putInt(characterId).putShort(59)
        .putInt(Int.MAX_VALUE).putInt(Int.MAX_VALUE).array()
    private fun statusQuery(characterId: Int) = ByteBuffer.allocate(11).putShort(0x032e)
        .putInt(characterId).putInt(123).put(19).array()
    private fun fuelStatus(client: SyntheticClient, characterId: Int): List<String> {
        val reply = ByteBuffer.wrap(client.request(statusQuery(characterId), 0x032f, "fuel progress"))
        assertEquals(characterId, reply.getInt(6))
        assertEquals(123, reply.getInt(10))
        assertEquals(19, reply.get(14).toInt())
        reply.position(25)
        assertEquals(3, reply.get().toInt())
        return List(3) { index ->
            assertEquals(index + 1, reply.int)
            assertEquals(index, reply.get().toInt())
            assertEquals(0, reply.get().toInt())
            val length = reply.get().toInt()
            CharArray(length) { reply.char }.concatToString().removeSuffix("\u0000")
        }
    }
    private fun expectRefusal(client: SyntheticClient, text: String) {
        val refused = client.receive()
        assertEquals(0x0b02, code(refused)); assertEquals(1, ByteBuffer.wrap(refused).int)
        client.expect(0x0356)
        val notice = ByteBuffer.wrap(client.expect(0x0500))
        assertEquals(0x0b02,notice.getShort(6).toInt())
        notice.position(9)
        assertEquals("[연료 보급] $text\u0000",CharArray(notice.get().toInt()) { notice.char }.concatToString())
    }
    @Test fun detentionRefusesWarpAndFuelWithoutPaymentUntilRelease() = runBlocking<Unit> {
        val base = 1049
        val character = 10000
        val rules = SessionRules.load()
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire", 2)),
            listOf(GalaxyBase(base, 2487, 80, 0, "Odin", 0, 20f, 86400, 0, 0f, 2f)),
            Power.entries.associateWith { 2487 })
        val detention = Detention(character, CountryId(2), rules.startSeconds, rules.startSeconds + 60, base = base)
        var savedPlayers = mapOf("load-000" to Navigator(1, character, Power.EMPIRE, 2487, 1000, 1000, 900,
            baseId = base, mode = 4, spotId = base * 10_000 + 19, cpRecoveryAt = rules.startSeconds))
        var savedStocks = PlanetStocks(1, 0, mapOf(base to PlanetStock(base, 2, 0, 100, 200, 300)),
            deploymentInitialized = true, social = SocialState(justice = JusticeState(detentions = mapOf(character to detention))))
        val navigationStore = object : NavigationStore {
            override fun load() = savedPlayers
            override fun save(players: Map<String, Navigator>) { savedPlayers = players }
        }
        val stockStore = object : PlanetStockStore {
            override fun load() = savedStocks
            override fun save(stocks: PlanetStocks) { savedStocks = stocks }
        }
        val committer = WorldStateCommitter { players, stocks ->
            navigationStore.save(players)
            stockStore.save(checkNotNull(stocks))
        }
        LoadFixture(directory, 1, LoadMetrics(), galaxy, ticking = false, navigationStore = navigationStore,
            stockStore = stockStore, worldStateCommitter = committer).use { fixture ->
            val account = fixture.account(0)
            val client = fixture.join(account, true, false).client
            fun through(opcode: Int): ByteArray {
                repeat(300) { val message = client.receive(); if (code(message) == opcode) return message }
                error("Missing opcode ${opcode.toString(16)}")
            }
            client.send(SyntheticClient.opcode(0x0f00)); through(0x0f01)
            val before = fixture.engine.snapshot().navigators.getValue(account)
            assertEquals(base * 10_000 + 19, before.spotId)

            val warp = NavigationMessages.accepted(NavigationMessages.Warp(character, 59, 2488, 0, 5), 0, 0, 0).drop(4).toByteArray()
            client.send(warp)
            val refused = ByteBuffer.wrap(through(0x0b01))
            assertEquals(1, refused.int)
            assertEquals(before.grid, refused.getShort(28).toInt())
            assertEquals(base, refused.getInt(31))
            assertEquals(before.mode, refused.getShort(35).toInt())
            client.expect(0x0356)
            client.expect(0x0325)
            client.expect(0x0317)
            val warpEnd = ByteBuffer.wrap(client.expect(0x0b07))
            assertEquals(before.grid, warpEnd.getInt(14))
            assertEquals(before.unitId, warpEnd.getInt(25))
            assertEquals(before.cruise, warpEnd.getInt(29))
            val restored = ByteBuffer.wrap(client.expect(0x0b0b))
            assertEquals(character, restored.getInt(10))
            assertEquals(base, restored.getInt(14))
            assertEquals(before.mode, restored.getShort(18).toInt())
            assertEquals(before.spotId, restored.getInt(20))
            assertEquals(before.spotOwner, restored.getInt(24))
            val notice = client.expect(0x0f1d)
            assertEquals(ChatMessages.Spot(character, "[워프] 구금 중 이동 불가"), ChatMessages.spot(notice.copyOfRange(4, notice.size)))
            client.send(request(character))
            expectRefusal(client, "구금 중 보급 불가")
            assertEquals(before, fixture.engine.snapshot().navigators.getValue(account))

            fixture.engine.submit(WorldCommand.AdvanceTo(60_000L / fixture.engine.clockSpeed))
            fixture.await("detainee released") { fixture.engine.snapshot().planetStocks?.social?.justice?.detained(character) == false }
            assertEquals(0, fixture.engine.snapshot().navigators.getValue(account).spotId)
            val depart = PlaceMessages.switched(PlaceMessages.SwitchMode(character, 59, 5), 0, 0, 0, 5, 0, 0, true)
            client.send(depart.copyOfRange(4, depart.size)); through(0x0b0b)
            client.send(warp)
            assertEquals(0, ByteBuffer.wrap(through(0x0b01)).int)
            assertEquals(2488, ByteBuffer.wrap(through(0x0b07)).getInt(14))
            client.send(request(character))
            assertEquals(0, ByteBuffer.wrap(through(0x0b02)).int)
            assertNotNull(fixture.engine.snapshot().navigators.getValue(account).fuelReadyAt)
        }
    }
    @Test fun encryptedCaptainCommandChargesOnceAndNotifiesWhenTheServerDeadlinePasses() = runBlocking<Unit> {
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire"), GalaxySystem(4, 2015, 4, "Alliance")),
            emptyList(), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
        LoadFixture(directory, 1, LoadMetrics(), galaxy, ticking = false).use { fixture ->
            val client = fixture.join(fixture.account(0), create = true, initialize = false).client
            client.send(SyntheticClient.opcode(0x0f00))
            for (ignored in 0..300) { if (code(client.receive()) == 0x0f01) break }
            val own = fixture.engine.snapshot().navigators.getValue(fixture.account(0))
            assertEquals(listOf("FS1", "", ""), fuelStatus(client, own.characterId))
            val request = request(own.characterId)
            client.send(request)
            expectRefusal(client,"연료가 가득 찼습니다")
            assertEquals(own, fixture.engine.snapshot().navigators.getValue(fixture.account(0)))
            client.send(ByteBuffer.allocate(33).putShort(0x0b01).putInt(0).putInt(0).putInt(own.characterId)
                .putShort(59).putInt(Int.MAX_VALUE).putInt(Int.MAX_VALUE).putShort(2488).put(0).putInt(0).putShort(5).array())
            for (ignored in 0..10) { if (code(client.receive()) == 0x0b0a) break }
            val ack = client.request(request, 0x0b02, "supply")
            assertEquals(0, ByteBuffer.wrap(ack).int)
            assertEquals(800, ByteBuffer.wrap(ack).getInt(24))
            client.expect(0x0356)
            val started = fixture.engine.snapshot().navigators.getValue(fixture.account(0))
            assertEquals(900, started.cruise)
            val job = checkNotNull(started.strategy).jobs.single()
            assertEquals(listOf("FS1", (job.startsAtGameMillis / 1000).toString(), started.fuelReadyAt.toString()),
                fuelStatus(client, own.characterId))
            client.send(request)
            expectRefusal(client,"이미 보급 중입니다")
            assertEquals(started, fixture.engine.snapshot().navigators.getValue(fixture.account(0)))
            client.send(ByteBuffer.allocate(33).putShort(0x0b01).putInt(0).putInt(0).putInt(own.characterId)
                .putShort(59).putInt(0).putInt(0).putShort(2489).put(0).putInt(0).putShort(5).array())
            val deniedWarp = ByteBuffer.wrap(client.receive())
            assertEquals(1, deniedWarp.int); assertEquals(0x0b01, deniedWarp.short.toInt())
            assertEquals(800, deniedWarp.getInt(24)); assertEquals(2488, deniedWarp.getShort(28).toInt())
            assertEquals(started, fixture.engine.snapshot().navigators.getValue(fixture.account(0)))
            fixture.engine.submit(WorldCommand.AdvanceTo(104 * 3600_000L / 24))
            client.send(SyntheticClient.opcode(0x0300))
            var clock = false
            var notification: ByteArray? = null
            for (ignored in 0..10) {
                val message = client.receive()
                when (code(message)) { 0x0301 -> clock = true; 0x0b0c -> notification = message }
                if (clock && notification != null) break
            }
            val supplied = ByteBuffer.wrap(assertNotNull(notification))
            assertEquals(own.characterId, supplied.getInt(14))
            assertEquals(1, supplied.get(18).toInt())
            assertEquals(own.unitId, supplied.getInt(19))
            assertEquals(1000, supplied.getInt(23))
            val completed = fixture.engine.snapshot().navigators.getValue(fixture.account(0))
            assertNull(completed.fuelReadyAt); assertEquals(1000, completed.cruise)
            assertEquals(listOf("FS1", "", ""), fuelStatus(client, own.characterId))
            client.send(statusQuery(own.characterId + 1))
            client.expectClosed()
        }
    }
    @Test fun aPlayerWithInsufficientCpCanRetryOnTheSameConnectionAfterRecovery() = runBlocking<Unit> {
        val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Empire"),GalaxySystem(4,2015,4,"Alliance")),
            emptyList(),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
        val store = object : NavigationStore {
            private var players = mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,245,0,900))
            override fun load()=players
            override fun save(players: Map<String,Navigator>) { this.players=players }
        }
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false,navigationStore=store).use { fixture ->
            val client=fixture.join(fixture.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00))
            for (ignored in 0..300) { if (code(client.receive()) == 0x0f01) break }
            val before=store.load().values.single()
            val request=request(before.characterId)
            client.send(request)
            expectRefusal(client,"MCP 160 또는 PCP 320 필요")
            assertEquals(before,store.load().values.single())
            fixture.engine.submit(WorldCommand.AdvanceTo(2 * 3600_000L / 24))
            val reply=ByteBuffer.wrap(client.request(request,0x0b02,"supply with alternate CP"))
            assertEquals(0,reply.int)
            assertEquals(0,reply.getInt(20));assertEquals(75,reply.getInt(24))
            val after=store.load().values.single()
            assertEquals(900,after.cruise);assertNotNull(after.fuelReadyAt)
        }
    }
}
