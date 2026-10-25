package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class UnitProductionWireTest {
    @TempDir lateinit var directory: Path
    private fun code(bytes: ByteArray) = ByteBuffer.wrap(bytes).getShort(4).toInt() and 65535
    @Test fun realCipherWarehouseCarriesProducedQuantitiesAndRepeatedQueryDoesNotProduceAgain() = runBlocking<Unit> {
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "ヴァルハラ", 2)),
            listOf(GalaxyBase(1049, 2487, 80, 0, "オーディン", 0, 20f, 86400, 0, 0f, 2f)),
            mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2487))
        LoadFixture(directory, 1, LoadMetrics(), galaxy, ticking = false).use { fixture ->
            val client = fixture.join(fixture.account(0), true, false).client
            client.send(SyntheticClient.opcode(0x0f00))
            for (ignored in 0..300) if (code(client.receive()) == 0x0f01) break
            fixture.engine.submit(WorldCommand.AdvanceTo(5 * 3600000L))
            fun warehouse() = client.request(ByteBuffer.allocate(10).putShort(0x0326).putInt(1049).putInt(0).array(), 0x0327, "warehouse")
            val first = warehouse(); val input = ByteBuffer.wrap(first); input.position(18)
            fun entries(): Map<UnitStack, Int> = buildMap {
                repeat(input.get().toInt() and 255) { put(UnitStack(input.short.toInt() and 65535, input.get().toInt() and 255), input.short.toInt() and 65535) }
            }
            val ships = entries(); val troops = entries()
            val stock = fixture.engine.snapshot().planetStocks!!.bases.getValue(1049)
            assertEquals(stock.units.ships, ships); assertEquals(stock.units.troops, troops)
            assertEquals(5, troops[UnitStack(3)]); assertTrue(ships.isNotEmpty())
            assertEquals(stock.supplies, input.int); assertEquals(stock.food, input.int); assertEquals(stock.mineral, input.int)
            assertContentEquals(first, warehouse())
        }
    }
}
