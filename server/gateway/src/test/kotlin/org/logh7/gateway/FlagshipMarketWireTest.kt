package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.CharacterMessages
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
    private fun request(character:Int,command:Int,mode:Int=0,target:Int=0,value:Int=0,preview:Boolean=false)=ByteBuffer.allocate(31)
        .putShort(0x1200).putInt(character).putShort(0x7f00).putShort(command.toShort()).putShort((if(command==61)59 else 0).toShort())
        .putShort(value.toShort()).putInt(target).put(if(preview)1 else 0).putInt(mode).putShort(0).putInt(0).putShort(0).array()
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(200){val packet=client.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode)return packet}
        error("Missing response $opcode")
    }
    private fun action(client:SyntheticClient,body:ByteArray):ByteBuffer {
        client.send(body);val result=ByteBuffer.wrap(next(client,0x1200))
        assertEquals(0,result.getShort(16).toInt())
        next(client,0x1201);next(client,0x0356)
        return result
    }
    private fun purchasePreview(client:SyntheticClient,character:Int,mode:Int=0):ByteBuffer {
        client.send(request(character,96,mode=mode,preview=true))
        val header=ByteBuffer.wrap(next(client,0x1200))
        next(client,0x1202);next(client,0x1201)
        assertEquals(mode,header.getInt(23))
        assertEquals(1,header.getShort(27).toInt())
        return header
    }
    @Test fun exchangeDirectoryUsesActualMembershipCurrentRankAndPagingAcrossSharedDisplayCodes() {
        val path=directory.resolve("directory");Files.createDirectories(path)
        val characters=CharacterCreation(path.resolve("characters.properties"))
        for((account,id,country) in listOf(Triple("load-000",10000,2),Triple("load-001",10001,2),
            Triple("foreign",10002,43),Triple("different_display",10003,42))) {
            val data=CharacterMessages.Generate(4,id.toLong(),if(id==10003)3 else 2,0,0,"Pilot$id","Test",30,1,1,1000001,
                List(8){50},0,0,0,20,0,0,"Ship",1)
            assertTrue(characters.carry(account,CharacterProfile(data,CountryId(country),ShipStyleId("empire"))))
        }
        val state=State();val directoryOnly=AtomicBoolean(false)
        LoadFixture(path,2,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,stockStore=state.inventory,
            worldStateCommitter=state,navigationDecorator={delegate->object:GameNavigation by delegate {
                // This tests the directory projection; full runtime admission of country 42 is still fenced.
                override fun snapshot()=delegate.snapshot()?.let {snapshot->if(!directoryOnly.get())snapshot else snapshot.copy(
                    navigators=snapshot.navigators.mapValues {(_,p)->p.copy(country=CountryId(42))})}
            }}).use {fixture->
            val client=fixture.join(fixture.account(0),false,false).client
            client.send(SyntheticClient.opcode(0x0f00));next(client,0x0f01)
            directoryOnly.set(true)
            fun directory(offset:Int):List<Pair<Int,Int>> {
                client.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(0x7f00).putShort(96).putShort(0)
                    .putShort(0).putInt(offset).put(1).putInt(1).putShort(0).putInt(0).putShort(0).array())
                next(client,0x1200);val bytes=ByteBuffer.wrap(next(client,0x1202));next(client,0x1201)
                bytes.position(6)
                val rows=List(bytes.get().toInt() and 255) {
                    val id=bytes.int;val length=bytes.get().toInt() and 255;bytes.position(bytes.position()+length*2)
                    val rank=bytes.short.toInt();bytes.position(bytes.position()+13);id to rank
                }
                assertEquals(bytes.limit(),bytes.position());return rows
            }
            assertEquals(listOf(10001 to 5,10003 to 20),directory(0))
            assertEquals(listOf(10003 to 20),directory(10001))
            assertTrue(directory(10003).isEmpty())
            assertTrue(state.stocks.flagshipMarket.offers.isEmpty());assertEquals(1000,state.players.getValue("load-000").pcp)
        }
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
            assertEquals(1000,purchasePreview(giver,10000).getInt(29))
            assertEquals(0,purchasePreview(receiver,10001,mode=4).getInt(29))
            assertEquals(1000L,state.stocks.strategic.people.getValue(10000).evaluation)
            val receipt=action(giver,request(10000,96))
            assertEquals(1,receipt.getShort(27).toInt());assertEquals(950,receipt.getInt(29))
            assertEquals(3,state.players.getValue("load-000").hull?.kind)
            assertEquals(950L,state.stocks.strategic.people.getValue(10000).evaluation)
            assertEquals(0,action(giver,request(10000,96,1,10001)).getShort(27).toInt())
            assertEquals(1,state.stocks.flagshipMarket.offers.size)
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
