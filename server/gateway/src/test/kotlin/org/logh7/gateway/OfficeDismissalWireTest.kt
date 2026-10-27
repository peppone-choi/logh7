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

class OfficeDismissalWireTest {
    @TempDir lateinit var directory:Path
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(100) {val b=client.receive();if(ByteBuffer.wrap(b).getShort(4).toInt()==opcode)return b}
        error("Missing opcode $opcode")
    }
    @Test fun dismissalRevokesOnlineMembershipPreservesCommanderAndRejectsReplay() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
            mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
        val properties=Properties()
        repeat(2) {i ->
            val person=CharacterMessages.Generate(4,10000L+i,2,0,0,"Pilot$i","Test",30,1,1,1,List(8){50},
                0,0,0,if(i==0)5 else 11,0,0,"Ship$i",0)
            val bytes=CharacterMessages.generateReply(person)
            properties.setProperty("load-%03d".format(i),HexFormat.of().formatHex(bytes.copyOfRange(4,bytes.size)))
        }
        Files.newBufferedWriter(directory.resolve("characters.properties")).use {properties.store(it,null)}
        var players=(0..1).associate {"load-%03d".format(it) to Navigator(1,10000+it,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=4,cpRecoveryAt=SessionRules.load().startSeconds)}
        var stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
            outfits=mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,number=1,commanderId=10000,staff=FleetStaff(officers=listOf(10001)))),
            warehouses=mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200)),
            deploymentInitialized=true)
        val navStore=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val stockStore=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        var commits=0
        val commit=WorldStateCommitter {nav,inventory ->players=nav;stocks=checkNotNull(inventory);commits++}
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,stockStore=stockStore,navigationStore=navStore,
            worldStateCommitter=commit).use {fixture ->
            val actor=fixture.join(fixture.account(0),false,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            val target=fixture.join(fixture.account(1),false,false).client
            target.send(SyntheticClient.opcode(0x0f00));next(target,0x0f01)
            val lookup=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(5).putShort(80).putShort(42).array()
            actor.send(lookup);next(actor,0x1200)
            val people=next(actor,0x1202);assertEquals(1,people[6].toInt());assertEquals(10001,ByteBuffer.wrap(people).getInt(7));next(actor,0x1201)
            val query=ByteBuffer.allocate(31).putShort(0x0708).putInt(-1).putInt(10000).putInt(-1).putInt(-1)
                .putInt(10001).putInt(42).putInt(0).put(0).array()
            repeat(2) {attempt ->
                actor.send(query);val ack=ByteBuffer.wrap(next(actor,0x0708))
                assertEquals(if(attempt==0)0 else 1,ack.int);assertEquals(840,ack.getInt(18))
                assertEquals(if(attempt==0)10001 else 0,ack.getInt(22));next(actor,0x0356)
                if(attempt==0) {
                    val changed=next(target,0x0356)
                    assertContentEquals(org.logh7.protocol.BootstrapMessages.changedCharacter(
                        CharacterMessages.Generate(4,10001,2,0,0,"Pilot1","Test",30,1,1,1,List(8){50},0,0,0,11,0,0,"Ship1",0),1000,1000),changed)
                    assertEquals(0,ByteBuffer.wrap(next(target,0x0325)).getInt(19))
                }
                actor.send(ByteBuffer.allocate(4).putShort(0x034e).putShort(42).array())
                assertEquals(0,next(actor,0x034f)[8].toInt())
            }
            actor.send(lookup);next(actor,0x1200);assertEquals(0,next(actor,0x1202)[6].toInt());next(actor,0x1201)
            assertEquals(840,players.getValue("load-000").mcp);assertEquals(1000,players.getValue("load-001").mcp)
            assertTrue(stocks.outfits.getValue(200).staff.officers.isEmpty());assertEquals(10000,stocks.outfits.getValue(200).commanderId)
            assertTrue(commits>0)
        }
    }
}
