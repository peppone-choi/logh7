package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.BootstrapMessages
import org.logh7.protocol.CharacterMessages
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat
import java.util.Properties
import kotlin.test.*

/** 국가 직무의 암호화 목록·해임·온라인 카드 갱신 검사. 작성: 최병호. */
class NationalOfficeDismissalWireTest {
    @TempDir lateinit var directory:Path

    @Test fun supremeCommanderDismissesImperialStaffDirector()=exercise(NationalOffice.IMPERIAL_STAFF_DIRECTOR)
    @Test fun defenseCommitteeChairDismissesAllianceFleetCommander()=exercise(NationalOffice.ALLIANCE_FLEET_COMMANDER)
    @Test fun allianceFleetCommanderDismissesFleetChief()=exercise(NationalOffice.ALLIANCE_FLEET_CHIEF)

    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(100) {
            val packet=client.receive()
            if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode) return packet
        }
        error("Missing opcode $opcode")
    }

    private fun changedCharacter(client:SyntheticClient,character:Int):ByteArray {
        repeat(100) {
            val packet=client.receive()
            val input=ByteBuffer.wrap(packet)
            if(input.getShort(4).toInt()==0x0356 && input.getInt(7)==character) return packet
        }
        error("Missing changed character $character")
    }

    private fun directoryQuery(search:Int,flags:Int,role:Int=0)=ByteBuffer.allocate(31)
        .putShort(0x1200).putInt(10000).putShort(search.toShort()).putShort(flags.toShort())
        .putShort(role.toShort()).array()

    private fun roles(client:SyntheticClient):Map<Int,Int> {
        client.send(directoryQuery(18,123))
        next(client,0x1200)
        val input=ByteBuffer.wrap(next(client,0x1208));input.position(6)
        val count=input.short.toInt() and 65535
        val values=linkedMapOf<Int,Int>()
        repeat(count) {
            val card=input.short.toInt() and 65535
            assertEquals(0,input.int)
            assertNull(values.put(card,input.get().toInt() and 255),"Duplicate directory card $card")
        }
        assertFalse(input.hasRemaining())
        next(client,0x1201)
        return values
    }

    private fun people(client:SyntheticClient,card:Int,count:Int) {
        client.send(directoryQuery(5,80,card))
        next(client,0x1200)
        val packet=next(client,0x1202)
        assertEquals(count,packet[6].toInt() and 255)
        if(count==1) assertEquals(10001,ByteBuffer.wrap(packet).getInt(7))
        next(client,0x1201)
    }

    private fun roster(client:SyntheticClient,card:Int,count:Int) {
        client.send(ByteBuffer.allocate(4).putShort(0x034e).putShort(card.toShort()).array())
        val packet=next(client,0x034f)
        assertEquals(0,ByteBuffer.wrap(packet).int)
        assertEquals(card,ByteBuffer.wrap(packet).getShort(6).toInt())
        assertEquals(count,packet[8].toInt() and 255)
        if(count==1) assertEquals(10001,ByteBuffer.wrap(packet).getInt(9))
    }

    private fun exercise(role:NationalOffice) {
        val authority=checkNotNull(role.appointer)
        val power=if(role.power==2)Power.EMPIRE else Power.ALLIANCE
        val base=if(role.power==2)1049 else 1177
        val grid=role.capital.grid
        val galaxy=Galaxy(listOf(GalaxySystem(3,grid,4,"Home",role.power)),
            listOf(GalaxyBase(base,grid,80,0,role.capital.baseName,0,20f,86400,0,0f,2f)),
            mapOf(Power.EMPIRE to grid,Power.ALLIANCE to grid))
        // Both holders are marshals: dismissal follows the stored superior office, not a rank comparison.
        val characters=(0..1).map {i -> CharacterMessages.Generate(4,10000L+i,role.power,0,0,"Pilot$i","Test",
            30,1,1,1,List(8){50},0,0,0,if(i==0)authority.ranks.max() else 2,0,if(role.power==2)0 else 89,"Ship$i",0)}
        val properties=Properties()
        characters.forEachIndexed {i,person ->
            val reply=CharacterMessages.generateReply(person)
            properties.setProperty("load-%03d".format(i),HexFormat.of().formatHex(reply.copyOfRange(4,reply.size)))
        }
        Files.newBufferedWriter(directory.resolve("characters.properties")).use {properties.store(it,null)}
        var navigators=(0..1).associate {i -> "load-%03d".format(i) to Navigator(1,10000+i,power,grid,
            1000,1000,1000,cpRecoveryAt=SessionRules.load().startSeconds)}
        var stocks=PlanetStocks(1,0,mapOf(base to PlanetStock(base,role.power,0,100,200,300)),
            deploymentInitialized=true,nationalOffices=mapOf(authority to 10000,role to 10001))
        val navigation=object:NavigationStore {
            override fun load()=navigators
            override fun save(players:Map<String,Navigator>){navigators=players}
        }
        val inventory=object:PlanetStockStore {
            override fun load()=stocks
            override fun save(value:PlanetStocks){stocks=value}
        }
        var commits=0
        val commit=WorldStateCommitter {nav,stored -> navigators=nav;stocks=checkNotNull(stored);commits++}
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=navigation,stockStore=inventory,
            worldStateCommitter=commit).use {fixture ->
            val actor=fixture.join(fixture.account(0),false,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            val target=fixture.join(fixture.account(1),false,false).client
            target.send(SyntheticClient.opcode(0x0f00))
            assertContentEquals(BootstrapMessages.character(characters[1],1000,1000,
                offices=listOf(BootstrapMessages.OfficeCard(role.card,checkNotNull(role.seat(galaxy))))),next(target,0x0323))
            next(target,0x0f01)

            assertEquals(1,roles(actor)[role.card])
            people(actor,role.card,1)
            roster(actor,role.card,1)
            val targetBefore=navigators.getValue("load-001")
            val commitsBefore=commits
            val query=ByteBuffer.allocate(31).putShort(0x0708).putInt(-1).putInt(10000).putInt(-1).putInt(-1)
                .putInt(10001).putInt(role.card).putInt(0).put(0).array()
            repeat(2) {attempt ->
                actor.send(query)
                val ack=ByteBuffer.wrap(next(actor,0x0708))
                assertEquals(if(attempt==0)0 else 1,ack.int)
                assertEquals(1000,ack.getInt(14));assertEquals(840,ack.getInt(18))
                assertEquals(if(attempt==0)10001 else 0,ack.getInt(22));assertEquals(role.card,ack.getInt(26))
                assertContentEquals(BootstrapMessages.changedCharacter(characters[0],1000,840,
                    offices=listOf(BootstrapMessages.OfficeCard(authority.card,checkNotNull(authority.seat(galaxy))))),
                    changedCharacter(actor,10000))
                if(attempt==0) {
                    assertContentEquals(BootstrapMessages.changedCharacter(characters[1],1000,1000),changedCharacter(target,10001))
                }
                assertEquals(commitsBefore+1,commits,"Replay must not commit or charge again")
                assertEquals(mapOf(authority to 10000),stocks.nationalOffices)
                assertEquals(targetBefore,navigators.getValue("load-001"))
                assertEquals(840,navigators.getValue("load-000").mcp)
                assertEquals(1000,navigators.getValue("load-000").pcp)
                // Dismissal frees the same seat for a replacement appointment.
                assertEquals(1,roles(actor)[role.card])
                people(actor,role.card,0)
                roster(actor,role.card,0)
            }
        }
    }
}
