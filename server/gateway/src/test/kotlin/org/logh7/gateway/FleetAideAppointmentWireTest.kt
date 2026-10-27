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

class FleetAideAppointmentWireTest {
    @TempDir lateinit var directory:Path
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(100) {val b=client.receive();if(ByteBuffer.wrap(b).getShort(4).toInt()==opcode)return b}
        error("Missing opcode $opcode")
    }
    @Test fun aideAppointmentPublishesMembershipAndResignationPreservesTheCommander() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
            mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
        val properties=Properties()
        repeat(2) {i ->
            val person=CharacterMessages.Generate(4,10000L+i,2,0,0,"Pilot$i","Test",30,1,1,1,List(8){50},
                0,0,0,if(i==0)5 else 12,0,0,"Ship$i",0)
            val bytes=CharacterMessages.generateReply(person)
            properties.setProperty("load-%03d".format(i),HexFormat.of().formatHex(bytes.copyOfRange(4,bytes.size)))
        }
        Files.newBufferedWriter(directory.resolve("characters.properties")).use {properties.store(it,null)}
        var players=(0..1).associate {"load-%03d".format(it) to Navigator(1,10000+it,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=4,cpRecoveryAt=SessionRules.load().startSeconds)}
        var stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
            outfits=mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,number=1,commanderId=10000)),
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
            fun query(search:Int,flags:Int,role:Int=0)=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000)
                .putShort(search.toShort()).putShort(flags.toShort()).putShort(role.toShort()).array()
            actor.send(query(18,123));next(actor,0x1200)
            val roles=next(actor,0x1208);assertEquals(1,ByteBuffer.wrap(roles).getShort(6).toInt())
            assertEquals(43,ByteBuffer.wrap(roles).getShort(8).toInt());next(actor,0x1201)
            actor.send(query(27,80,43));next(actor,0x1200)
            val seats=next(actor,0x120c);assertEquals(1,seats[6].toInt());assertEquals(200,ByteBuffer.wrap(seats).getInt(8));next(actor,0x1201)
            actor.send(query(4,80,43));next(actor,0x1200)
            val people=next(actor,0x1202);assertEquals(1,people[6].toInt());assertEquals(10001,ByteBuffer.wrap(people).getInt(7));next(actor,0x1201)
            repeat(2) {attempt ->
                actor.send(ByteBuffer.allocate(40).putShort(0x0707).putInt(-1).putInt(10000).putInt(-1).putInt(-1)
                    .putInt(10001).putInt(43).putShort(0).putInt(0).putInt(200).putInt(0).array())
                val ack=ByteBuffer.wrap(next(actor,0x0707))
                assertEquals(0,ack.int);assertEquals(840,ack.getInt(18));assertEquals(if(attempt==0)200 else 0,ack.getInt(32))
                assertEquals(1049,ack.getInt(40));next(actor,0x0356)
                if(attempt==0) {
                    val updated=next(target,0x0356)
                    assertEquals(43,ByteBuffer.wrap(updated).getShort(updated.size-7).toInt())
                    assertEquals(200,ByteBuffer.wrap(updated).getInt(updated.size-5))
                    val unit=ByteBuffer.wrap(next(target,0x0325))
                    assertEquals(200,unit.getInt(19))
                }
            }
            assertEquals(10001,stocks.outfits.getValue(200).aideId)
            assertEquals(840,players.getValue("load-000").mcp)
            actor.send(ByteBuffer.allocate(11).putShort(0x032e).putInt(200).putInt(1049).put(0).array())
            assertEquals(2,next(actor,0x032f)[25].toInt())
            fun chat(text:String) {
                val b=ByteBuffer.allocate(12+text.length*2).putShort(0x0f1c).putInt(0).putInt(10001).put(2).put(text.length.toByte())
                text.forEach(b::putChar);target.send(b.array());next(target,0x0f1c)
            }
            chat("aide-in-fleet")
            assertEquals(2,next(actor,0x0f1c)[14].toInt())
            actor.send(ByteBuffer.allocate(4).putShort(0x034e).putShort(43).array())
            val roster=next(actor,0x034f);assertEquals(1,roster[8].toInt());assertEquals(10001,ByteBuffer.wrap(roster).getInt(9))
            assertEquals(200,ByteBuffer.wrap(roster).getInt(roster.size-5))
            // The existing resignation command must also clear the newly published unit affiliation.
            target.send(ByteBuffer.allocate(27).putShort(0x0709).putInt(-1).putInt(10001).putInt(-1).putInt(-1)
                .putInt(43).putInt(0).put(0).array())
            assertEquals(0,ByteBuffer.wrap(next(target,0x0709)).int)
            next(target,0x0356);assertEquals(0,ByteBuffer.wrap(next(target,0x0325)).getInt(19))
            assertEquals(0,stocks.outfits.getValue(200).aideId)
            assertEquals(920,players.getValue("load-001").mcp)
            assertEquals(10000,stocks.outfits.getValue(200).commanderId)
            chat("aide-left-fleet")
            actor.send(SyntheticClient.opcode(0x0300))
            for(i in 0..99) {
                val packet=nextAny(actor)
                val opcode=ByteBuffer.wrap(packet).getShort(4).toInt()
                assertNotEquals(0x0f1c,opcode)
                if(opcode==0x0301)break
                assertTrue(i<99)
            }
            assertTrue(commits>0)
        }
    }
    private fun nextAny(client:SyntheticClient)=client.receive()
}
