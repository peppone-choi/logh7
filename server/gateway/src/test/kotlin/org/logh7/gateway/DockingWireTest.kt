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

class DockingWireTest {
    @TempDir lateinit var directory: Path
    private fun code(packet: ByteArray) = ByteBuffer.wrap(packet).getShort(4).toInt() and 65535
    private fun through(client: SyntheticClient, end: Int): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        repeat(300) { packets += client.receive(); if (code(packets.last()) == end) return packets }
        error("Missing opcode $end")
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
}
