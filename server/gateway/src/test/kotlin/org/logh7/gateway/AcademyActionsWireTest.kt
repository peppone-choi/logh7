package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import kotlin.test.*

/** 암호화 사관학교 수강·강의·병기 연습 요청의 실제 저장과 카드 명령 공개. 작성: Peppone Choi. */
class AcademyActionsWireTest {
    @TempDir lateinit var directory:Path
    private val now=SessionRules.load().startSeconds
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2015,4,"Other",3)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val classroom=galaxy.facilities.getValue(1049).single {it.kind==20}.spots.single {it.kind==AcademyActions.CLASSROOM}.id
    private inner class State:WorldStateCommitter {
        var players=(0..1).associate {i->"load-%03d".format(i) to Navigator(1,10000+i,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=1,spotId=classroom,cpRecoveryAt=now)}
        var stocks=PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,if(it.id==1049)2 else 3,0,
            2000,200,300,PlanetEconomy(1000,600,70,80),treasury=200)},deploymentInitialized=true,
            strategic=StrategicState(people=(0..1).associate {i->10000+i to StrategicPerson(rank=8,
                abilities=listOf(50,60,50,50,50,50,50,50))},offices=listOf(OfficeHolding(38,1,10001))))
        val navigation=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?){players=navigation;stocks=checkNotNull(inventory)}
    }
    private fun request(command:Int,argument:Int,phase:Int=0,ticket:Int=0,character:Int=10000,card:Int=0,token:Int=37)=
        ByteBuffer.allocate(31).putShort(0x1200).putInt(character).putShort(0x7f11).putShort(command.toShort()).putShort(card.toShort())
            .putShort(0).putInt(ticket).put(phase.toByte()).putInt(0).putShort(argument.toShort()).putInt(0).putShort(token.toShort()).array()
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(200){val b=client.receive();if(ByteBuffer.wrap(b).getShort(4).toInt()==opcode)return b};error("Missing $opcode")
    }
    private fun action(client:SyntheticClient,body:ByteArray,status:Int=0):Pair<ByteBuffer,ByteBuffer> {
        client.send(body);val current=next(client,0x1200);val last=next(client,0x1200)
        for(b in listOf(current,last))assertEquals(status,ByteBuffer.wrap(b).getShort(16).toInt())
        next(client,0x1201);next(client,0x0356);return ByteBuffer.wrap(current) to ByteBuffer.wrap(last)
    }
    @Test fun aLectureAndAttendanceCommitThroughTheOriginalEnvelope() {
        val state=State()
        LoadFixture(directory.resolve("academy"),2,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
            stockStore=state.inventory,worldStateCommitter=state).use {server ->
            val student=server.join(server.account(0),true,false).client;student.send(SyntheticClient.opcode(0x0f00));next(student,0x0f01)
            val lecturer=server.join(server.account(1),true,false).client;lecturer.send(SyntheticClient.opcode(0x0f00));next(lecturer,0x0f01)
            var ticket=action(lecturer,request(27,0,character=10001,card=38)).first.getInt(18)
            val lecture=action(lecturer,request(27,0,1,ticket,10001,38,38));assertEquals(12,lecture.second.get(22).toInt())
            assertEquals(1,lecture.first.getShort(27).toInt() shr 8);assertEquals(840,state.players.getValue("load-001").mcp)
            val preview=action(student,request(93,2)).first;ticket=preview.getInt(18)
            assertEquals(1,preview.getShort(27).toInt() shr 8);assertEquals(7200,preview.getInt(23))
            val heard=action(student,request(93,2,1,ticket,token=39)).second;assertEquals(12,heard.get(22).toInt())
            assertEquals(4,heard.getInt(18));assertEquals(50*128+50,heard.getShort(27).toInt());assertEquals(100,heard.getInt(29))
            assertEquals(listOf(0,0,0,0,50,50,0,0),state.stocks.social.academy.experience[10000])
            assertEquals(840,state.players.getValue("load-000").mcp)
            assertEquals(9,action(student,request(93,2,1,ticket+1,token=40),9).second.getShort(16).toInt())
        }
    }
    @Test fun personalAndAcademyCardsAdvertiseTheReconstructedCommands() {
        assertTrue(org.logh7.protocol.WorldMessages.commandsForCard(0).containsAll(listOf(93,94)))
        for(card in listOf(37,38,173,174))assertEquals(listOf(27),org.logh7.protocol.WorldMessages.commandsForCard(card))
    }
}
