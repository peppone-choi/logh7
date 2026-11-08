package org.logh7.gateway

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.ChatMessages
import org.logh7.protocol.TacticalStopMessages
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class TacticalGroundWireTest {
    @TempDir lateinit var directory:Path
    @Test fun encryptedDeployResumesPrivatelyAndWithdrawReturnsTheSameIds()=encounter()
    @Test fun overCapacityLandingCancellationReachesBothPeersAndNotifiesOnlyTheCommander()=encounter(cancelSaved=true)
    private fun encounter(cancelSaved:Boolean=false)=runBlocking {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,0f,86400,0,0f,2f),
                GalaxyBase(1001,2487,80,0,"Enemy",0,0f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val now=SessionRules.load().startSeconds
        fun motion()=TacticalMotion(2487,now,TacticalPose(TacticalPoint(0f,0f),0f),0f,0f,listOf(TacticalPoint(0f,0f)))
        var savedPlayers=(0..1).associate {i->"load-00$i" to Navigator(1,10000+i,Power.EMPIRE,2487,1000,1000,900,
            cpPaused=true,hull=FlagshipHull(0,now),motion=motion())}
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32),tactical=NpcTacticalState(motion(),now,now),
            posture=TacticalPosture(2487,TacticalShipMode.ANCHORED,1000))))
        val aboard=listOf(ActiveGroundUnit(2,UnitStack(0),carrier=1),ActiveGroundUnit(3,UnitStack(1),carrier=1))
        val stationed=if(cancelSaved) List(GroundBattleRules.BOX_CAPACITY_PER_FACTION-1) {
            ActiveGroundUnit(4+it,UnitStack(0),base=1000)
        } else emptyList()
        val troops=ActiveGroundUnits(aboard+stationed)
        val outfit=OutfitStock(1,2,OutfitKind.FLEET,1000,10,10000,UnitInventory(ships.quantities(),troops.quantities()),
            activeShips=ships,activeGround=troops,position=OutfitPosition(2487))
        var savedStocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0),1001 to PlanetStock(1001,3,0,0,0,0)),mapOf(1 to outfit),
            mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),nextShipId=4+stationed.size)
        if(cancelSaved) {
            val pending=TacticalGroundOrder(TacticalGroundAction.DEPLOY,10000,2487,1,1000,now,now+600)
            savedStocks=savedStocks.copy(outfits=mapOf(1 to outfit.copy(activeGround=ActiveGroundUnits(
                aboard.map {it.copy(order=pending)}+stationed))))
        }
        val nav=object:NavigationStore {override fun load()=savedPlayers;override fun save(players:Map<String,Navigator>){savedPlayers=players}}
        val stock=object:PlanetStockStore {override fun load()=savedStocks;override fun save(stocks:PlanetStocks){savedStocks=stocks}}
        val commit=WorldStateCommitter {p,s->savedPlayers=p.toMap();savedStocks=checkNotNull(s)}
        fun through(c:SyntheticClient,op:Int,accept:(ByteArray)->Boolean={true}):ByteArray {
            repeat(500) {val p=c.receive();if(ByteBuffer.wrap(p).getShort(4).toInt()==op && accept(p))return p}
            error("Missing $op")
        }
        fun notice(c:SyntheticClient)=ChatMessages.spot(through(c,0x0f1d).drop(4).toByteArray())
        fun noticesBeforeClock(c: SyntheticClient): List<ChatMessages.Spot> = buildList {
            c.send(SyntheticClient.opcode(0x0300))
            repeat(500) {
                val message = c.receive()
                when (ByteBuffer.wrap(message).getShort(4).toInt()) {
                    0x0301 -> return@buildList
                    0x0f1d -> add(ChatMessages.spot(message.drop(4).toByteArray()))
                }
            }
            error("Missing clock reply")
        }
        fun command(op:Int,char:Int)=ByteBuffer.allocate(if(op==0x040f)20 else 19).putShort(op.toShort()).putLong(-1)
            .putInt(char).put(1).putInt(1).apply {if(op==0x040f)put(0)}.array()
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=nav,stockStore=stock,
            worldStateCommitter=commit,powerByIndex={2}).use {fixture ->
            fun join(index:Int,create:Boolean)=fixture.join(fixture.account(index),create,false).client.also {c->
                c.send(SyntheticClient.opcode(0x0f00));through(c,0x0f01);c.send(SyntheticClient.opcode(0x0f02));through(c,0x0f03)}
            val clients=mutableListOf(join(0,true),join(1,true))
            if(cancelSaved) {
                assertEquals(aboard.size,fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).activeGround!!.values.count {it.order!=null})
                fixture.engine.submit(WorldCommand.AdvanceTo(25000))
                withTimeout(5000){while(fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).activeGround!!.values.any {it.order!=null})delay(1)}
                for(client in clients) {
                    val packet=ByteBuffer.wrap(through(client,0x040f) {ByteBuffer.wrap(it).int==1})
                    assertEquals(1,packet.getInt(0))
                    assertEquals(0,packet.getInt(10))
                    assertEquals(10000,packet.getInt(14))
                }
                assertEquals(ChatMessages.Spot(10000,"[육전] 조건 변경으로 취소"),notice(clients[0]))
                assertTrue(noticesBeforeClock(clients[1]).isEmpty())
                assertEquals(troops,fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).activeGround)
                return@runBlocking
            }
            clients[1].send(command(0x040f,10001));assertEquals(1,ByteBuffer.wrap(through(clients[1],0x040f)).int)
            assertEquals(ChatMessages.Spot(10001,"[육전] 이 함정의 지휘 권한이 없습니다."),notice(clients[1]))
            clients[0].send(command(0x040f,10000));val ack=ByteBuffer.wrap(through(clients[0],0x040f))
            assertEquals(0,ack.int);assertEquals(600,ack.getInt(10))
            assertEquals(ChatMessages.Spot(10000,"[육전] 접수 · 약 25초 · CP 0"),notice(clients[0]))
            assertTrue(fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).activeGround!!.values.all {it.carrier==1 && it.order!=null})
            fixture.engine.submit(WorldCommand.AdvanceTo(10001))
            withTimeout(5000) { while (fixture.engine.snapshot().session!!.gameSeconds < now + 240) delay(1) }
            clients[1].close();fixture.awaitState(online=1,connections=1,participants=2)
            clients[1]=join(1,false);assertEquals(360,ByteBuffer.wrap(through(clients[1],0x040f)).getInt(10))
            assertTrue(noticesBeforeClock(clients[1]).isEmpty())
            assertTrue(noticesBeforeClock(clients[1]).isEmpty())
            clients[0].close();fixture.awaitState(online=1,connections=1,participants=2)
            clients[0]=join(0,false);assertEquals(360,ByteBuffer.wrap(through(clients[0],0x040f)).getInt(10))
            assertTrue(noticesBeforeClock(clients[0]).isEmpty())
            assertEquals(listOf(ChatMessages.Spot(10000,"[육전] 진행 중 · 약 15초 남음")),noticesBeforeClock(clients[0]))
            assertTrue(noticesBeforeClock(clients[0]).isEmpty())
            fixture.engine.submit(WorldCommand.AdvanceTo(25000))
            withTimeout(5000){while(fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).activeGround!!.values.any {it.order!=null})delay(1)}
            for(c in clients) {
                val base=ByteBuffer.wrap(through(c,0x0345));assertEquals(2000,base.getShort(33).toInt())
                assertEquals(2000,ByteBuffer.wrap(through(c,0x042a)).getShort(14).toInt())
                val moved=(0..1).map {ByteBuffer.wrap(through(c,0x0429))}
                if(c==clients[0])assertEquals(ChatMessages.Spot(10000,"[육전] 2유닛 상륙 완료"),notice(c))
                val packet=ByteBuffer.wrap(through(c,0x0325));assertEquals(5,packet.getShort(6).toInt())
                assertEquals(setOf(2,3),moved.map {it.getInt(15)}.toSet());assertTrue(moved.all {it.getInt(11)==1 && it.getInt(19)==1000 && it.get(10).toInt()==0})
            }
            assertTrue(savedStocks.outfits.getValue(1).activeGround!!.values.all {it.origin==1 && it.base==1000 && it.carrier==0})
            clients[0].send(command(0x0410,10000));assertEquals(0,ByteBuffer.wrap(through(clients[0],0x0410)).int)
            assertEquals(ChatMessages.Spot(10000,"[철수] 접수 · 약 25초 · CP 0"),notice(clients[0]))
            clients[0].send(TacticalStopMessages.reply(TacticalStopMessages.Stop(10000,listOf(1)),0).drop(4).toByteArray())
            assertEquals(0,ByteBuffer.wrap(through(clients[0],0x040a)).int)
            assertEquals(ChatMessages.Spot(10000,"[정지] 철수 취소"),notice(clients[0]))
            assertTrue(savedStocks.outfits.getValue(1).activeGround!!.values.all {it.base==1000 && it.order==null})
            clients[0].send(command(0x0410,10000));assertEquals(0,ByteBuffer.wrap(through(clients[0],0x0410)).int)
            assertEquals(ChatMessages.Spot(10000,"[철수] 접수 · 약 25초 · CP 0"),notice(clients[0]))
            fixture.engine.submit(WorldCommand.AdvanceTo(50000))
            withTimeout(5000){while(fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).activeGround!!.values.any {it.order!=null})delay(1)}
            for(c in clients) {
                assertEquals(0,ByteBuffer.wrap(through(c,0x0345)).getShort(33).toInt())
                assertEquals(0,ByteBuffer.wrap(through(c,0x042a)).getShort(14).toInt())
                repeat(2){assertEquals(1,ByteBuffer.wrap(through(c,0x0429)).get(10).toInt())}
                if(c==clients[0])assertEquals(ChatMessages.Spot(10000,"[철수] 2유닛 재탑승 완료"),notice(c))
                through(c,0x0325)
                c.close()
            }
            assertTrue(savedStocks.outfits.getValue(1).activeGround!!.values.all {it.carrier==1 && it.origin==0 && it.base==0 && it.order==null})
        }
    }
}
