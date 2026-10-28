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

class TacticalFireWireTest {
    @TempDir lateinit var directory:Path
    @Test fun twoEncryptedClientsReceiveOneAuthoritativeBeamLossAfterDelayAndWorldCommit() = runBlocking {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Battle",3)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val now=SessionRules.load().startSeconds
        val point=TacticalPoint(-180f,-97f)
        var savedPlayers=(0..1).associate {index->"load-00$index" to Navigator(1,10000+index,Power.EMPIRE,2488,1000,960,900,
            cpPaused=true,energy=TacticalEnergy(List(11){if(it==3)100 else 0}),charge=TacticalCharge(now,24000),
            motion=TacticalMotion(2488,now,TacticalPose(point,0f),0f,0f,listOf(point)))}
        val stack=UnitStack(119)
        var inventory=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100)),
            outfits=mapOf(1 to OutfitStock(1,3,OutfitKind.FLEET,1000,10,units=UnitInventory(mapOf(stack to 1)),
                activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack))),position=OutfitPosition(2488))),
            warehouses=mapOf(10 to OutfitWarehouse(10,3,OutfitKind.FLEET,1000,1)),deploymentInitialized=true,nextShipId=2)
        val navStore=object:NavigationStore {
            override fun load()=savedPlayers
            override fun save(players:Map<String,Navigator>){savedPlayers=players.toMap()}
        }
        val stockStore=object:PlanetStockStore {
            override fun load()=inventory
            override fun save(stocks:PlanetStocks){inventory=stocks}
        }
        val commit=WorldStateCommitter {players,stocks->savedPlayers=players.toMap();inventory=checkNotNull(stocks)}
        fun through(client:SyntheticClient,opcode:Int):ByteArray {
            repeat(300){val packet=client.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode)return packet}
            error("Missing $opcode")
        }
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,stockStore=stockStore,navigationStore=navStore,
            worldStateCommitter=commit).use {fixture->
            val clients=(0..1).map {index->fixture.join(fixture.account(index),true,false).client.also {client->
                client.send(SyntheticClient.opcode(0x0f00));through(client,0x0f01)
                client.send(SyntheticClient.opcode(0x0f02));through(client,0x0f03)
            }}
            val shoot=TacticalAttackMessages.Shoot(10000,listOf(1010000),0,1,1)
            val request=TacticalAttackMessages.reply(shoot,0,0).drop(4).toByteArray()
            clients[0].send(request)
            val ack=ByteBuffer.wrap(through(clients[0],0x0406))
            assertEquals(0,ack.int);assertEquals(120,ack.getInt(10))
            assertEquals(300,inventory.outfits.getValue(1).activeShips!!.values.single().boats)
            assertNotNull(savedPlayers.getValue("load-000").fire!!.pending)
            fixture.engine.submit(WorldCommand.AdvanceTo(5000))
            withTimeout(5000){while(fixture.engine.snapshot().session!!.gameSeconds<now+120)delay(1)}
            val fire=fixture.engine.snapshot().navigators.getValue("load-000").fire!!
            val losses=fire.last!!.destroyed
            assertTrue(losses>0);assertNull(fire.pending)
            assertEquals(300-losses,inventory.outfits.getValue(1).activeShips!!.values.single().boats)
            for(client in clients) {
                val hit=through(client,0x0426)
                assertEquals(1010000,ByteBuffer.wrap(hit).getInt(10))
                assertEquals(1,ByteBuffer.wrap(hit).getInt(16))
                assertEquals(0,ByteBuffer.wrap(hit).getShort(20).toInt())
                assertEquals(losses,ByteBuffer.wrap(hit).getShort(22).toInt())
            }
            assertEquals(0,savedPlayers.getValue("load-000").charge!!.beam)
            clients[0].send(request)
            assertEquals(1,ByteBuffer.wrap(through(clients[0],0x0406)).int)
            clients[0].send(SyntheticClient.opcode(0x0f02))
            var restored=false
            for(index in 0 until 300) {
                val input=ByteBuffer.wrap(clients[0].receive())
                val opcode=input.getShort(4).toInt()
                if(opcode==0x0325 && input.getShort(6).toInt()==3 && input.getInt(8)==1) {
                    assertEquals(0,input.getShort(34).toInt())
                    assertEquals(losses,input.getShort(36).toInt())
                    restored=true
                }
                if(opcode==0x0f03)break
            }
            assertTrue(restored)
        }
    }
}
