package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
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
