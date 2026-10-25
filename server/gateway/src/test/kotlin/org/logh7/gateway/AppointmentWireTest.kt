package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.CharacterMessages
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat
import java.util.Properties
import kotlin.test.*

class AppointmentWireTest {
    @TempDir lateinit var directory: Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private class State(governor:Int,galaxy:Galaxy):WorldStateCommitter {
        var players=(0..1).associate { "load-%03d".format(it) to Navigator(1,10000+it,Power.EMPIRE,2487,1000,1000,1000,
            cpRecoveryAt=SessionRules.load().startSeconds) }
        var stocks=PlanetStocks(1,0,galaxy.conditions.mapValues { (_,c)->PlanetStock(c.baseId,c.power,c.camp,
            100,200,300,PlanetEconomy(c.population,c.adultPopulation,c.approval,c.peace),governorId=governor) },deploymentInitialized=true)
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?) { players=navigation.toMap();stocks=checkNotNull(inventory) }
        val navigationStore=object:NavigationStore {
            override fun load()=players
            override fun save(players:Map<String,Navigator>) { this@State.players=players.toMap() }
        }
        val stockStore=object:PlanetStockStore {
            override fun load()=stocks
            override fun save(stocks:PlanetStocks) { this@State.stocks=stocks }
        }
    }
    private fun request()=ByteBuffer.allocate(40).putShort(0x0707).putInt(-1).putInt(10000)
        .putInt(Int.MAX_VALUE).putInt(Int.MAX_VALUE).putInt(10001).putInt(55).putShort(0).putInt(0).putInt(0).putInt(1049).array()
    private fun next(client:SyntheticClient,opcode:Int,id:Int?=null):ByteArray {
        repeat(100) { val value=client.receive();val b=ByteBuffer.wrap(value)
            if(b.getShort(4).toInt()==opcode && (id==null || b.getInt(if(opcode==0x0356)7 else 6)==id))return value }
        error("Missing $opcode/$id")
    }
    @Test fun encryptedAppointmentNotifiesOnlineTargetAndRetryCannotDuplicateCardsOrPayment()=exercise(10000,10)
    @Test fun forgedGovernorCardCannotAppoint()=exercise(0,10)
    @Test fun clientSuppliedCardCannotBypassStoredTargetRank()=exercise(10000,20)
    private fun exercise(governor:Int,targetRank:Int) {
        val properties=Properties()
        for(i in 0..1) {
            val person=CharacterMessages.Generate(4,10000L+i,2,0,0,"Pilot$i","Test",30,1,1,1,List(8){50},
                0,0,0,if(i==0)7 else targetRank,0,0,"Ship$i",0)
            properties.setProperty("load-%03d".format(i),HexFormat.of().formatHex(CharacterMessages.generateReply(person).copyOfRange(4,CharacterMessages.generateReply(person).size)))
        }
        Files.newBufferedWriter(directory.resolve("characters.properties")).use { properties.store(it,null) }
        val state=State(governor,galaxy)
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,stockStore=state.stockStore,
            navigationStore=state.navigationStore,worldStateCommitter=state).use { fixture->
            val actor=fixture.join(fixture.account(0),false,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            val target=fixture.join(fixture.account(1),false,false).client
            target.send(SyntheticClient.opcode(0x0f00));next(target,0x0f01)
            val accepted=governor==10000 && targetRank==10
            if(governor!=0) {
                fun directoryQuery(search:Int,flags:Int,role:Int=0,base:Int=0)=ByteBuffer.allocate(31)
                    .putShort(0x1200).putInt(10000).putShort(search.toShort()).putShort(flags.toShort()).putShort(role.toShort())
                    .also { it.putInt(25,base) }.array()
                actor.send(directoryQuery(18,123))
                next(actor,0x1200)
                val roles=ByteBuffer.wrap(next(actor,0x1208))
                assertEquals(1,roles.getShort(6).toInt());assertEquals(55,roles.getShort(8).toInt())
                assertEquals(0,roles.getInt(10));assertEquals(1,roles.get(14).toInt())
                next(actor,0x1201)
                actor.send(directoryQuery(28,123,55))
                next(actor,0x1200)
                val bases=ByteBuffer.wrap(next(actor,0x120d))
                assertEquals(1,bases.getShort(6).toInt());assertEquals(1049,bases.getInt(9))
                assertEquals(1049,bases.getShort(15).toInt())
                next(actor,0x1201)
                actor.send(directoryQuery(4,80,55))
                next(actor,0x1200)
                val people=next(actor,0x1202)
                assertEquals(if(accepted)1 else 0,people[6].toInt())
                if(accepted)assertEquals(10001,ByteBuffer.wrap(people).getInt(7))
                next(actor,0x1201)
                actor.send(ByteBuffer.allocate(6).putShort(0x0322).putInt(10001).array())
                val detail = next(actor,0x0323)
                assertEquals(10001,ByteBuffer.wrap(detail).getInt(6))
                assertEquals(2,detail[detail.size-14].toInt())
                val registeredTarget=CharacterMessages.generate(HexFormat.of().parseHex(properties.getProperty("load-001")))
                assertContentEquals(org.logh7.protocol.BootstrapMessages.character(registeredTarget,0,0),detail)
            }
            repeat(2) { attempt->
                actor.send(request())
                val ack=ByteBuffer.wrap(next(actor,0x0707))
                assertEquals(if(accepted)0 else 1,ack.int)
                assertEquals(1000,ack.getInt(14));assertEquals(if(accepted)840 else 1000,ack.getInt(18))
                assertEquals(if(accepted && attempt==0)10001 else 0,ack.getInt(22))
                next(actor,0x0356,10000)
                if(accepted) {
                    val updated=next(target,0x0356,10001)
                    val tail=ByteBuffer.wrap(updated,updated.size-20,20)
                    assertEquals(3,tail.get().toInt());tail.position(tail.position()+12)
                    assertEquals(55,tail.short.toInt());assertEquals(1049,tail.int)
                }
                actor.send(ByteBuffer.allocate(4).putShort(0x034e).putShort(55).array())
                val roster=next(actor,0x034f)
                assertEquals(if(accepted)1 else 0,roster[8].toInt())
                if(accepted)assertEquals(10001,ByteBuffer.wrap(roster).getInt(9))
            }
            assertEquals(if(accepted)10001 else 0,state.stocks.bases.getValue(1049).garrisonCommanderId)
            assertEquals(if(accepted)840 else 1000,state.players.getValue("load-000").mcp)
            assertEquals(1000,state.players.getValue("load-001").mcp)
            assertEquals(2487,state.players.getValue("load-001").grid)
            assertTrue(state.stocks.outfits.values.none { it.commanderId==10001 })
        }
    }
}
