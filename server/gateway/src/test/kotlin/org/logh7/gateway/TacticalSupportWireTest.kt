package org.logh7.gateway

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class TacticalSupportWireTest {
    @TempDir lateinit var directory:Path
    @Test fun supplyCommanderRequestUpdatesBothEncryptedPeersAndRejectsAnotherCommander()=encounter(false)
    @Test fun repairCommanderRequestConsumesSuppliesAndUpdatesBothEncryptedPeers()=encounter(true)
    private fun encounter(repair:Boolean)=runBlocking {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Battle",3)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val now=SessionRules.load().startSeconds
        fun motion(y:Float)=TacticalMotion(2488,now,TacticalPose(TacticalPoint(0f,y),0f),0f,0f,listOf(TacticalPoint(0f,y)))
        var savedPlayers=(0..1).associate {i->"load-00$i" to Navigator(1,10000+i,Power.EMPIRE,2488,1000,1000,900,
            cpPaused=true,supplies=if(i==0)400 else 1000,hull=FlagshipHull(0,now,hp=if(i==0)40 else 100),motion=motion(0.5f-i*0.25f))}
        val kind=ProductionUnits.definitions.first {it.ship && it.power==2 && it.name.startsWith(if(repair)"工作艦" else "輸送艦")}.kind
        val stack=UnitStack(kind);val enemy=UnitStack(119)
        var savedStocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100)),
            outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,commanderId=10000,
                units=UnitInventory(mapOf(stack to 1)),supplies=10000,
                activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack,supplies=10000,
                    tactical=NpcTacticalState(motion(0f),now,now)))),position=OutfitPosition(2488)),
                2 to OutfitStock(2,3,OutfitKind.FLEET,1000,20,units=UnitInventory(mapOf(enemy to 1)),
                    activeShips=ActiveShipUnits(listOf(ActiveShipUnit(99,enemy))),position=OutfitPosition(2488))),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1),20 to OutfitWarehouse(20,3,OutfitKind.FLEET,1000,2)),
            deploymentInitialized=true,nextShipId=100)
        val nav=object:NavigationStore {override fun load()=savedPlayers;override fun save(players:Map<String,Navigator>){savedPlayers=players}}
        val stock=object:PlanetStockStore {override fun load()=savedStocks;override fun save(stocks:PlanetStocks){savedStocks=stocks}}
        val commit=WorldStateCommitter {p,s->savedPlayers=p.toMap();savedStocks=checkNotNull(s)}
        fun through(client:SyntheticClient,opcode:Int,predicate:(ByteArray)->Boolean={true}):ByteArray {
            repeat(500){val packet=client.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode && predicate(packet))return packet}
            error("Missing $opcode")
        }
        val opcode=if(repair)0x0413 else 0x0414
        val target=if(repair)1010000 else 1010001
        fun command(character:Int)=ByteBuffer.allocate(22).putShort(opcode.toShort()).putLong(-1)
            .putInt(character).putInt(1).putInt(target).array()
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=nav,stockStore=stock,
            worldStateCommitter=commit,powerByIndex={2}).use {fixture ->
            val clients=(0..1).map {i->fixture.join(fixture.account(i),true,false).client.also {c ->
                c.send(SyntheticClient.opcode(0x0f00));through(c,0x0f01)
                c.send(SyntheticClient.opcode(0x0f02));through(c,0x0f03)
            }}.toMutableList()
            clients[1].send(command(10001))
            assertEquals(1,ByteBuffer.wrap(through(clients[1],opcode)).int)
            clients[0].send(command(10000))
            val ack=ByteBuffer.wrap(through(clients[0],opcode))
            assertEquals(0,ack.int);assertEquals(120,ack.getInt(10))
            assertEquals(10000,fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).supplies)
            clients[1].close()
            fixture.awaitState(online=1,connections=1,participants=2)
            clients[1]=fixture.join(fixture.account(1),false,false).client.also {c ->
                c.send(SyntheticClient.opcode(0x0f00));through(c,0x0f01)
                c.send(SyntheticClient.opcode(0x0f02));through(c,0x0f03)
                val resumed=ByteBuffer.wrap(through(c,opcode))
                assertEquals(10000,resumed.getInt(14));assertEquals(1,resumed.getInt(18))
                assertEquals(target,resumed.getInt(22));assertEquals(120,resumed.getInt(10))
            }
            fixture.engine.submit(WorldCommand.AdvanceTo(5000))
            withTimeout(5000){while(fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).activeShips!!.values.single().support!=null)delay(1)}
            val remaining=if(repair)9860 else 1000
            for(c in clients) {
                val result=ByteBuffer.wrap(through(c,if(repair)0x042d else 0x042e))
                assertEquals(1,result.getInt(6));assertEquals(target,result.getInt(10));assertEquals(remaining,result.getInt(14))
                if(repair)assertEquals(0,result.getShort(18).toInt()) else assertEquals(10000,result.getInt(18))
                val definitions=ByteBuffer.wrap(through(c,0x0325))
                assertEquals(4,definitions.getShort(6).toInt())
                val offsets=(0 until 4).associate {i->val offset=8+i*42;definitions.getInt(offset) to offset}
                assertEquals(setOf(1,99,1010000,1010001),offsets.keys)
                assertEquals(remaining,definitions.getInt(offsets.getValue(1)+30))
                assertEquals(if(repair)400 else 10000,definitions.getInt(offsets.getValue(target)+30))
                assertEquals(0,definitions.getShort(offsets.getValue(target)+26).toInt())
            }
            val snapshot=fixture.engine.snapshot()
            assertEquals(remaining,snapshot.planetStocks!!.outfits.getValue(1).supplies)
            if(repair)assertEquals(100,snapshot.navigators.getValue("load-000").hull!!.hp)
            else assertEquals(10000,snapshot.navigators.getValue("load-001").supplies)
        }
    }
}
