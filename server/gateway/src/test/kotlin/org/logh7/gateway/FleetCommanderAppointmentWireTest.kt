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

class FleetCommanderAppointmentWireTest {
    @TempDir lateinit var directory:Path
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(100) {val b=client.receive();if(ByteBuffer.wrap(b).getShort(4).toInt()==opcode)return b}
        error("Missing opcode $opcode")
    }
    @Test fun appointmentPublishesCommandAndUnitMembershipThenResignationRemovesBoth() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
            mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
        val properties=Properties()
        repeat(2) {i ->
            val person=CharacterMessages.Generate(4,10000L+i,2,0,0,"Pilot$i","Test",30,1,1,1,List(8){50},
                0,0,0,if(i==0)2 else 5,0,0,"Ship$i",0)
            val bytes=CharacterMessages.generateReply(person)
            properties.setProperty("load-%03d".format(i),HexFormat.of().formatHex(bytes.copyOfRange(4,bytes.size)))
        }
        Files.newBufferedWriter(directory.resolve("characters.properties")).use {properties.store(it,null)}
        var players=(0..1).associate {"load-%03d".format(it) to Navigator(1,10000+it,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=4,cpRecoveryAt=SessionRules.load().startSeconds)}
        var stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
            outfits=mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,number=1)),
            warehouses=mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200)),
            deploymentInitialized=true,nationalOffices=mapOf(NationalOffice.MILITARY_MINISTER to 10000))
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
            val roles=ByteBuffer.wrap(next(actor,0x1208));roles.position(6)
            val listed=List(roles.short.toInt()) {
                val id=roles.short.toInt();assertEquals(0,roles.int)
                assertTrue(roles.get().toInt()>0);id
            }
            assertEquals(listed.sorted().distinct(),listed)
            assertTrue(39 in listed,"The fleet commander appointment must remain available")
            assertFalse(roles.hasRemaining());next(actor,0x1201)
            actor.send(query(27,80,39));next(actor,0x1200)
            val seats=next(actor,0x120c);assertEquals(1,seats[6].toInt());assertEquals(200,ByteBuffer.wrap(seats).getInt(8));next(actor,0x1201)
            actor.send(query(4,80,39));next(actor,0x1200)
            val people=next(actor,0x1202);assertEquals(1,people[6].toInt());assertEquals(10001,ByteBuffer.wrap(people).getInt(7));next(actor,0x1201)
            repeat(2) {attempt ->
                actor.send(ByteBuffer.allocate(40).putShort(0x0707).putInt(-1).putInt(10000).putInt(-1).putInt(-1)
                    .putInt(10001).putInt(39).putShort(0).putInt(0).putInt(200).putInt(0).array())
                val ack=ByteBuffer.wrap(next(actor,0x0707))
                assertEquals(0,ack.int);assertEquals(840,ack.getInt(18));assertEquals(if(attempt==0)200 else 0,ack.getInt(32))
                assertEquals(1049,ack.getInt(40));next(actor,0x0356)
                if(attempt==0) {
                    val updated=next(target,0x0356)
                    assertEquals(39,ByteBuffer.wrap(updated).getShort(updated.size-7).toInt())
                    assertEquals(200,ByteBuffer.wrap(updated).getInt(updated.size-5))
                    val unit=ByteBuffer.wrap(next(target,0x0325))
                    assertEquals(200,unit.getInt(19))
                }
            }
            assertEquals(10001,stocks.outfits.getValue(200).commanderId)
            assertEquals(840,players.getValue("load-000").mcp)
            actor.send(ByteBuffer.allocate(4).putShort(0x034e).putShort(39).array())
            val roster=next(actor,0x034f);assertEquals(1,roster[8].toInt());assertEquals(10001,ByteBuffer.wrap(roster).getInt(9))
            assertEquals(200,ByteBuffer.wrap(roster).getInt(roster.size-5))
            // The existing resignation command must also clear the newly published unit affiliation.
            target.send(ByteBuffer.allocate(27).putShort(0x0709).putInt(-1).putInt(10001).putInt(-1).putInt(-1)
                .putInt(39).putInt(0).put(0).array())
            assertEquals(0,ByteBuffer.wrap(next(target,0x0709)).int)
            next(target,0x0356);assertEquals(0,ByteBuffer.wrap(next(target,0x0325)).getInt(19))
            assertEquals(0,stocks.outfits.getValue(200).commanderId)
            assertEquals(920,players.getValue("load-001").mcp)
            assertTrue(commits>0)
        }
    }

    @Test fun tacticalObserverGetsCurrentAuthorityWhenACommanderChangesFleets() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2488,4,"Battle",2)),
            listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
            Power.entries.associateWith {2487})
        val properties=Properties()
        for(i in 0..2) {
            val person=CharacterMessages.Generate(4,10000L+i,2,0,0,"Pilot$i","Test",30,1,1,1,List(8){50},
                0,0,0,if(i==0)2 else 5,0,0,"Ship$i",0)
            val bytes=CharacterMessages.generateReply(person)
            properties.setProperty("load-%03d".format(i),HexFormat.of().formatHex(bytes.copyOfRange(4,bytes.size)))
        }
        Files.newBufferedWriter(directory.resolve("characters.properties")).use {properties.store(it,null)}
        val now=SessionRules.load().startSeconds
        var players=(0..2).associate {i->
            val observer=i==2
            "load-%03d".format(i) to Navigator(1,10000+i,Power.EMPIRE,if(observer)2488 else 2487,1000,1000,1000,
                baseId=if(observer)0 else 1049,mode=if(observer)5 else 4,cpPaused=observer,cpRecoveryAt=now)
        }
        val fleets=(1..3).associateWith {id->
            val stack=UnitStack(if(id==3)119 else 32)
            OutfitStock(id,if(id==3)3 else 2,OutfitKind.FLEET,1049,id+10,commanderId=if(id==1)10001 else 0,
                units=UnitInventory(mapOf(stack to 1)),activeShips=ActiveShipUnits(listOf(ActiveShipUnit(id,stack))),
                position=OutfitPosition(2488))
        }
        var stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),outfits=fleets,
            warehouses=fleets.values.associate {it.warehouseId to OutfitWarehouse(it.warehouseId,it.country,it.kind,it.baseId,it.id)},
            deploymentInitialized=true,nextShipId=4,nationalOffices=mapOf(NationalOffice.MILITARY_MINISTER to 10000),
            tacticalCommands=TacticalCommandState(authority=mapOf(1 to 10001)))
        val navStore=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val stockStore=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        val commit=WorldStateCommitter {nav,inventory ->players=nav;stocks=checkNotNull(inventory)}
        LoadFixture(directory,3,LoadMetrics(),galaxy,ticking=false,stockStore=stockStore,navigationStore=navStore,
            worldStateCommitter=commit,powerByIndex={2}).use {fixture->
            val clients=(0..2).map {i->fixture.join(fixture.account(i),false,false).client.also {client->
                client.send(SyntheticClient.opcode(0x0f00));next(client,0x0f01)
            }}
            val (actor,target,observer)=clients
            fun commanders():Map<Int,Int> {
                val states=ByteBuffer.wrap(next(observer,0x033b))
                return (0 until states.getShort(6).toInt()).associate {row->
                    val offset=8+47*row
                    states.getInt(offset) to states.getInt(offset+6)
                }.filterKeys {it in 1..2}
            }
            observer.send(SyntheticClient.opcode(0x0f02))
            assertEquals(mapOf(1 to 10001,2 to 0),commanders())
            next(observer,0x0f03)
            actor.send(ByteBuffer.allocate(31).putShort(0x0708).putInt(-1).putInt(10000).putInt(-1).putInt(-1)
                .putInt(10001).putInt(39).putInt(0).put(0).array())
            assertEquals(0,ByteBuffer.wrap(next(actor,0x0708)).int)
            assertEquals(mapOf(1 to 0,2 to 0),commanders())
            actor.send(ByteBuffer.allocate(40).putShort(0x0707).putInt(-1).putInt(10000).putInt(-1).putInt(-1)
                .putInt(10001).putInt(39).putShort(0).putInt(0).putInt(2).putInt(0).array())
            assertEquals(0,ByteBuffer.wrap(next(actor,0x0707)).int)
            assertEquals(mapOf(1 to 0,2 to 10001),commanders())
            target.send(ByteBuffer.allocate(27).putShort(0x0709).putInt(-1).putInt(10001).putInt(-1).putInt(-1)
                .putInt(39).putInt(0).put(0).array())
            assertEquals(0,ByteBuffer.wrap(next(target,0x0709)).int)
            assertEquals(mapOf(1 to 0,2 to 0),commanders())
        }
    }
}
