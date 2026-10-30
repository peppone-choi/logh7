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
    @Test fun authenticatedSortieAcknowledgesAndCompletesAsANativeOneUnitNotification() = runBlocking<Unit> {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Battle",3)),
            listOf(GalaxyBase(1000,2487,80,0,"Base",0,0f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val now=SessionRules.load().startSeconds
        var saved=(0..1).associate {i->"load-00$i" to Navigator(1,10000+i,if(i==0)Power.EMPIRE else Power.ALLIANCE,
            2487,1000,1000,900,cpPaused=true,hull=FlagshipHull(if(i==0)0 else 89,now))}
        val motion=TacticalMotion(2487,now,TacticalPose(TacticalPoint(0f,0f),0f),0f,0f,listOf(TacticalPoint(0f,0f)))
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32),tactical=NpcTacticalState(motion,now,now),
            posture=TacticalPosture(2487,TacticalShipMode.STATIONED,1000))))
        val outfit=OutfitStock(1,2,OutfitKind.FLEET,1000,10,10000,UnitInventory(ships=ships.quantities()),
            activeShips=ships,position=OutfitPosition(2487))
        var inventory=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0)),mapOf(1 to outfit),
            mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),nextShipId=2)
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
            val command=TacticalGroundMessages.Command(TacticalGroundMessages.Action.SORTIE,10000,listOf(1))
            clients[0].send(ByteBuffer.allocate(11).putShort(0x344).put(2).putInt(1000).putInt(999).array())
            val bases=ByteBuffer.wrap(through(clients[0],0x0345))
            assertEquals(1,bases.get(6).toInt());assertEquals(1000,bases.getInt(7));assertEquals(35,bases.capacity())
            clients[0].send(TacticalGroundMessages.reply(command,0,0).drop(4).toByteArray())
            val acknowledgement=ByteBuffer.wrap(through(clients[0],0x0412))
            assertEquals(0,acknowledgement.int);assertEquals(25*24,acknowledgement.getInt(10))
            assertNotNull(inventory.outfits.getValue(1).activeShips!!.values.single().posture!!.order)
            fixture.engine.submit(WorldCommand.AdvanceTo(25000))
            for(c in clients) {
                val packet=ByteBuffer.wrap(through(c,0x0437))
                assertEquals(34,packet.capacity());assertEquals(1000,packet.getInt(10));assertEquals(1,packet.getInt(14))
            }
            assertEquals(TacticalPosture(2487,TacticalShipMode.ANCHORED,1000),inventory.outfits.getValue(1).activeShips!!.values.single().posture)
            clients[0].send(TacticalGroundMessages.reply(command,0,0).drop(4).toByteArray())
            assertEquals(1,ByteBuffer.wrap(through(clients[0],0x0412)).int)
        }
    }
    @Test fun authenticatedCommandWaitsAndPublishesTheCommittedPostureToBothConnections() = runBlocking<Unit> {
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
        received.clear();val sortie=event.copy(mode=TacticalShipMode.ANCHORED,base=1000,sortie=true)
        hub.posture(sortie)
        assertEquals(listOf(0x0400,0x0437),received.single().map {ByteBuffer.wrap(it).getShort(4).toInt()})
        received.clear();hub.posture(sortie.copy(cancelled=true))
        assertEquals(0x0412,ByteBuffer.wrap(received.single().single()).getShort(4).toInt())
    }
}
