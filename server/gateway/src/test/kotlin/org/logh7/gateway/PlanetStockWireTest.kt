package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class PlanetStockWireTest {
    @TempDir lateinit var directory: Path
    private fun code(message: ByteArray) = ByteBuffer.wrap(message).getShort(4).toInt() and 65535
    @Test fun warehouseAndPlanetInformationShareTheSameAuthoritativeProducedSupplies() = runBlocking<Unit> {
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire", 2), GalaxySystem(4, 2015, 4, "Alliance", 3)),
            listOf(GalaxyBase(1000, 2487, 60, 0, "Home", 0, 20f, 86400, 0, 0f, 2f)),
            mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
        LoadFixture(directory, 1, LoadMetrics(), galaxy, ticking = false).use { fixture ->
            val client = fixture.join(fixture.account(0), create = true, initialize = false).client
            client.send(SyntheticClient.opcode(0x0f00))
            for (ignored in 0..300) { if (code(client.receive()) == 0x0f01) break }
            fun warehouse() = client.request(ByteBuffer.allocate(10).putShort(0x0326).putInt(1000).putInt(0).array(), 0x0327, "warehouse")
            assertEquals(100000, ByteBuffer.wrap(warehouse()).getInt(20))
            fixture.engine.submit(WorldCommand.AdvanceTo(3600000))
            repeat(2) { assertEquals(101000, ByteBuffer.wrap(warehouse()).getInt(20)) }
            val condition = client.request(ByteBuffer.allocate(7).putShort(0x031e).put(1).putInt(1000).array(), 0x031f, "condition")
            assertEquals(101000, ByteBuffer.wrap(condition).getInt(29))
            val stored = fixture.engine.snapshot().planetStocks!!.bases.getValue(1000)
            assertEquals(stored.economy!!.population, ByteBuffer.wrap(condition).getInt(47))
            assertEquals(stored.economy!!.adults, ByteBuffer.wrap(condition).getInt(51))
            assertEquals(stored.food, ByteBuffer.wrap(condition).getInt(99))
            assertEquals(stored.food, ByteBuffer.wrap(warehouse()).getInt(24))
            assertEquals(stored.mineral, ByteBuffer.wrap(warehouse()).getInt(28))
            assertEquals(1, fixture.engine.snapshot().planetStocks!!.producedDays)
            fixture.clock(client)
        }
    }
}
