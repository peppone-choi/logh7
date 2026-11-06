package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.MailMessages as Mail
import kotlin.test.*

/** 암호화 연설·공개 지지/인물 갱신·상대 재실행 제한·서버 재시작. 작성: Peppone Choi. */
class SocialActionsWireTest {
    @TempDir lateinit var directory:Path
    private val now=SessionRules.load().startSeconds
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2015,4,"Other",3)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val square=galaxy.facilities.getValue(1049).single {it.kind==2}.spots.single().id
    private inner class State:WorldStateCommitter {
        var failSave=false
        var players=(0..1).associate {i->"load-%03d".format(i) to Navigator(1,10000+i,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=1,spotId=square,cpRecoveryAt=now)}
        var stocks=PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,if(it.id==1049)2 else 3,0,
            2000,200,300,PlanetEconomy(1000,600,70,80),treasury=200)},deploymentInitialized=true,
            strategic=StrategicState(people=(0..1).associate {i->10000+i to StrategicPerson(rank=1,influence=10,
                abilities=listOf(50,60,50,50,50,50,50,50),money=789)}))
        val navigation=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?){
            if(failSave)throw java.io.IOException("test save failure")
            players=navigation;stocks=checkNotNull(inventory)
        }
    }
    private fun request(phase:Int=0,ticket:Int=0,character:Int=10000,token:Int=37)=ByteBuffer.allocate(31)
        .putShort(0x1200).putInt(character).putShort(0x7f10).putShort(14).putShort(0).putShort(0).putInt(ticket)
        .put(phase.toByte()).putInt(0).putShort(0).putInt(0).putShort(token.toShort()).array()
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(200){val b=client.receive();if(ByteBuffer.wrap(b).getShort(4).toInt()==opcode)return b};error("Missing $opcode")
    }
    private fun action(client:SyntheticClient,body:ByteArray,status:Int=0):Pair<ByteArray,ByteArray> {
        client.send(body);val current=next(client,0x1200);val last=next(client,0x1200)
        for(b in listOf(current,last))assertEquals(status,ByteBuffer.wrap(b).getShort(16).toInt())
        next(client,0x1201);next(client,0x0356);return current to last
    }
    private fun influence(profile:ByteArray):Int {
        val b=ByteBuffer.wrap(profile);b.position(10)
        val surname=b.get().toInt() and 255;b.position(b.position()+surname*2+14)
        val ship=b.get().toInt() and 255;b.position(b.position()+ship*2+28)
        return b.get().toInt() and 255
    }
    @Test fun encryptedCoupEnvelopeKeepsCountryCostsAndRollbackAcrossRestart() {
        val state=State()
        state.stocks=state.stocks.copy(strategic=state.stocks.strategic.copy(
            people=mapOf(10000 to StrategicPerson(rank=5)),offices=listOf(OfficeHolding(58,1049,10000))))
        fun perform(client:SyntheticClient,command:Int,status:Int=0) {
            client.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(0x7f00)
                .putShort(command.toShort()).putShort(0).putShort(0).putInt(0).put(0).putInt(0).putShort(0).putInt(0).putShort(0).array())
            assertEquals(status,ByteBuffer.wrap(next(client,0x1200)).getShort(16).toInt())
            next(client,0x1201);next(client,0x0356)
        }
        LoadFixture(directory.resolve("coup-first"),2,LoadMetrics(),galaxy,ticking=false,
            navigationStore=state.navigation,stockStore=state.inventory,worldStateCommitter=state).use {server->
            val actor=server.join(server.account(0),true,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            perform(actor,CoupActions.INTENT)
            assertEquals(360,state.players.getValue("load-000").pcp)
            assertEquals(CountryId(2),state.stocks.social.coup.plots.getValue(10000).country)
            perform(actor,CoupActions.REVOLT,SocialRejection.CP.ordinal+1)
            assertEquals(0,state.stocks.social.coup.plots.getValue(10000).revolt)
        }
        // Top up only the saved QA fixture before a new actor restores it.
        state.players=state.players.mapValues {(_,p)->p.copy(pcp=1000,pcpUsed=0)}
        LoadFixture(directory.resolve("coup-restart"),2,LoadMetrics(),galaxy,ticking=false,
            navigationStore=state.navigation,stockStore=state.inventory,worldStateCommitter=state).use {server->
            val actor=server.join(server.account(0),true,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            state.failSave=true
            assertFailsWith<java.io.IOException> {perform(actor,CoupActions.REVOLT)}
            assertEquals(1000,state.players.getValue("load-000").pcp)
            assertEquals(0,state.stocks.bases.getValue(1049).camp)
            assertEquals(EngineHealth.STORAGE_FAILED,server.engine.health())
            assertEquals(0,server.engine.snapshot().planetStocks!!.bases.getValue(1049).camp)
        }
        state.failSave=false
        LoadFixture(directory.resolve("coup-after-failure"),2,LoadMetrics(),galaxy,ticking=false,
            navigationStore=state.navigation,stockStore=state.inventory,worldStateCommitter=state).use {server->
            val actor=server.join(server.account(0),true,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            perform(actor,CoupActions.REVOLT)
            assertEquals(360,state.players.getValue("load-000").pcp)
            assertEquals(1,state.stocks.bases.getValue(1049).camp)
            assertTrue(OfficeHolding(68,1049,10000) in state.stocks.strategic.offices)
        }
    }
    @Test fun encryptedDefectionCommitsNavigationSessionAndCharacterMembershipAcrossRestart() {
        val state=State();val path=directory.resolve("defection")
        fun inbox(client:SyntheticClient):List<Mail.Item> {
            val query=Mail.begin(Mail.Query(Mail.Peer(10000),true,true))
            client.send(query.copyOfRange(4,query.size))
            next(client,0x0f08)
            val rows=mutableListOf<Mail.Item>()
            repeat(200) {
                val packet=client.receive();val opcode=ByteBuffer.wrap(packet).getShort(4).toInt() and 65535
                if(opcode==0x0f09)return rows
                if(opcode==0x0f0a)rows+=Mail.item(packet.copyOfRange(4,packet.size),0x0f0a)
            }
            error("Missing mailbox end")
        }
        LoadFixture(path,2,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
            stockStore=state.inventory,worldStateCommitter=state).use {server->
            val actor=server.join(server.account(0),true,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            val friend=server.join(server.account(1),true,false).client
            friend.send(SyntheticClient.opcode(0x0f00));next(friend,0x0f01)
            val mail=Mail.item(Mail.Item(0,-1,false,Mail.Peer(10001),Mail.Peer(10000),0,"망명 전 편지","보존할 본문"),0x0f10)
            friend.send(mail.copyOfRange(4,mail.size));next(friend,0x0f10)
            actor.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(0x7f00)
                .putShort(Justice.DEFECT.toShort()).putShort(0).putShort(0).putInt(0)
                .put(0).putInt(0).putShort(0).putInt(0).putShort(0).array())
            assertEquals(0,ByteBuffer.wrap(next(actor,0x1200)).getShort(16).toInt())
            val player=state.players.getValue("load-000")
            assertEquals(CountryId(3),player.country);assertEquals(680,player.pcp)
            assertEquals(11770019,player.spotId)
            assertEquals(CountryId(3),state.stocks.social.justice.detentions.getValue(10000).country)
            assertEquals(CountryId(3),CharacterCreation(path.resolve("characters.properties")).profiles().getValue("load-000").country)
            actor.close()
            val rejoined=server.join(server.account(0),false,false).client
            rejoined.send(SyntheticClient.opcode(0x0f00));next(rejoined,0x0f01)
            assertEquals(CountryId(3),server.engine.snapshot().navigators.getValue("load-000").country)
            assertEquals("보존할 본문",inbox(rejoined).single().text)
        }
        LoadFixture(path,2,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
            stockStore=state.inventory,worldStateCommitter=state).use {server->
            val actor=server.join(server.account(0),false,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            assertEquals(CountryId(3),server.engine.snapshot().navigators.getValue("load-000").country)
            assertEquals(680,state.players.getValue("load-000").pcp)
            assertEquals("보존할 본문",inbox(actor).single().text)
        }
    }
    @Test fun originalEnvelopeCommitsARealSpeechPublishesEffectsAndRestoresItsReceiptWithoutAnotherCharge() {
        val state=State();var ticket=0
        LoadFixture(directory.resolve("first"),2,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
            stockStore=state.inventory,worldStateCommitter=state).use {server ->
            val actor=server.join(server.account(0),true,false).client;actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            val peer=server.join(server.account(1),true,false).client;peer.send(SyntheticClient.opcode(0x0f00));next(peer,0x0f01)
            ticket=ByteBuffer.wrap(action(actor,request()).first).getInt(18);assertEquals(1,ticket)
            val result=action(actor,request(1,ticket,token=38));assertEquals(12,result.second[22].toInt())
            assertEquals(680,state.players.getValue("load-000").mcp);assertEquals(11,state.stocks.strategic.people.getValue(10000).influence)
            assertEquals(72,state.stocks.bases.getValue(1049).economy?.approval);assertEquals(789L,state.stocks.strategic.people.getValue(10000).money)
            var seen=false;repeat(30){if(!seen){val b=ByteBuffer.wrap(next(peer,0x031f));seen=b.get(6).toInt()>0 && b.getInt(7)==1049 && b.getShort(91).toInt()==72}};assertTrue(seen)
            peer.send(ByteBuffer.allocate(6).putShort(0x0354).putInt(10000).array())
            assertEquals(11,influence(next(peer,0x0355)))
            action(actor,request(1,ticket,token=39));assertEquals(680,state.players.getValue("load-000").mcp)
            action(peer,request(1,2,10001,40),SocialRejection.COOLDOWN.ordinal+1)
            assertEquals(1000,state.players.getValue("load-001").mcp)
        }
        LoadFixture(directory.resolve("restart"),2,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
            stockStore=state.inventory,worldStateCommitter=state).use {server ->
            val actor=server.join(server.account(0),true,false).client;actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            val query=action(actor,request(token=41));assertEquals(ticket,ByteBuffer.wrap(query.second).getInt(18))
            assertEquals(86400,ByteBuffer.wrap(query.first).getInt(23));assertEquals(11*128+72,ByteBuffer.wrap(query.first).getShort(27).toInt())
            action(actor,request(1,ticket,token=42));assertEquals(680,state.players.getValue("load-000").mcp)
            assertEquals(72,state.stocks.bases.getValue(1049).economy?.approval)
            assertEquals(now+86400,state.stocks.social.speakers[10000])
        }
    }
}
