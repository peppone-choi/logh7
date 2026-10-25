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
