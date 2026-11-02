package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import kotlin.test.*

/** 암호화 조회/견적/확정·재시작·일일 정산·비권한 접속 금고 비공개. 작성: Peppone Choi. */
class BudgetActionsWireTest {
    @TempDir lateinit var directory:Path
    private val now=SessionRules.load().startSeconds
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2015,4,"Other",3)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2487,80,1,"Fort",0,30f,86400,0,0f,2f),
        GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private inner class State:WorldStateCommitter {
        var players=(0..1).associate {i->"load-%03d".format(i) to Navigator(1,10000+i,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=1,cpRecoveryAt=now)}
        var stocks=PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,if(it.id==1177)3 else 2,0,2000,200,300,
            PlanetEconomy(1000,600,70,80),taxRate=20,treasury=200)},deploymentInitialized=true,
            nationalTreasuries=mapOf(2 to 5001L,3 to 1000L),strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=1)),
                offices=listOf(OfficeHolding(8,1049,10000))))
        val navigation=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?){players=navigation;stocks=checkNotNull(inventory)}
    }
    private fun request(command:Int=18,phase:Int=0,base:Int=1050,rate:Int=0,quote:Int=0,token:Int=37,character:Int=10000)=
        ByteBuffer.allocate(31).putShort(0x1200).putInt(character).putShort(0x7f02).putShort(command.toShort()).putShort(8)
            .putShort(rate.toShort()).putInt(quote).put(phase.toByte()).putInt(0).putShort(0).putInt(base).putShort(token.toShort()).array()
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(200){val b=client.receive();if(ByteBuffer.wrap(b).getShort(4).toInt()==opcode)return b};error("Missing $opcode")
    }
    private fun action(client:SyntheticClient,body:ByteArray,status:Int=0):ByteArray {
        val token=ByteBuffer.wrap(body).getShort(29);client.send(body)
        var result:ByteArray?=null
        repeat(200){if(result==null) {
            val b=client.receive();val v=ByteBuffer.wrap(b)
            if(v.getShort(4).toInt()==0x1200 && v.getShort(10).toInt()==0x7f02 && v.getShort(33)==token && b[22].toInt()==12)result=b
        }}
        val packet=assertNotNull(result);assertEquals(status,ByteBuffer.wrap(packet).getShort(16).toInt())
        next(client,0x1201);next(client,0x0356);return packet
    }
    @Test fun quoteSurvivesRestartConfirmationIsAtomicAndReceiptReplayAfterAnotherRestartNeverPaysTwice() {
        val state=State();var quote=0
        LoadFixture(directory.resolve("quote"),2,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
            stockStore=state.inventory,worldStateCommitter=state).use {server ->
            val actor=server.join(server.account(0),true,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            action(actor,request());val prepared=action(actor,request(phase=1,rate=33,token=38))
            quote=ByteBuffer.wrap(prepared).getInt(29);assertTrue(quote>0)
            assertEquals(1650L,state.stocks.budget.quotes.getValue(10000).amount)
            assertEquals(5001L,state.stocks.nationalTreasuries[2]);assertEquals(1000,state.players.getValue("load-000").mcp)
        }
        LoadFixture(directory.resolve("commit"),2,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
            stockStore=state.inventory,worldStateCommitter=state).use {server ->
            val actor=server.join(server.account(0),true,false).client;val observer=server.join(server.account(1),true,false).client
            observer.send(SyntheticClient.opcode(0x0f00));next(observer,0x0f01)
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01);observer.send(SyntheticClient.opcode(0x0f00));next(observer,0x0f01)
            assertEquals(quote,ByteBuffer.wrap(action(actor,request())).getInt(29))
            val committed=action(actor,request(phase=2,rate=33,quote=quote,token=39))
            assertEquals(2,ByteBuffer.wrap(committed).getShort(27).toInt()%4)
            assertEquals(3351L,state.stocks.nationalTreasuries[2]);assertEquals(1850L,state.stocks.bases.getValue(1050).treasury)
            assertEquals(680,state.players.getValue("load-000").mcp)
            // Drain to a clock barrier: an unrelated same-grid viewer receives no finance snapshots.
            observer.send(SyntheticClient.opcode(0x0300));var clock=false
            repeat(200){if(!clock) {
                val b=observer.receive();val v=ByteBuffer.wrap(b);val op=v.getShort(4).toInt()
                assertFalse(op==0x1200 && b.size>=35 && v.getShort(10).toInt()==0x7f02)
                clock=op==0x0301
            }};assertTrue(clock)
            action(observer,request(character=10001,token=40),BudgetRejection.AUTHORITY.ordinal+1)
        }
        LoadFixture(directory.resolve("receipt"),2,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
            stockStore=state.inventory,worldStateCommitter=state).use {server ->
            val actor=server.join(server.account(0),true,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            action(actor,request(phase=2,rate=33,quote=quote,token=41))
            assertEquals(3351L,state.stocks.nationalTreasuries[2]);assertEquals(1850L,state.stocks.bases.getValue(1050).treasury)
            assertEquals(680,state.players.getValue("load-000").mcp)
            val r=action(actor,request(16,1,1049,80,token=42));val rateQuote=ByteBuffer.wrap(r).getInt(29)
            action(actor,request(16,2,1049,80,rateQuote,43))
            assertEquals(80,state.stocks.budget.remittanceRates[1049]);assertEquals(20,state.stocks.bases.getValue(1049).taxRate)
            assertEquals(360,state.players.getValue("load-000").mcp)
            runBlocking {
                server.engine.submit(WorldCommand.AdvanceTo(86400L*1000/SessionRules.load().speed))
                val reply=CompletableDeferred<PlanetStocks>();server.engine.submit(WorldCommand.Stock(1,reply));withTimeout(5000){reply.await()}
            }
            assertEquals(3447L,state.stocks.nationalTreasuries[2]);assertEquals(224L,state.stocks.bases.getValue(1049).treasury)
            action(actor,request(16,0,1049,token=44));assertEquals(3447L,state.stocks.nationalTreasuries[2])
        }
    }
}
