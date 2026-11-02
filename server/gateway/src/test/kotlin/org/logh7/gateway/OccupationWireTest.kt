package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class OccupationWireTest {
    @TempDir lateinit var directory:Path
    @Test fun encryptedPeersReceiveCaptureOnlyAfterCombatCommitAndLoserIsExpelled()=runBlocking<Unit> {
        val base=GalaxyBase(1000,2487,80,0,"Here",0,20f,86400,0,0f,2f)
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3)),listOf(base),Power.entries.associateWith {2487})
        val troops=listOf(2 to 0,3 to 4).mapIndexed {i,(power,kind)->
            val roster=ActiveGroundUnits(listOf(ActiveGroundUnit(i+1,UnitStack(kind),base=1000,strength=if(power==3)1 else 1000)))
            i+1 to OutfitStock(i+1,power,OutfitKind.GROUND,1000,101+i,
                units=UnitInventory(troops=roster.quantities()),activeGround=roster)
        }.toMap()
        var saved=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,3,0,100,100,100,governorId=10001)),
            troops,troops.values.associate {it.warehouseId to OutfitWarehouse(it.warehouseId,it.power,OutfitKind.PATROL,1000)},
            deploymentInitialized=true,nextShipId=3)
        var players=mapOf("load-001" to Navigator(1,10001,Power.ALLIANCE,2487,700,500,1000,baseId=1000,mode=4,returnBase=1000))
        val stocks=object:PlanetStockStore {override fun load()=saved;override fun save(stocks:PlanetStocks){saved=stocks}}
        val navigation=object:NavigationStore {
            override fun load()=players;override fun save(next:Map<String,Navigator>){players=next}
        }
        val commit=WorldStateCommitter {next,inventory->players=next;saved=checkNotNull(inventory)}
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,stockStore=stocks,navigationStore=navigation,
            worldStateCommitter=commit,powerByIndex={if(it==1)3 else 2}).use {fixture->
            fun code(packet:ByteArray)=ByteBuffer.wrap(packet).getShort(4).toInt() and 65535
            val clients=(0..1).map {i->fixture.join(fixture.account(i),true,false).client.also {c->
                c.send(SyntheticClient.opcode(0x0f00))
                var ready=false
                repeat(300) {if(!ready && code(c.receive())==0x0f01)ready=true}
                assertTrue(ready)
            }}
            val now=fixture.engine.snapshot().session!!.gameSeconds
            fixture.engine.submit(WorldCommand.AdvanceTo(5_000))
            fixture.await("captured world") {fixture.engine.snapshot().planetStocks!!.bases.getValue(1000).power==2}
            assertEquals(0,players.getValue("load-001").baseId)
            assertEquals(0,saved.bases.getValue(1000).governorId)
            for((i,client) in clients.withIndex()) {
                // Actor publication and this query can reach the channel in either order.
                client.send(ByteBuffer.allocate(7).putShort(0x031e).put(1).putInt(1000).array())
                val seen=mutableListOf<ByteArray>();var finished=false
                repeat(300) {
                    if(!finished) {
                        val packet=try {client.receive()} catch(failure:java.net.SocketTimeoutException) {
                            throw AssertionError("occupation viewer=$i received="+seen.map {code(it).toString(16)},failure)
                        };seen+=packet
                        finished=seen.any {code(it)==0x031f && it[11].toInt()==2} &&
                            seen.any {code(it)==0x0321} && seen.any {code(it)==if(i==0)0x0442 else
                                org.logh7.protocol.generated.MessageId.NotifySearch.opcode} &&
                            (i==0 || seen.any {code(it)==0x0f1f && it[6].toInt()==1})
                    }
                }
                assertTrue(finished)
                assertTrue(seen.any {code(it)==0x031f && it[11].toInt()==2})
                assertTrue(seen.any {code(it)==0x0321})
                if(i==0)assertTrue(seen.any {code(it)==0x0442}) else
                    assertTrue(seen.any {code(it)==0x0f1f && it[6].toInt()==1})
            }
            assertTrue(fixture.engine.snapshot().session!!.gameSeconds>=now+5)
        }
    }
}
