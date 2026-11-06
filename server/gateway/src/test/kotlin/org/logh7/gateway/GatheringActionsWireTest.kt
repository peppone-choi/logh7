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

/** 초대 목록·실시간 알림·서버 재시작 뒤 수락과 주최자 CP의 암호화 통신. 작성: Peppone Choi. */
class GatheringActionsWireTest {
    @TempDir lateinit var directory:Path
    private val now=SessionRules.load().startSeconds
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val room=1049*10000+17*200+13+1
    private inner class State:WorldStateCommitter {
        var players=(0..1).associate {i->"load-%03d".format(i) to Navigator(1,10000+i,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=1,spotId=if(i==0)room else 1049*10000+17*200+2,spotOwner=if(i==0)10000 else 0,cpRecoveryAt=now)}
        var stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,2000,200,300,PlanetEconomy(1000,600,70,80))),
            deploymentInitialized=true,strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=1,influence=10,
                abilities=List(8){90}),10001 to StrategicPerson(rank=8,abilities=List(8){50}))),
            social=SocialState(funds=FundState(confidence=mapOf(2 to (50L to 3L))),production=ProductionState(paused=setOf(1049))))
        val navigation=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?){players=navigation;stocks=checkNotNull(inventory)}
    }
    private fun seed(path:Path) {
        Files.createDirectories(path);val store=CharacterCreation(path.resolve("characters.properties"))
        for((account,id) in listOf("load-000" to 10000,"load-001" to 10001,"foreign" to 10002)) {
            val ship=checkNotNull(Flagships.find(3))
            val data=CharacterMessages.Generate(4,id.toLong(),2,0,0,"Pilot$id","Test",30,1,1,1000001,List(8){50},
                0,0,0,if(id==10000)1 else 8,ship.type,ship.kind,"Ship",1)
            assertTrue(store.carry(account,CharacterProfile(data,CountryId(if(id==10002)42 else 2),ShipStyleId("empire"))))
        }
    }
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(200){val p=client.receive();if(ByteBuffer.wrap(p).getShort(4).toInt()==opcode)return p};error("Missing $opcode")
    }
    private fun request(character:Int,target:Int,value:Int=0,phase:Int=0,ticket:Int=0)=ByteBuffer.allocate(31)
        .putShort(0x1200).putInt(character).putShort(0x7f13).putShort(13).putShort(0).putShort(value.toShort()).putInt(ticket)
        .put(phase.toByte()).putInt(0).putShort(0).putInt(target).putShort(37).array()
    private fun action(client:SyntheticClient,body:ByteArray,status:Int=0):ByteBuffer {
        client.send(body);val current=next(client,0x1200);val last=next(client,0x1200)
        for(p in listOf(current,last))assertEquals(status,ByteBuffer.wrap(p).getShort(16).toInt())
        next(client,0x1201);next(client,0x0356);return ByteBuffer.wrap(current)
    }
    @Test fun anInvitationSurvivesRestartAndChargesOnlyTheHostOnceWhileTheDirectoryUsesActualCountries() {
        val state=State();val showForeign=AtomicBoolean(false)
        fun server(name:String):LoadFixture {
            val path=directory.resolve(name);seed(path)
            return LoadFixture(path,2,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
                stockStore=state.inventory,worldStateCommitter=state,navigationDecorator={delegate->object:GameNavigation by delegate {
                    // Directory fixture only: runtime admission of country 42 is still fenced in WorldEngine.
                    override fun snapshot()=delegate.snapshot()?.let {if(!showForeign.get())it else it.copy(navigators=it.navigators+
                        ("foreign" to state.players.getValue("load-001").copy(characterId=10002,country=CountryId(42))))}
                }})
        }
        server("first").use {fixture->
            val host=fixture.join(fixture.account(0),false,false).client;host.send(SyntheticClient.opcode(0x0f00));next(host,0x0f01)
            val guest=fixture.join(fixture.account(1),false,false).client;guest.send(SyntheticClient.opcode(0x0f00));next(guest,0x0f01)
            showForeign.set(true)
            host.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(0x7f00).putShort(13).putShort(0)
                .putShort(0).putInt(0).put(1).putInt(0).putShort(0).putInt(0).putShort(0).array())
            next(host,0x1200);val rows=ByteBuffer.wrap(next(host,0x1202));next(host,0x1201)
            showForeign.set(false)
            assertEquals(1,rows.get(6).toInt());assertEquals(10001,rows.getInt(7))
            action(host,request(10000,10001,phase=1,ticket=1))
            assertEquals(1000,state.players.getValue("load-000").mcp)
            assertEquals(13,next(guest,0x1200)[22].toInt(),"the guest receives an invitation")
            val pending=action(guest,request(10001,10000));assertEquals(2,pending.getInt(18))
            assertEquals(10001,state.stocks.social.gatherings.invites.getValue(10000).guest)
        }
        server("restart").use {fixture->
            val host=fixture.join(fixture.account(0),false,false).client;host.send(SyntheticClient.opcode(0x0f00));next(host,0x0f01)
            val guest=fixture.join(fixture.account(1),false,false).client;guest.send(SyntheticClient.opcode(0x0f00));next(guest,0x0f01)
            action(guest,request(10001,10000,value=1,phase=1,ticket=2))
            assertEquals(680,state.players.getValue("load-000").mcp);assertEquals(1000,state.players.getValue("load-001").mcp)
            assertTrue(state.stocks.social.gatherings.invites.isEmpty());assertEquals(now+86400,state.stocks.social.gatherings.hostNextAt[10000])
            assertEquals(mapOf(2 to (50L to 3L)),state.stocks.social.funds.confidence)
            assertEquals(setOf(1049),state.stocks.social.production.paused)
            action(guest,request(10001,10000,value=1,phase=1,ticket=2),SocialRejection.STALE.ordinal+1)
            assertEquals(680,state.players.getValue("load-000").mcp)
        }
    }
}
