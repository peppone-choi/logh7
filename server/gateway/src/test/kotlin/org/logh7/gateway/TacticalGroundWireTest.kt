package org.logh7.gateway

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class TacticalGroundWireTest {
    @TempDir lateinit var directory:Path
    @Test fun encryptedDeployWaitsSurvivesPeerReconnectAndWithdrawReturnsTheSameIds()=runBlocking {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,0f,86400,0,0f,2f),
                GalaxyBase(1001,2487,80,0,"Enemy",0,0f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val now=SessionRules.load().startSeconds
        fun motion()=TacticalMotion(2487,now,TacticalPose(TacticalPoint(0f,0f),0f),0f,0f,listOf(TacticalPoint(0f,0f)))
        var savedPlayers=(0..1).associate {i->"load-00$i" to Navigator(1,10000+i,Power.EMPIRE,2487,1000,1000,900,
            cpPaused=true,hull=FlagshipHull(0,now),motion=motion())}
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32),tactical=NpcTacticalState(motion(),now,now),
            posture=TacticalPosture(2487,TacticalShipMode.ANCHORED,1000))))
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(2,UnitStack(0),carrier=1),ActiveGroundUnit(3,UnitStack(1),carrier=1)))
        val outfit=OutfitStock(1,2,OutfitKind.FLEET,1000,10,10000,UnitInventory(ships.quantities(),troops.quantities()),
            activeShips=ships,activeGround=troops,position=OutfitPosition(2487))
        var savedStocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0),1001 to PlanetStock(1001,3,0,0,0,0)),mapOf(1 to outfit),
            mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),nextShipId=4)
        val nav=object:NavigationStore {override fun load()=savedPlayers;override fun save(players:Map<String,Navigator>){savedPlayers=players}}
        val stock=object:PlanetStockStore {override fun load()=savedStocks;override fun save(stocks:PlanetStocks){savedStocks=stocks}}
        val commit=WorldStateCommitter {p,s->savedPlayers=p.toMap();savedStocks=checkNotNull(s)}
        fun through(c:SyntheticClient,op:Int):ByteArray {repeat(500){val p=c.receive();if(ByteBuffer.wrap(p).getShort(4).toInt()==op)return p};error("Missing $op")}
        fun command(op:Int,char:Int)=ByteBuffer.allocate(if(op==0x040f)20 else 19).putShort(op.toShort()).putLong(-1)
            .putInt(char).put(1).putInt(1).apply {if(op==0x040f)put(0)}.array()
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=nav,stockStore=stock,
            worldStateCommitter=commit,powerByIndex={2}).use {fixture ->
            fun join(index:Int,create:Boolean)=fixture.join(fixture.account(index),create,false).client.also {c->
                c.send(SyntheticClient.opcode(0x0f00));through(c,0x0f01);c.send(SyntheticClient.opcode(0x0f02));through(c,0x0f03)}
            val clients=mutableListOf(join(0,true),join(1,true))
            clients[1].send(command(0x040f,10001));assertEquals(1,ByteBuffer.wrap(through(clients[1],0x040f)).int)
            clients[0].send(command(0x040f,10000));val ack=ByteBuffer.wrap(through(clients[0],0x040f))
            assertEquals(0,ack.int);assertEquals(600,ack.getInt(10))
            assertTrue(fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).activeGround!!.values.all {it.carrier==1 && it.order!=null})
            clients[1].close();fixture.awaitState(online=1,connections=1,participants=2)
            clients[1]=join(1,false);assertEquals(600,ByteBuffer.wrap(through(clients[1],0x040f)).getInt(10))
            fixture.engine.submit(WorldCommand.AdvanceTo(25000))
            withTimeout(5000){while(fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).activeGround!!.values.any {it.order!=null})delay(1)}
            for(c in clients) {
                val base=ByteBuffer.wrap(through(c,0x0345));assertEquals(2000,base.getShort(33).toInt())
                assertEquals(2000,ByteBuffer.wrap(through(c,0x042a)).getShort(14).toInt())
                val moved=(0..1).map {ByteBuffer.wrap(through(c,0x0429))}
                val packet=ByteBuffer.wrap(through(c,0x0325));assertEquals(5,packet.getShort(6).toInt())
                assertEquals(setOf(2,3),moved.map {it.getInt(15)}.toSet());assertTrue(moved.all {it.getInt(11)==1 && it.getInt(19)==1000 && it.get(10).toInt()==0})
            }
            assertTrue(savedStocks.outfits.getValue(1).activeGround!!.values.all {it.origin==1 && it.base==1000 && it.carrier==0})
            clients[0].send(command(0x0410,10000));assertEquals(0,ByteBuffer.wrap(through(clients[0],0x0410)).int)
            fixture.engine.submit(WorldCommand.AdvanceTo(50000))
            withTimeout(5000){while(fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).activeGround!!.values.any {it.order!=null})delay(1)}
            for(c in clients) {
                assertEquals(0,ByteBuffer.wrap(through(c,0x0345)).getShort(33).toInt())
                assertEquals(0,ByteBuffer.wrap(through(c,0x042a)).getShort(14).toInt())
                repeat(2){assertEquals(1,ByteBuffer.wrap(through(c,0x0429)).get(10).toInt())}
                through(c,0x0325)
                c.close()
            }
            assertTrue(savedStocks.outfits.getValue(1).activeGround!!.values.all {it.carrier==1 && it.origin==0 && it.base==0 && it.order==null})
        }
    }
}
