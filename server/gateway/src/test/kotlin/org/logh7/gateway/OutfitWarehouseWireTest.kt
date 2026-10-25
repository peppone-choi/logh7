package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class OutfitWarehouseWireTest {
    @TempDir lateinit var directory: Path
    private val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2487))
    private fun snapshot() = PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,1000,2000,3000)),
        mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,101,10000),
            2 to OutfitStock(2,2,OutfitKind.PATROL,1049,102,20000),
            3 to OutfitStock(3,2,OutfitKind.PATROL,1049,102,30000)),
        mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1049,1,UnitInventory(mapOf(UnitStack(32,1) to 7),mapOf(UnitStack(3) to 35)),500,600,700),
            102 to OutfitWarehouse(102,2,OutfitKind.PATROL,1049)),
        mapOf(40000 to LogisticsOffice.EMPIRE_FIRST))
    @Test fun realEncryptedQueryReturnsOwnedOutfitWarehouseIdentityAndNeverPlanetBalances() = runBlocking<Unit> {
        val store = object : PlanetStockStore {
            var stocks = snapshot()
            override fun load() = stocks
            override fun save(stocks: PlanetStocks) { this.stocks = stocks }
        }
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false,stockStore=store).use { fixture ->
            val client = fixture.join(fixture.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00))
            for (ignored in 0..300) if (ByteBuffer.wrap(client.receive()).getShort(4).toInt() == 0x0f01) break
            fun query() = client.request(ByteBuffer.allocate(10).putShort(0x0326).putInt(1049).putInt(1).array(),0x0327,"outfitWarehouse")
            val response = query()
            val input = ByteBuffer.wrap(response); input.position(6)
            assertEquals(1049,input.int); assertEquals(1,input.int); assertEquals(0,input.int)
            assertEquals(1,input.get().toInt()); assertEquals(32,input.short.toInt()); assertEquals(1,input.get().toInt())
            assertEquals(7,input.short.toInt())
            assertEquals(1,input.get().toInt()); assertEquals(1003,input.short.toInt()); assertEquals(0,input.get().toInt())
            assertEquals(35,input.short.toInt())
            assertEquals(500,input.int); assertEquals(600,input.int); assertEquals(700,input.int)
            assertFalse(input.hasRemaining())
            assertContentEquals(response,query())
            assertEquals(7,store.stocks.warehouses.getValue(101).units.ships[UnitStack(32,1)])
        }
    }
    @Test fun otherCommandersCannotReadPrivateWarehousesAndOfficersHaveCategoryRestrictions() {
        val world = WorldBootstrap(galaxy)
        val stocks = snapshot()
        assertFails { world.outfitWarehouse(1049,1,30000,2,stocks) }
        assertFails { world.outfitWarehouse(1049,1,10000,3,stocks) }
        assertFails { world.outfitWarehouse(1048,1,10000,2,stocks) }
        assertFails { world.outfitWarehouse(1049,2,40000,2,stocks) }
        assertEquals(0x0327,ByteBuffer.wrap(world.outfitWarehouse(1049,1,40000,2,stocks)).getShort(4).toInt())
        assertEquals(0x0327,ByteBuffer.wrap(world.outfitWarehouse(1049,3,20000,2,stocks)).getShort(4).toInt())
    }
}
