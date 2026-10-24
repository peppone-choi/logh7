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

class NavigationRecoveryWireTest {
    @TempDir lateinit var directory: Path
    private fun code(message: ByteArray) = ByteBuffer.wrap(message).getShort(4).toInt() and 65535
    private fun through(client: SyntheticClient, end: Int): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()
        repeat(300) { messages += client.receive(); if (code(messages.last()) == end) return messages }
        error("Missing transaction end")
    }
    @Test fun serverPushesAuthoritativeCpToTheHudWithoutClientClockPolling() = runBlocking<Unit> {
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire"), GalaxySystem(4, 2015, 4, "Alliance")),
            emptyList(), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
        LoadFixture(directory, 1, LoadMetrics(), galaxy, ticking = false).use { fixture ->
            val client = fixture.join(fixture.account(0), create = true, initialize = false).client
            client.send(SyntheticClient.opcode(0x0f00)); through(client, 0x0f01)
            val warp = NavigationMessages.accepted(NavigationMessages.Warp(10000, 59, 2488, 0, 5), 0, 0, 0)
            client.send(warp.copyOfRange(4, warp.size)); through(client, 0x0b07); through(client, 0x0b0a)
            assertEquals(960, fixture.engine.snapshot().navigators.getValue(fixture.account(0)).mcp)
            fixture.clock(client) // No unsolicited character update before the interval.
            fixture.engine.submit(WorldCommand.AdvanceTo(300000))
            val refreshed = through(client, 0x0356)
            assertEquals(listOf(0x0356), refreshed.map(::code))
            val message = refreshed.last()
            assertEquals(0, message[6].toInt())
            assertEquals(10000, ByteBuffer.wrap(message).getInt(7))
            val nameLength = message[7 + 36].toInt() and 255
            val cpOffset = 7 + 37 + 2 * nameLength + 12
            assertEquals(1000, ByteBuffer.wrap(message).getInt(cpOffset))
            assertEquals(1000, ByteBuffer.wrap(message).getInt(cpOffset + 4))
            val player = fixture.engine.snapshot().navigators.getValue(fixture.account(0))
            assertEquals(1000, player.mcp); assertEquals(2488, player.grid); assertEquals(900, player.cruise)
            fixture.clock(client) // Same timestamp cannot duplicate the recovery or add another HUD update.
        }
    }
}
