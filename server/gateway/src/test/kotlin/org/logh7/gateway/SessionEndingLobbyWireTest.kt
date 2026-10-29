package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class SessionEndingLobbyWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(GalaxyBase(1000,2487,80,0,"A",0,20f,86400,0,0f,2f),GalaxyBase(1001,2015,80,0,"B",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private fun lobby(f:LoadFixture,index:Int):SyntheticClient {
        val token=SyntheticClient(f.loginPort,LoadMetrics()).use {login->
            login.send(SyntheticClient.login(f.account(index),f.accounts.getValue(f.account(index))))
            val response=login.receive(false)
            assertEquals(0x7001,ByteBuffer.wrap(response).short.toInt() and 65535)
            ByteBuffer.wrap(response).getInt(14).toLong() and 0xffffffffL
        }
        return SyntheticClient(f.sessionPort,LoadMetrics()).also {
            it.send(SyntheticClient.token(token),SyntheticClient.opcode(0x2000));it.expect(0x2001)
        }
    }
    private fun charged(packet:ByteArray):Pair<Int,Int> {
        val b=ByteBuffer.wrap(packet).position(6)
        fun string(){val n=b.get().toInt() and 255;b.position(b.position()+n*2)}
        assertEquals(1,b.get().toInt());assertEquals(1,b.short.toInt());assertEquals(3,b.get().toInt())
        string();string();b.int
        repeat(2) {
            val power=b.get().toInt();assertTrue(power in 2..3)
            repeat(3){b.int};assertEquals(1,b.get().toInt());string();b.short
            repeat(5){b.get()};repeat(2){b.short};repeat(3){b.int}
        }
        assertEquals(1,b.get().toInt());repeat(2){b.short};repeat(3){b.int}
        assertEquals(listOf(0,2,1),List(3){b.get().toInt()})
        return b.int to b.get().toInt()
    }
    @Test fun endedAccountsInspectOnlyTheirChargedCharacterWithoutJoiningOrMutatingAndNewGenerationClearsIt()=runBlocking {
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,powerByIndex={if(it==1)3 else 2}).use {f->
            val peers=(0..1).map {index->f.join(f.account(index),true,false).client.also {client->
                client.send(SyntheticClient.opcode(0x0f00))
                while(ByteBuffer.wrap(client.receive()).getShort(4).toInt() and 65535!=0x0f01) { }
            }}
            val ids=(0..1).map {f.engine.snapshot().navigators.getValue(f.account(it)).characterId}
            f.engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 4,Power.ALLIANCE to 4),setOf(Power.ALLIANCE)))
            f.await("ended") {f.engine.snapshot().result!=null}
            peers.forEach {it.expect(0x035a);it.close()}
            f.await("no ended players online") {f.engine.snapshot().session!!.online==0 && f.access.trackedConnections()==0}
            val before=f.engine.snapshot()
            for(index in 0..1)lobby(f,index).use {client->
                client.send(SyntheticClient.opcode(0x2003));val reply=client.expect(0x2004)
                assertEquals(ids[index] to if(index==0)2 else 3,charged(reply))
                assertEquals(1,reply[reply.size-18].toInt());assertTrue(reply.takeLast(17).all {it==0.toByte()})
                client.send(ByteBuffer.allocate(4).putShort(0x2009).putShort(1).array());client.expect(0x200b)
                assertEquals(0,f.access.trackedConnections());assertEquals(before.navigators,f.engine.snapshot().navigators)
                assertEquals(before.planetStocks,f.engine.snapshot().planetStocks)
            }
            lobby(f,2).use {client->client.send(SyntheticClient.opcode(0x2003));assertEquals(0,client.expect(0x2004)[6].toInt())}
            lobby(f,0).use {client->
                f.engine.submit(WorldCommand.Restart);f.await("next generation") {f.engine.snapshot().session!!.generation==2L}
                client.send(SyntheticClient.opcode(0x2003));assertEquals(0,client.expect(0x2004)[6].toInt())
                client.send(SyntheticClient.opcode(0x2005)+byteArrayOf(0))
                val sessions=client.expect(0x2006);assertEquals(1,sessions[7].toInt());assertEquals(1,sessions[10].toInt())
            }
        }
    }
    @Test fun automaticNextGenerationRecreatesBothAccountsAndRestoresInitialWorldWithoutOldLocationsOrCosts()=runBlocking {
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,powerByIndex={if(it==1)3 else 2}).use {f->
            val old=(0..1).map {f.join(f.account(it),true,false).client.also {client->
                client.send(SyntheticClient.opcode(0x0f00))
                while(ByteBuffer.wrap(client.receive()).getShort(4).toInt() and 65535!=0x0f01) { }
            }}
            val warp=org.logh7.protocol.NavigationMessages.accepted(
                org.logh7.protocol.NavigationMessages.Warp(10000,59,2488,0,5),0,0,0)
            old[0].send(warp.copyOfRange(4,warp.size))
            while(ByteBuffer.wrap(old[0].receive()).getShort(4).toInt() and 65535!=0x0b0a) { }
            val previous=f.engine.snapshot()
            assertEquals(2488,previous.navigators.getValue(f.account(0)).grid)
            assertEquals(900,previous.navigators.getValue(f.account(0)).cruise)
            assertEquals(1L,previous.session!!.generation)
            f.engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 4,Power.ALLIANCE to 4),setOf(Power.ALLIANCE)))
            f.await("ended before next game") {f.engine.snapshot().result!=null}
            f.engine.submit(WorldCommand.AdvanceTo(SessionRules.load().restartDelayRealMillis))
            f.await("fresh generation") {f.engine.snapshot().session!!.generation==2L}
            old.forEach {it.expectClosed()}
            f.await("old game bindings removed") {f.access.trackedConnections()==0}
            assertNull(f.engine.snapshot().result)
            assertTrue(f.engine.snapshot().navigators.isEmpty())
            for(index in 0..1) {
                val client=f.join(f.account(index),true,false).client
                client.send(SyntheticClient.opcode(0x0f00))
                while(ByteBuffer.wrap(client.receive()).getShort(4).toInt() and 65535!=0x0f01) { }
                val player=f.engine.snapshot().navigators.getValue(f.account(index))
                assertEquals(2L,player.generation)
                assertEquals(if(index==0)Power.EMPIRE else Power.ALLIANCE,player.power)
                assertNull(player.motion)
                assertEquals(if(index==0)2487 else 2015,player.grid)
                assertEquals(0L,player.moves)
                assertEquals(1000,player.cruise)
                assertEquals(1000,player.mcp)
                client.close()
            }
            val fresh=f.engine.snapshot()
            assertEquals(2L,fresh.planetStocks!!.generation)
            assertEquals(previous.planetStocks!!.bases,fresh.planetStocks!!.bases)
            assertEquals(previous.planetStocks!!.outfits,fresh.planetStocks!!.outfits)
            assertEquals(previous.planetStocks!!.warehouses,fresh.planetStocks!!.warehouses)
            assertNull(fresh.session!!.ended)
        }
    }

}
