package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import kotlin.test.*

/** 암호화 회견: 같은 스폿 인물 목록과 우호도 저장·쌍별 제한. 작성: Peppone Choi. */
class MeetingActionsWireTest {
    @TempDir lateinit var directory:Path
    private val now=SessionRules.load().startSeconds
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2015,4,"Other",3)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val square=galaxy.facilities.getValue(1049).single {it.kind==2}.spots.single().id
    private inner class State:WorldStateCommitter {
        var players=(0..2).associate {i->"load-%03d".format(i) to Navigator(1,10000+i,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=1,spotId=if(i==2)square+1 else square,cpRecoveryAt=now)}
        var stocks=PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,if(it.id==1049)2 else 3,0,
            2000,200,300,PlanetEconomy(1000,600,70,80),treasury=200)},deploymentInitialized=true)
        val navigation=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?){players=navigation;stocks=checkNotNull(inventory)}
    }
    private fun meeting(target:Int,phase:Int=0,ticket:Int=0,token:Int=37)=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000)
        .putShort(0x7f12).putShort(87).putShort(0).putShort(0).putInt(ticket).put(phase.toByte()).putInt(0).putShort(0).putInt(target)
        .putShort(token.toShort()).array()
    private fun directory()=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(0x7f00).putShort(87).putShort(0).putShort(0)
        .putInt(0).put(1).putInt(0).putShort(0).putInt(0).putShort(36).array()
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(200){val b=client.receive();if(ByteBuffer.wrap(b).getShort(4).toInt()==opcode)return b};error("Missing $opcode")
    }
    private fun action(client:SyntheticClient,body:ByteArray,status:Int=0):Pair<ByteBuffer,ByteBuffer> {
        client.send(body);val current=next(client,0x1200);val last=next(client,0x1200)
        for(b in listOf(current,last))assertEquals(status,ByteBuffer.wrap(b).getShort(16).toInt())
        next(client,0x1201);next(client,0x0356);return ByteBuffer.wrap(current) to ByteBuffer.wrap(last)
    }
    @Test fun theDirectoryListsOnlyTheSameSpotAndAMeetingStoresFriendship() {
        val state=State()
        LoadFixture(directory.resolve("meeting"),3,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
            stockStore=state.inventory,worldStateCommitter=state).use {server ->
            val me=server.join(server.account(0),true,false).client;me.send(SyntheticClient.opcode(0x0f00));next(me,0x0f01)
            for(i in 1..2)server.join(server.account(i),true,false).client.let {it.send(SyntheticClient.opcode(0x0f00));next(it,0x0f01)}
            me.send(directory());val header=ByteBuffer.wrap(next(me,0x1200));assertEquals(1,header.get(22).toInt())
            val people=ByteBuffer.wrap(next(me,0x1202));assertEquals(1,people.get(6).toInt());assertEquals(10001,people.getInt(7));next(me,0x1201)
            val ticket=action(me,meeting(10001)).first.let {assertEquals(50,it.getShort(27).toInt());it.getInt(18)}
            val done=action(me,meeting(10001,1,ticket,38)).second;assertEquals(12,done.get(22).toInt());assertEquals(3,done.getInt(29))
            assertEquals(53,state.stocks.social.meeting.friendship(10000,10001));assertEquals(990,state.players.getValue("load-000").mcp)
            assertEquals(9,action(me,meeting(10001,1,ticket+1,39),9).second.getShort(16).toInt())
            assertEquals(6,action(me,meeting(10002,1,ticket+1,40),6).second.getShort(16).toInt())
        }
    }
}
