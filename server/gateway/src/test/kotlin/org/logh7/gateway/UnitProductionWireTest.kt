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
            fun entries(offset: Int = 0): Map<UnitStack, Int> = buildMap {
                repeat(input.get().toInt() and 255) { put(UnitStack((input.short.toInt() and 65535) - offset, input.get().toInt() and 255), input.short.toInt() and 65535) }
            }
            val ships = buildMap {
                repeat(input.get().toInt() and 255) {
                    val kind = input.short.toInt() and 65535
                    val count = input.get().toInt() and 255; val boats = input.short.toInt() and 65535
                    assertEquals(count*UnitSpecifications.ships.single { it.kind == kind }.number,boats)
                    put(kind,count)
                }
            }
            val troops = entries(1000)
            val stock = fixture.engine.snapshot().planetStocks!!.bases.getValue(1049)
            assertEquals(stock.units.ships.entries.groupBy { it.key.kind }.mapValues { (_, rows) -> rows.sumOf { it.value } }, ships); assertEquals(stock.units.troops, troops)
            assertEquals(5, troops[UnitStack(3)]); assertTrue(ships.isNotEmpty())
            assertEquals(stock.supplies, input.int); assertEquals(stock.food, input.int); assertEquals(stock.mineral, input.int)
            assertContentEquals(first, warehouse())
        }
    }
    @Test fun capturedShipyardPublishesCaptorProductionAndDoesNotReuseForeignProgress()=runBlocking<Unit> {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"ヴァルハラ",2)),
            listOf(GalaxyBase(1051,2487,80,0,"ヴァルグリンド",0,20f,86400,0,0f,2f)),
            Power.entries.associateWith {2487})
        var saved:PlanetStocks?=PlanetStocks(1,0,mapOf(1051 to PlanetStock(1051,3,0,100000,10000000,100000,
            units=UnitInventory(work=mapOf("S64" to 179,"T3" to 119)))),deploymentInitialized=true)
        val store=object:PlanetStockStore {
            override fun load()=saved
            override fun save(stocks:PlanetStocks){saved=stocks}
        }
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false,stockStore=store,powerByIndex={3}).use {f->
            val client=f.join(f.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00))
            for(ignored in 0..300)if(code(client.receive())==0x0f01)break
            f.engine.submit(WorldCommand.AdvanceTo(10*3600000L))
            val query=ByteBuffer.allocate(10).putShort(0x0326).putInt(1051).putInt(0).array()
            val first=client.request(query,0x0327,"captured warehouse")
            val input=ByteBuffer.wrap(first).position(18)
            val ships=buildMap {repeat(input.get().toInt() and 255){
                val kind=input.short.toInt() and 65535;val count=input.get().toInt() and 255
                input.short;put(kind,count)
            }}
            val troops=buildMap {repeat(input.get().toInt() and 255){
                val kind=(input.short.toInt() and 65535)-1000;input.get();put(kind,input.short.toInt() and 65535)
            }}
            assertEquals(1,ships[147]);assertFalse(64 in ships)
            assertTrue(troops.getValue(7)>0 && troops.getValue(4)>0)
            assertTrue(ships.keys.all {it>=119} && troops.keys.all {it in 4..7})
            val stock=saved!!.bases.getValue(1051)
            assertFalse("S64" in stock.units.work || "T3" in stock.units.work)
            assertEquals(stock.supplies,input.int);assertEquals(stock.food,input.int);assertEquals(stock.mineral,input.int)
            assertContentEquals(first,client.request(query,0x0327,"repeat captured warehouse"))
        }
    }

}
