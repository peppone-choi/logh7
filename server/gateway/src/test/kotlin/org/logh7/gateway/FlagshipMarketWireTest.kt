package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import kotlin.test.*

/** 암호화 요청→수리/구매/합의 교환→양측 알림과 재접속. 작성: Peppone Choi. 실행은 리드 담당. */
class FlagshipMarketWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private inner class State:WorldStateCommitter {
        private val now=SessionRules.load().startSeconds
        private val yard=galaxy.facilities.getValue(1049).single {it.kind==23}.spots.single {it.kind==21}.id
        var players=mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,
            spotId=yard,cpRecoveryAt=now,hull=FlagshipHull(0,now,hp=72),supplies=35),
            "load-001" to Navigator(1,10001,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,
                spotId=yard,cpRecoveryAt=now,hull=FlagshipHull(2,now,hp=89),supplies=27))
        var stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300,PlanetEconomy(1000,600,70,80))),
            strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=5,evaluation=1000),10001 to StrategicPerson(rank=5))),
            deploymentInitialized=true)
        val navigation=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?){players=navigation;stocks=checkNotNull(inventory)}
    }
    private fun request(character:Int,command:Int,mode:Int=0,target:Int=0,value:Int=0)=ByteBuffer.allocate(31)
        .putShort(0x1200).putInt(character).putShort(0x7f00).putShort(command.toShort()).putShort((if(command==61)59 else 0).toShort())
        .putShort(value.toShort()).putInt(target).put(0).putInt(mode).putShort(0).putInt(0).putShort(0).array()
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(200){val packet=client.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode)return packet}
        error("Missing response $opcode")
    }
    private fun action(client:SyntheticClient,body:ByteArray) {
        client.send(body);assertEquals(0,ByteBuffer.wrap(next(client,0x1200)).getShort(16).toInt())
        next(client,0x1201);next(client,0x0356)
    }
    @Test fun repairedAndPurchasedHullsThenConsentExchangeReachBothOwnersAndSurviveRestart() {
        val state=State()
        LoadFixture(directory.resolve("first"),2,LoadMetrics(),galaxy,ticking=false,
            navigationStore=state.navigation,stockStore=state.inventory,worldStateCommitter=state).use {server->
            val giver=server.join(server.account(0),true,false).client
            val receiver=server.join(server.account(1),true,false).client
            giver.send(SyntheticClient.opcode(0x0f00));next(giver,0x0f01)
            receiver.send(SyntheticClient.opcode(0x0f00));next(receiver,0x0f01)
            action(giver,request(10000,61));assertEquals(100,state.players.getValue("load-000").hull?.hp)
            assertEquals(0,state.players.getValue("load-000").supplies);assertEquals(840,state.players.getValue("load-000").mcp)
            action(giver,request(10000,96));assertEquals(3,state.players.getValue("load-000").hull?.kind)
            assertEquals(950L,state.stocks.strategic.people.getValue(10000).evaluation)
            action(giver,request(10000,96,1,10001));assertEquals(1,state.stocks.flagshipMarket.offers.size)
            action(receiver,request(10001,96,2,10000))
            assertEquals(2,state.players.getValue("load-000").hull?.kind);assertEquals(3,state.players.getValue("load-001").hull?.kind)
            assertEquals(89,state.players.getValue("load-000").hull?.hp);assertEquals(27,state.players.getValue("load-001").supplies)
            assertEquals(920,state.players.getValue("load-000").pcp);assertEquals(920,state.players.getValue("load-001").pcp)
            assertTrue(state.stocks.flagshipMarket.offers.isEmpty())
            var observed=false
            repeat(30){if(!observed){val packet=next(giver,0x0325);val b=ByteBuffer.wrap(packet)
                if(b.getInt(8)==1_010_000 && b.getShort(12).toInt()==2)observed=true}}
            assertTrue(observed)
        }
        LoadFixture(directory.resolve("restart"),2,LoadMetrics(),galaxy,ticking=false,
            navigationStore=state.navigation,stockStore=state.inventory,worldStateCommitter=state).use {server->
            val client=server.join(server.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00));next(client,0x0f01)
            assertEquals(2,state.players.getValue("load-000").hull?.kind);assertEquals(89,state.players.getValue("load-000").hull?.hp)
            assertEquals(950L,state.stocks.strategic.people.getValue(10000).evaluation)
        }
    }
}
