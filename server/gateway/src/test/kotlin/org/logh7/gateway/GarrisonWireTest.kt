package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import kotlin.test.*

class GarrisonWireTest {
    @TempDir lateinit var directory: Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,"Planet",0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2487,10,1,"Fortress",0,30f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private fun next(client: SyntheticClient,opcode: Int): ByteArray {
        repeat(100) { val message=client.receive();if (ByteBuffer.wrap(message).getShort(4).toInt()==opcode) return message }
        error("Missing response $opcode")
    }
    @Test fun encryptedLoginAndPublicQueriesUseBaseCardsWithoutGrantingOutfitCommand() {
        val store=object : PlanetStockStore {
            var state=PlanetStocks(1,0,(1049..1050).associateWith {
                PlanetStock(it,2,0,100,200,300,garrisonCommanderId=10000)
            },deploymentInitialized=true)
            override fun load()=state
            override fun save(stocks: PlanetStocks) { state=stocks }
        }
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false,stockStore=store).use { fixture ->
            val client=fixture.join(fixture.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00))
            val initial=next(client,0x0323)
            val tail=ByteBuffer.wrap(initial,initial.size-26,26)
            assertEquals(4,tail.get().toInt());tail.position(tail.position()+12)
            assertEquals(55,tail.short.toInt());assertEquals(1049,tail.int)
            assertEquals(52,tail.short.toInt());assertEquals(1050,tail.int)
            next(client,0x0f01)
            for ((card,base) in listOf(55 to 1049,52 to 1050)) {
                client.send(ByteBuffer.allocate(4).putShort(0x034e).putShort(card.toShort()).array())
                val response=next(client,0x034f)
                assertEquals(card,ByteBuffer.wrap(response).getShort(6).toInt())
                assertEquals(1,response[8].toInt());assertEquals(10000,ByteBuffer.wrap(response).getInt(9))
                assertEquals(base,ByteBuffer.wrap(response).getInt(response.size-5))
            }
            client.send(ByteBuffer.allocate(4).putShort(0x034e).putShort(54).array())
            assertEquals(0,next(client,0x034f)[8].toInt())
            assertTrue(store.state.outfits.values.none { it.commanderId==10000 })
            assertTrue(store.state.bases.values.all { it.governorId==0 && it.garrisonCommanderId==10000 })
        }
    }
}
