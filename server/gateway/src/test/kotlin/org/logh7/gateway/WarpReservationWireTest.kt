package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.NavigationMessages
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

/** Distance-scaled warp waits (design 2026-10-30): ACK with the wait, 0b07 only on execution. */
class WarpReservationWireTest {
    @TempDir lateinit var directory: Path
    private val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire"), GalaxySystem(4, 2015, 4, "Alliance")),
        emptyList(), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
    private fun until(client: SyntheticClient, opcode: Int): ByteBuffer {
        repeat(300) { val message = ByteBuffer.wrap(client.receive()); if (message.getShort(4).toInt() == opcode) return message }
        error("Missing 0x%04x".format(opcode))
    }
    private fun warp(client: SyntheticClient, grid: Int) {
        val request = NavigationMessages.accepted(NavigationMessages.Warp(10000, 59, grid, 0, 5), 0, 0, 0)
        client.send(request.copyOfRange(4, request.size))
    }

    @Test fun reservedWarpAcknowledgesTheWaitAndCompletesOnlyAfterExecution() = runBlocking<Unit> {
        LoadFixture(directory, 1, LoadMetrics(), galaxy, ticking = false).use { fixture ->
            val client = fixture.join(fixture.account(0), true, false).client
            client.send(org.logh7.gateway.load.SyntheticClient.opcode(0x0f00)); until(client, 0x0f01)
            warp(client, 2588) // Diagonal: 142 ly, two started 100 ly -> 80 MCP and one G-hour.
            val ack = until(client, 0x0b01)
            assertEquals(0, ack.getInt(0)); assertEquals(3600, ack.getInt(10)); assertEquals(920, ack.getInt(24))
            assertEquals(2588, ack.getShort(28).toInt())
            client.expect(0x0356) // CP only; no 0317/0b07 while the fleet waits at its origin.
            val reserved = fixture.engine.snapshot().navigators.values.single()
            assertEquals(2487, reserved.grid); assertEquals(2588, reserved.warpTarget)
            fixture.engine.submit(WorldCommand.AdvanceTo(200_000))
            fixture.clock(client)
            assertEquals(2588, until(client, 0x0317).getShort(6).toInt())
            val moved = until(client, 0x0b07)
            assertEquals(2588, moved.getInt(14)); assertEquals(858, moved.getInt(29))
            val stored = fixture.engine.snapshot().navigators.values.single()
            assertEquals(2588, stored.grid); assertNull(stored.warpTarget); assertEquals(920, stored.mcp)
        }
    }
}
