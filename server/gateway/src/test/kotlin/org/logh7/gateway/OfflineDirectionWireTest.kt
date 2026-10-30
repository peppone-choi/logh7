package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class OfflineDirectionWireTest {
    @TempDir lateinit var directory:Path
    private fun code(packet:ByteArray)=ByteBuffer.wrap(packet).getShort(4).toInt() and 65535
    private fun through(client:SyntheticClient,end:Int):List<ByteArray> {
        val packets=mutableListOf<ByteArray>()
        repeat(300){packets+=client.receive();if(code(packets.last())==end)return packets}
        error("Missing $end")
    }
    private fun switches(packet:ByteArray):List<Int> {
        val input=ByteBuffer.wrap(packet);input.position((if(code(packet)==0x356)7 else 6)+36)
        val name=input.get().toInt() and 255;input.position(input.position()+name*2+26)
        return List(6){input.get().toInt() and 255}
    }
    @Test fun originalSettingsCommandPersistsFiveFlagsAndReconnectReadsTheSameCharacterRecord()=runBlocking<Unit> {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        var saved=emptyMap<String,Navigator>()
        var stocks=PlanetStocks(1,SessionRules.load().startSeconds,mapOf(1000 to PlanetStock(1000,2,0,100,0,0)))
        val nav=object:NavigationStore {override fun load()=saved;override fun save(players:Map<String,Navigator>){saved=players}}
        val store=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        val commit=WorldStateCommitter {players,stock->saved=players;stocks=checkNotNull(stock)}
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false,navigationStore=nav,stockStore=store,worldStateCommitter=commit).use {fixture ->
            val account=fixture.account(0);val first=fixture.join(account,true,false).client
            first.send(SyntheticClient.opcode(0x0f00));through(first,0x0f01)
            val before=fixture.engine.snapshot().navigators.getValue(account)
            first.send(ByteBuffer.allocate(15).putShort(0x0f18).putInt(123).putInt(10000)
                .put(byteArrayOf(1,0,1,0,1)).array())
            val packets=through(first,0x356)
            val ack=ByteBuffer.wrap(packets.single {code(it)==0xf18})
            assertEquals(0,ack.int);assertEquals(SessionRules.load().startSeconds,ack.getInt(6).toLong())
            assertContentEquals(byteArrayOf(1,0,1,0,1),ack.array().copyOfRange(14,19))
            assertEquals(listOf(0,1,0,1,0,1),switches(packets.last()))
            assertEquals(21,stocks.tacticalCommands.aiSwitches[10000])
            assertEquals(before.pcp,fixture.engine.snapshot().navigators.getValue(account).pcp)
            first.close();fixture.awaitState(0,0,1)
            val second=fixture.join(account,false,false).client
            second.send(SyntheticClient.opcode(0x0f00))
            val restored=through(second,0x0f01).single {code(it)==0x323}
            assertEquals(listOf(0,1,0,1,0,1),switches(restored))
        }
    }
}
