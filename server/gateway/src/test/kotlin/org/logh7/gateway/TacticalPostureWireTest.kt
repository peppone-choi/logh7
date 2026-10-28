package org.logh7.gateway

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class TacticalPostureWireTest {
    @TempDir lateinit var directory:Path
    @Test fun authenticatedCommandWaitsAndPublishesTheCommittedPostureToBothConnections() = runBlocking {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Battle",3)),
            listOf(GalaxyBase(1000,2487,80,0,"Enemy",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val now=SessionRules.load().startSeconds
        var saved=(0..1).associate {i->"load-00$i" to Navigator(1,10000+i,if(i==0)Power.EMPIRE else Power.ALLIANCE,
            2487,1000,1000,900,cpPaused=true,hull=FlagshipHull(if(i==0)0 else 89,now))}
        var inventory=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,3,0,0,0,0)))
        val nav=object:NavigationStore {override fun load()=saved;override fun save(players:Map<String,Navigator>){saved=players}}
        val stock=object:PlanetStockStore {override fun load()=inventory;override fun save(stocks:PlanetStocks){inventory=stocks}}
        val commit=WorldStateCommitter {p,s->saved=p.toMap();inventory=checkNotNull(s)}
        fun through(c:SyntheticClient,opcode:Int):ByteArray {
            repeat(400){val packet=c.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode)return packet}
            error("Missing opcode $opcode")
        }
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=nav,stockStore=stock,
            worldStateCommitter=commit,powerByIndex={if(it==0)2 else 3}).use {fixture->
            val clients=(0..1).map {i->fixture.join(fixture.account(i),true,false).client.also {c->
                c.send(SyntheticClient.opcode(0x0f00));through(c,0x0f01)
                c.send(SyntheticClient.opcode(0x0f02));through(c,0x0f03)
            }}
            val command=TacticalGroundMessages.Posture(10000,listOf(1010000),7,0)
            clients[0].send(TacticalGroundMessages.reply(command,0,0).drop(4).toByteArray())
            val acknowledgement=ByteBuffer.wrap(through(clients[0],0x0411))
            assertEquals(0,acknowledgement.int);assertEquals(240,acknowledgement.getInt(10))
            assertEquals(TacticalShipMode.SAILING,saved.getValue("load-000").posture!!.mode)
            fixture.engine.submit(WorldCommand.AdvanceTo(10000))
            withTimeout(5000){while(fixture.engine.snapshot().session!!.gameSeconds<now+240)delay(1)}
            for(c in clients) {
                val packet=ByteBuffer.wrap(through(c,0x042f))
                assertEquals(now+240,packet.getInt(6).toLong());assertEquals(7,packet.get(10).toInt())
                assertEquals(0,packet.getInt(11));assertEquals(1,packet.get(15).toInt());assertEquals(1010000,packet.getInt(16))
            }
            assertEquals(TacticalPosture(2487,TacticalShipMode.BATTLE),saved.getValue("load-000").posture)
            assertNull(saved.getValue("load-000").posture!!.order)
            // Unsupported semantic values get a rejected ACK while the authenticated connection remains usable.
            clients[0].send(TacticalGroundMessages.reply(command.copy(kind=0),0,0).drop(4).toByteArray())
            assertEquals(1,ByteBuffer.wrap(through(clients[0],0x0411)).int)
            clients[0].send(SyntheticClient.opcode(0x0f00));through(clients[0],0x0f01)
        }
    }
    @Test fun postureDeliveryRejectsOldGenerationGridAndConnectionAndFreezesBeforeApplyingTheMode() {
        val hub=GamePresence();val received=mutableListOf<List<ByteArray>>()
        val c=CharacterMessages.Generate(4,10000,2,0,0,"Pilot","One",30,1,1,1000001,List(8){50},0,0,0,20,0,0,"Ship",1)
        val p=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,900,cpPaused=true)
        hub.update(GamePresence.Member("current",c,p,received::add));received.clear()
        val event=TacticalPostureResult(1,2487,100,10000,TacticalShipMode.BATTLE,0,
            mapOf(1010000 to TacticalPose(TacticalPoint(1f,2f),0.3f)),mapOf(10000 to "current"))
        for(stale in listOf(event.copy(generation=2),event.copy(grid=2488),event.copy(recipients=mapOf(10000 to "old"))))hub.posture(stale)
        assertTrue(received.isEmpty());hub.posture(event)
        assertEquals(listOf(0x0400,0x042f),received.single().map {ByteBuffer.wrap(it).getShort(4).toInt()})
        received.clear();hub.posture(event.copy(cancelled=true))
        val rejected=ByteBuffer.wrap(received.single().single());assertEquals(0x0411,rejected.getShort(4).toInt());assertEquals(1,rejected.int)
    }
}
