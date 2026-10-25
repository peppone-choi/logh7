package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class ReturnBaseWireTest {
    @TempDir lateinit var directory: Path
    private fun code(packet: ByteArray) = ByteBuffer.wrap(packet).getShort(4).toInt() and 65535
    private fun through(client: SyntheticClient, end: Int): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        repeat(300) { packets += client.receive(); if (code(packets.last()) == end) return packets }
        error("Missing opcode $end")
    }
    private fun select(client: SyntheticClient, base: Int): List<ByteArray> {
        val packet = ReturnBaseMessages.reply(10000, base, 0)
        client.send(packet.copyOfRange(4, packet.size))
        return through(client, 0x0356)
    }
    @Test fun selectionDenialsReconnectAndCpUpdatesKeepAuthoritativeReturnPlanet() = runBlocking<Unit> {
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire", 2), GalaxySystem(4, 2015, 4, "Alliance", 3)),
            listOf(GalaxyBase(1048, 2487, 80, 0, "Soest", 0, 20f, 86400, 0, 0f, 2f),
                GalaxyBase(1050, 2487, 30, 0, "Gas", 0, 40f, 86400, 0, 0f, 2f),
                GalaxyBase(2015, 2015, 60, 0, "Alliance", 0, 20f, 86400, 0, 0f, 2f)),
            mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
        LoadFixture(directory, 1, LoadMetrics(), galaxy, ticking = false).use { fixture ->
            val account = fixture.account(0)
            val first = fixture.join(account, true, false).client
            first.send(SyntheticClient.opcode(0x0f00)); through(first, 0x0f01)
            val before = fixture.engine.snapshot().navigators.getValue(account)
            val accepted = select(first, 1048)
            assertEquals(listOf(0x0f1a, 0x0356), accepted.map(::code))
            assertEquals(0, ByteBuffer.wrap(accepted[0]).int)
            assertEquals(1048, ByteBuffer.wrap(accepted[0]).getInt(14))
            assertEquals(1048, ByteBuffer.wrap(accepted[1]).getInt(27))
            assertEquals(before.copy(returnBase = 1048), fixture.engine.snapshot().navigators.getValue(account))
            for (base in listOf(1050, 2015, 65535)) {
                val denied = select(first, base)
                assertEquals(1, ByteBuffer.wrap(denied[0]).int)
                assertEquals(1048, ByteBuffer.wrap(denied[0]).getInt(14))
                assertEquals(1048, ByteBuffer.wrap(denied[1]).getInt(27))
            }
            first.close(); fixture.awaitState(0, 0, 1)
            val second = fixture.join(account, false, false).client
            second.send(SyntheticClient.opcode(0x0f00))
            val restored = through(second, 0x0f01).single { code(it) == 0x0323 }
            assertEquals(1048, ByteBuffer.wrap(restored).getInt(26))
            val warp = NavigationMessages.accepted(NavigationMessages.Warp(10000, 59, 2488, 0, 5), 0, 0, 0)
            second.send(warp.copyOfRange(4, warp.size)); through(second, 0x0b0a)
            fixture.engine.submit(WorldCommand.AdvanceTo(300000))
            val refreshed = through(second, 0x0356).last()
            assertEquals(1048, ByteBuffer.wrap(refreshed).getInt(27))
            val reset = select(second, 0)
            assertEquals(0, ByteBuffer.wrap(reset[0]).getInt(14))
            assertEquals(0, fixture.engine.snapshot().navigators.getValue(account).returnBase)
        }
    }
}
