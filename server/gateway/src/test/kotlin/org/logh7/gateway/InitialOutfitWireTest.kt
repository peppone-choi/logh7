package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class InitialOutfitWireTest {
    @TempDir lateinit var directory: Path
    private val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"ヴァルハラ",2),GalaxySystem(4,2015,4,"バーラト",3)),
        listOf(GalaxyBase(1049,2487,80,0,"オーディン",0,20f,86400,0,0f,2f),
            GalaxyBase(2000,2015,80,0,"ハイネセン",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    @Test fun encryptedPartyAndUnitQueriesUseTheSameDurableIdentities() = runBlocking<Unit> {
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false).use { fixture ->
            val client = fixture.join(fixture.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00))
            for (ignored in 0..300) if (ByteBuffer.wrap(client.receive()).getShort(4).toInt() == 0x0f01) break
            val stocks = fixture.engine.snapshot().planetStocks!!
            val expected = stocks.outfits.getValue(1)
            val request = ByteBuffer.allocate(11).putShort(0x032e).putInt(1).putInt(1049).put(0).array()
            val first = client.request(request,0x032f,"outfitParty")
            val party = ByteBuffer.wrap(first); party.position(6)
            assertEquals(1,party.int); assertEquals(1049,party.int); assertEquals(0,party.get().toInt())
            assertEquals(2,party.get().toInt()); assertEquals(0,party.get().toInt()); assertEquals(0,party.int); assertEquals(1,party.int)
            assertEquals(0,party.get().toInt()); assertEquals(3,party.get().toInt())
            val ids = mutableListOf<Int>()
            repeat(3) {
                val kind = party.short.toInt(); val count = party.get().toInt(); val boats = party.short.toInt()
                assertEquals(count*300,boats); assertEquals(count,party.get().toInt())
                repeat(count) {
                    val id = party.int; ids += id
                    assertEquals(kind,expected.activeShips!!.values.single { it.id == id }.stack.kind)
                }
            }
            assertEquals(expected.activeShips!!.values.map { it.id }.toSet(),ids.toSet())
            assertEquals(40,ids.size)
            for (id in listOf(ids.first(),ids.last())) {
                val bytes = client.request(ByteBuffer.allocate(10).putShort(0x0330).putInt(id).putInt(1049).array(),0x0331,"outfitUnit")
                val unit = ByteBuffer.wrap(bytes); unit.position(6)
                assertEquals(1,unit.get().toInt()); assertEquals(id,unit.int)
                assertEquals(expected.activeShips!!.values.single { it.id == id }.stack.kind,unit.short.toInt())
                assertEquals(4,unit.get().toInt()); assertEquals(2487,unit.int); assertEquals(1,unit.int)
            }
            assertContentEquals(first,client.request(request,0x032f,"outfitPartyAgain"))
            assertEquals(stocks,fixture.engine.snapshot().planetStocks)
            val world = WorldBootstrap(galaxy)
            assertFails { world.outfitParty(org.logh7.protocol.OutfitPartyMessages.Query(1,2000,0),2,stocks) }
            assertFails { world.outfitParty(org.logh7.protocol.OutfitPartyMessages.Query(1,1049,0),3,stocks) }
            assertFails { world.outfitParty(org.logh7.protocol.OutfitPartyMessages.Query(1,1049,1),2,stocks) }
            assertFails { world.outfitUnit(ids.first(),3,stocks) }
            assertFails { world.outfitUnit(ids.first(),2,stocks,2000) }
            assertEquals(0,world.outfitUnit(999999,2,stocks)[6].toInt())
        }
    }
    @Test fun authenticatedDirectoryQueriesUseTheManualDeploymentAndDoNotRefillIt() = runBlocking<Unit> {
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false).use { fixture ->
            val client = fixture.join(fixture.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00))
            for (ignored in 0..300) if (ByteBuffer.wrap(client.receive()).getShort(4).toInt() == 0x0f01) break
            fun list(grid: Int, power: Int) = client.request(ByteBuffer.allocate(8).putShort(0x032c).putInt(grid)
                .put(power.toByte()).put(0).array(),0x032d,"outfitDirectory")
            val first = list(2487,2)
            val data = ByteBuffer.wrap(first); data.position(6)
            assertEquals(24,data.short.toInt())
            repeat(24) {
                val id = data.int; val kind = data.get().toInt(); val power = data.get().toInt()
                assertEquals(0,data.get().toInt()); val number = data.get().toInt(); val supplies = data.int
                val stored = fixture.engine.snapshot().planetStocks!!.outfits.getValue(id)
                assertEquals(stored.kind.wire,kind); assertEquals(2,power); assertEquals(stored.number,number)
                assertEquals(stored.supplies,supplies)
            }
            assertFalse(data.hasRemaining())
            assertEquals(12,ByteBuffer.wrap(list(2015,3)).getShort(6).toInt())
            assertEquals(0,ByteBuffer.wrap(list(2487,3)).getShort(6).toInt())
            assertEquals(36,ByteBuffer.wrap(list(-1,255)).getShort(6).toInt())
            val detail = client.request(ByteBuffer.allocate(11).putShort(0x032a).put(2).putInt(1).putInt(133).array(),0x032b,"outfitInformation")
            val info = ByteBuffer.wrap(detail); info.position(6)
            assertEquals(2,info.get().toInt())
            for ((id,power) in listOf(1 to 2,133 to 3)) {
                assertEquals(id,info.int); assertEquals(0,info.get().toInt()); assertEquals(power,info.get().toInt())
                assertEquals(0,info.get().toInt()); assertEquals(1,info.get().toInt())
                assertEquals(0,info.short.toInt()); assertEquals(0,info.int); repeat(10) { assertEquals(0,info.get().toInt()) }
            }
            val snapshot = fixture.engine.snapshot().planetStocks
            assertContentEquals(first,list(2487,2))
            assertEquals(snapshot,fixture.engine.snapshot().planetStocks)
        }
    }
}
