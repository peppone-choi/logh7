package org.logh7.gateway

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class TacticalFleetAttackWireTest {
    @TempDir lateinit var directory:Path
    @Test fun fleetAttackUsesAuthorityAndBroadcastsDamageToBothPeers()=runBlocking {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Battle",3)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val now=SessionRules.load().startSeconds
        fun motion(y:Float)=TacticalMotion(2488,now,TacticalPose(TacticalPoint(0f,y),0f),0f,0f,listOf(TacticalPoint(0f,y)))
        var savedPlayers=(0..1).associate {i->"load-00$i" to Navigator(1,10000+i,Power.EMPIRE,2488,1000,1000,900,
            cpPaused=true,supplies=if(i==0)400 else 1000,hull=FlagshipHull(0,now,hp=100),motion=motion(0.5f-i*0.25f))}
        val kind=32
        val stack=UnitStack(kind);val enemy=UnitStack(119)
        var savedStocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100)),
            outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,commanderId=10000,
                units=UnitInventory(mapOf(stack to 1)),supplies=10000,
                activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack,supplies=10000,
                    tactical=NpcTacticalState(motion(0f),now,now)))),position=OutfitPosition(2488)),
                2 to OutfitStock(2,3,OutfitKind.FLEET,1000,20,units=UnitInventory(mapOf(enemy to 1)),
                    activeShips=ActiveShipUnits(listOf(ActiveShipUnit(99,enemy,tactical=NpcTacticalState(motion(2f),now,now)))),position=OutfitPosition(2488))),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1),20 to OutfitWarehouse(20,3,OutfitKind.FLEET,1000,2)),
            deploymentInitialized=true,nextShipId=100)
        val nav=object:NavigationStore {override fun load()=savedPlayers;override fun save(players:Map<String,Navigator>){savedPlayers=players}}
        val stock=object:PlanetStockStore {override fun load()=savedStocks;override fun save(stocks:PlanetStocks){savedStocks=stocks}}
        val commit=WorldStateCommitter {p,s->savedPlayers=p.toMap();savedStocks=checkNotNull(s)}
        fun through(client:SyntheticClient,opcode:Int,predicate:(ByteArray)->Boolean={true}):ByteArray {
            repeat(500){val packet=client.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode && predicate(packet))return packet}
            error("Missing $opcode")
        }
        fun command(character:Int,mode:Int)=org.logh7.protocol.TacticalAttackMessages.reply(
            org.logh7.protocol.TacticalAttackMessages.Attack(character,listOf(1),mode,0),0,0).let {it.copyOfRange(4,it.size)}
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=nav,stockStore=stock,
            worldStateCommitter=commit,powerByIndex={2}).use {fixture ->
            val clients=(0..1).map {i->fixture.join(fixture.account(i),true,false).client.also {c ->
                c.send(SyntheticClient.opcode(0x0f00));through(c,0x0f01)
                c.send(SyntheticClient.opcode(0x0f02));through(c,0x0f03)
            }}.toMutableList()
            clients[1].send(command(10001,2));assertEquals(1,ByteBuffer.wrap(through(clients[1],0x0405)).int)
            clients[0].send(command(10000,2))
            val accepted=ByteBuffer.wrap(through(clients[0],0x0405))
            assertEquals(0,accepted.int);assertEquals(120,accepted.getInt(10))
            assertNotNull(savedStocks.outfits.getValue(1).activeShips!!.values.single().attack)
            fixture.engine.submit(WorldCommand.AdvanceTo(5000))
            for(c in clients) {
                val hit=ByteBuffer.wrap(through(c,0x0426))
                assertEquals(1,hit.getInt(10));assertEquals(99,hit.getInt(16));assertTrue(hit.getShort(22)>0)
                val cargo=ByteBuffer.wrap(through(c,0x0325) {ByteBuffer.wrap(it).getInt(8)==1 && ByteBuffer.wrap(it).getInt(38)<10000})
                assertEquals(4,cargo.getShort(6).toInt())
                assertEquals(OutfitSupplies.distribution(savedStocks.outfits.getValue(1)).getValue(1),cargo.getInt(38))
            }
            val reloading=savedStocks.outfits.getValue(1).activeShips!!.values.single().reload
            assertEquals(TacticalReload(now+360,now+480,now+600),reloading)
            clients[1].close();fixture.awaitState(online=1,connections=1,participants=2)
            clients[1]=fixture.join(fixture.account(1),false,false).client.also {c ->
                c.send(SyntheticClient.opcode(0x0f00));through(c,0x0f01)
                c.send(SyntheticClient.opcode(0x0f02));through(c,0x0f03)
                val resume=ByteBuffer.wrap(through(c,0x0405))
                assertEquals(10000,resume.getInt(14));assertEquals(1,resume.getInt(19))
            }
            assertEquals(reloading,savedStocks.outfits.getValue(1).activeShips!!.values.single().reload)
            fixture.engine.submit(WorldCommand.AdvanceTo(14000))
            clients[0].send(command(10000,0));assertEquals(0,ByteBuffer.wrap(through(clients[0],0x0405)).int)
            assertNull(savedStocks.outfits.getValue(1).activeShips!!.values.single().attack)
            assertEquals(reloading,savedStocks.outfits.getValue(1).activeShips!!.values.single().reload)
            val stopped=savedStocks
            fixture.engine.submit(WorldCommand.AdvanceTo(20000))
            clients[0].send(SyntheticClient.opcode(0x0300));through(clients[0],0x0301)
            assertEquals(stopped.outfits,savedStocks.outfits)
        }
    }
}
