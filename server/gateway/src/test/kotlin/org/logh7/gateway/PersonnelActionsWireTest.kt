package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.CharacterMessages
import kotlin.test.*

class PersonnelActionsWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2),GalaxySystem(4,2490,4,"There",3)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2487,80,0,"Estate",0,20f,86400,0,0f,2f),
        GalaxyBase(1051,2490,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val now=SessionRules.load().startSeconds
    private class State(now:Long):WorldStateCommitter {
        var players=mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,2000,2000,1000,cpRecoveryAt=now),
            "load-001" to Navigator(1,10001,CountryId(2),2487,1000,1000,1000,cpRecoveryAt=now,
                shipStyle=ShipStyleId("alliance"),hull=FlagshipHull(93,now)))
        var stocks=PlanetStocks(1,0,(1049..1051).associate {id->id to PlanetStock(id,if(id==1051)3 else 2,0,100,200,300)},
            deploymentInitialized=true,strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=0),
                10001 to StrategicPerson(rank=6,merit=100),10002 to StrategicPerson(rank=6,merit=1000000)),
                offices=listOf(OfficeHolding(1,1049,10000),OfficeHolding(17,1049,10000),OfficeHolding(4,1049,10001)),
                fiefs=mapOf(1051 to 10001)))
        val navigation=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?){players=navigation;stocks=checkNotNull(inventory)}
    }
    private fun seed(path:Path) {
        Files.createDirectories(path)
        val store=CharacterCreation(path.resolve("characters.properties"))
        for((account,id,rank) in listOf(Triple("load-000",10000,0),Triple("load-001",10001,6),Triple("foreign",10002,6))) {
            val kind=if(id==10001)93 else 3;val ship=checkNotNull(Flagships.find(kind))
            val data=CharacterMessages.Generate(4,id.toLong(),2,0,0,"Pilot$id","Test",30,1,1,1000001,List(8){50},
                0,0,0,rank,ship.type,kind,"Ship",1)
            assertTrue(store.carry(account,CharacterProfile(data,CountryId(if(id==10002)42 else 2),
                ShipStyleId(if(id==10001)"alliance" else "empire"))))
        }
    }
    private fun request(command:Int,card:Int,value:Int=0,base:Int=0,preview:Boolean=false)=ByteBuffer.allocate(31)
        .putShort(0x1200).putInt(10000).putShort(0x7f00).putShort(command.toShort()).putShort(card.toShort())
        .putShort(value.toShort()).putInt(if(preview)0 else 10001).put(if(preview)1 else 0).putInt(0).putShort(0)
        .putInt(base).putShort(0).array()
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(200){val packet=client.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode)return packet}
        error("Missing response $opcode")
    }
    private fun action(client:SyntheticClient,command:Int,card:Int,value:Int=0,base:Int=0,status:Int=0) {
        client.send(request(command,card,value,base))
        assertEquals(status,ByteBuffer.wrap(next(client,0x1200)).getShort(16).toInt())
        next(client,0x1201);next(client,0x0356)
    }
    @Test fun encryptedPersonnelTargetsExcludeSameDisplayForeignersAndKeepTitlesFiefsAndStyleAcrossRestart() {
        val state=State(now)
        fun server(name:String):LoadFixture {
            val path=directory.resolve(name);seed(path)
            return LoadFixture(path,2,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
                stockStore=state.inventory,worldStateCommitter=state)
        }
        server("first").use {fixture->
            val actor=fixture.join(fixture.account(0),false,false).client
            val target=fixture.join(fixture.account(1),false,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            target.send(SyntheticClient.opcode(0x0f00));next(target,0x0f01)
            actor.send(request(4,17,preview=true));next(actor,0x1200)
            val rows=ByteBuffer.wrap(next(actor,0x1202))
            assertEquals(1,rows.get(6).toInt());assertEquals(10001,rows.getInt(7));next(actor,0x1201)
            action(actor,4,17);assertEquals(1840,state.players.getValue("load-000").mcp)
            action(actor,3,1,Peerage.BARON.ordinal);assertEquals(1680,state.players.getValue("load-000").mcp)
            action(actor,8,1,base=1050);assertEquals(1040,state.players.getValue("load-000").mcp)
            action(actor,8,1,base=1051,status=StrategyActionRejection.LOCATION.ordinal+1)
            assertEquals(1040,state.players.getValue("load-000").mcp)
            action(actor,0,17);assertEquals(880,state.players.getValue("load-000").mcp)
            val person=state.stocks.strategic.people.getValue(10001)
            assertEquals(5,person.rank);assertEquals(Peerage.BARON,person.peerage);assertEquals(setOf(0),person.decorations)
            assertTrue(state.stocks.strategic.offices.none {it.character==10001})
            val upgraded=state.players.getValue("load-001")
            assertEquals(CountryId(2),upgraded.country);assertEquals(ShipStyleId("alliance"),upgraded.shipStyle)
            assertEquals(Flagships.rankDefault(3,5).kind,upgraded.hull?.kind)
        }
        server("restart").use {fixture->
            val actor=fixture.join(fixture.account(0),false,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            val cards=runBlocking {EngineGameAdministration(fixture.engine,this,galaxy).offices(10001,2,1)}
            assertEquals(listOf(1050),cards.filter {it.kind==66}.map {it.target})
            assertTrue(cards.any {it.kind==60+Peerage.BARON.ordinal})
            action(actor,9,1,base=1050);assertEquals(240,state.players.getValue("load-000").mcp)
            assertEquals(mapOf(1051 to 10001),state.stocks.strategic.fiefs,"historical foreign data is preserved but is not an active card")
        }
    }
}
