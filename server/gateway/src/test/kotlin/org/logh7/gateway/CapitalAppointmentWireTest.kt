package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.CharacterMessages
import java.nio.ByteBuffer
import java.nio.file.*
import java.util.*
import kotlin.test.*

class CapitalAppointmentWireTest {
    @TempDir lateinit var directory:Path
    @Test fun supremeCommanderAppointsImperialCapitalDefense()=exercise(56,2,4)
    @Test fun militaryMinisterAppointsGuardInspector()=exercise(57,2,3)
    @Test fun civilChairAppointsCapitalAdministratorThroughOfficeHierarchy()=exercise(192,1,1)
    @Test fun defenseHeadAppointsAllianceCapitalDefense()=exercise(193,5,8)
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(100) {val b=client.receive();if(ByteBuffer.wrap(b).getShort(4).toInt()==opcode)return b}
        error("Missing opcode $opcode")
    }
    private fun exercise(card:Int,actorRank:Int,targetRank:Int) {
        val rule=CapitalAppointmentPolicy.rule(card)!!
        val power=if(rule.power==2)Power.EMPIRE else Power.ALLIANCE
        val base=if(rule.power==2)1049 else 1177
        val grid=rule.capital.grid
        val galaxy=Galaxy(listOf(GalaxySystem(3,grid,4,"Home",rule.power)),
            listOf(GalaxyBase(base,grid,80,0,rule.capital.baseName,0,20f,86400,0,0f,2f)),
            mapOf(Power.EMPIRE to grid,Power.ALLIANCE to grid))
        val properties=Properties()
        repeat(2) {i ->
            val person=CharacterMessages.Generate(4,10000L+i,rule.power,0,0,"Pilot$i","Test",30,1,1,1,List(8){50},
                0,0,0,if(i==0)actorRank else targetRank,0,0,"Ship$i",0)
            val bytes=CharacterMessages.generateReply(person)
            properties.setProperty("load-%03d".format(i),HexFormat.of().formatHex(bytes.copyOfRange(4,bytes.size)))
        }
        Files.newBufferedWriter(directory.resolve("characters.properties")).use {properties.store(it,null)}
        var players=(0..1).associate {"load-%03d".format(it) to Navigator(1,10000+it,power,grid,1000,1000,1000,
            cpRecoveryAt=SessionRules.load().startSeconds)}
        var stocks=PlanetStocks(1,0,mapOf(base to PlanetStock(base,rule.power,0,100,200,300)),
            deploymentInitialized=true,nationalOffices=mapOf(rule.authority to 10000))
        val navStore=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val stockStore=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        val commit=WorldStateCommitter {nav,inventory ->players=nav;stocks=checkNotNull(inventory)}
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,stockStore=stockStore,navigationStore=navStore,
            worldStateCommitter=commit).use {fixture ->
            val actor=fixture.join(fixture.account(0),false,false).client
            actor.send(SyntheticClient.opcode(0x0f00));val initial=next(actor,0x0323);next(actor,0x0f01)
            assertEquals(rule.authority.card,ByteBuffer.wrap(initial).getShort(initial.size-7).toInt())
            assertEquals(rule.authority.seat(galaxy),ByteBuffer.wrap(initial).getInt(initial.size-5))
            actor.send(ByteBuffer.allocate(4).putShort(0x034e).putShort(rule.authority.card.toShort()).array())
            val nationalRoster=next(actor,0x034f)
            assertEquals(1,nationalRoster[8].toInt());assertEquals(10000,ByteBuffer.wrap(nationalRoster).getInt(9))
            assertEquals(rule.authority.card,ByteBuffer.wrap(nationalRoster).getShort(nationalRoster.size-7).toInt())
            assertEquals(rule.authority.seat(galaxy),ByteBuffer.wrap(nationalRoster).getInt(nationalRoster.size-5))
            val target=fixture.join(fixture.account(1),false,false).client
            target.send(SyntheticClient.opcode(0x0f00));next(target,0x0f01)
            fun query(search:Int,flags:Int,role:Int=0)=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000)
                .putShort(search.toShort()).putShort(flags.toShort()).putShort(role.toShort()).array()
            actor.send(query(18,123));next(actor,0x1200)
            val roles=ByteBuffer.wrap(next(actor,0x1208));roles.position(6)
            val listed=List(roles.short.toInt()) {
                val id=roles.short.toInt();assertEquals(0,roles.int)
                assertTrue(roles.get().toInt()>0);id
            }
            assertEquals(listed.sorted().distinct(),listed)
            assertTrue(card in listed,"Capital appointment must remain available with the additional manual offices")
            assertFalse(roles.hasRemaining());next(actor,0x1201)
            actor.send(query(28,123,card));next(actor,0x1200)
            assertEquals(base,ByteBuffer.wrap(next(actor,0x120d)).getInt(9));next(actor,0x1201)
            actor.send(query(4,80,card));next(actor,0x1200)
            val people=next(actor,0x1202);assertEquals(1,people[6].toInt());assertEquals(10001,ByteBuffer.wrap(people).getInt(7));next(actor,0x1201)
            repeat(2) {attempt ->
                val request=ByteBuffer.allocate(40).putShort(0x0707).putInt(-1).putInt(10000).putInt(-1).putInt(-1)
                    .putInt(10001).putInt(card).putShort(0).putInt(0).putInt(0).putInt(if(attempt==0)0 else base).array()
                actor.send(request);val ack=ByteBuffer.wrap(next(actor,0x0707))
                assertEquals(0,ack.int);assertEquals(840,ack.getInt(18));assertEquals(if(attempt==0)10001 else 0,ack.getInt(22))
                next(actor,0x0356)
                if(attempt==0) {
                    val updated=next(target,0x0356)
                    assertEquals(card,ByteBuffer.wrap(updated).getShort(updated.size-7).toInt())
                    assertEquals(base,ByteBuffer.wrap(updated).getInt(updated.size-5))
                }
                actor.send(ByteBuffer.allocate(4).putShort(0x034e).putShort(card.toShort()).array())
                val roster=next(actor,0x034f);assertEquals(1,roster[8].toInt());assertEquals(10001,ByteBuffer.wrap(roster).getInt(9))
            }
            assertEquals(10001,rule.holder(stocks.bases.getValue(base)))
            assertEquals(840,players.getValue("load-000").mcp);assertEquals(1000,players.getValue("load-001").mcp)
            assertEquals(mapOf(rule.authority to 10000),stocks.nationalOffices)
        }
    }
}
