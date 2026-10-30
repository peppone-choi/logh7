package org.logh7.gateway

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class TacticalFleetMotionWireTest {
    @TempDir lateinit var directory:Path
    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun fleetMoveUsesAuthoritativeOriginResumesAndStopsForBothPeers(parallel:Boolean)=runBlocking {
        val opcode=if(parallel)0x0402 else 0x0400
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Battle",3)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val now=SessionRules.load().startSeconds
        fun motion(y:Float)=TacticalMotion(2488,now,TacticalPose(TacticalPoint(0f,y),0f),0f,0f,listOf(TacticalPoint(0f,y)))
        var savedPlayers=(0..1).associate {i->"load-00$i" to Navigator(1,10000+i,Power.EMPIRE,2488,1000,1000,900,
            cpPaused=true,supplies=if(i==0)400 else 1000,hull=FlagshipHull(0,now,hp=100),motion=motion(0.5f-i*0.25f))}
        val kind=32
        val stack=UnitStack(kind);val enemy=UnitStack(119)
        var savedStocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100)),
            outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,commanderId=10000,
                units=UnitInventory(mapOf(stack to 1)),supplies=10000,
                activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack,supplies=10000,
                    tactical=NpcTacticalState(motion(0f),now,now)))),position=OutfitPosition(2488)),
                2 to OutfitStock(2,3,OutfitKind.FLEET,1000,20,units=UnitInventory(mapOf(enemy to 1)),
                    activeShips=ActiveShipUnits(listOf(ActiveShipUnit(99,enemy))),position=OutfitPosition(2488))),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1),20 to OutfitWarehouse(20,3,OutfitKind.FLEET,1000,2)),
            deploymentInitialized=true,nextShipId=100)
        val nav=object:NavigationStore {override fun load()=savedPlayers;override fun save(players:Map<String,Navigator>){savedPlayers=players}}
        val stock=object:PlanetStockStore {override fun load()=savedStocks;override fun save(stocks:PlanetStocks){savedStocks=stocks}}
        val commit=WorldStateCommitter {p,s->savedPlayers=p.toMap();savedStocks=checkNotNull(s)}
        fun through(client:SyntheticClient,opcode:Int,predicate:(ByteArray)->Boolean={true}):ByteArray {
            repeat(500){val packet=client.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode && predicate(packet))return packet}
            error("Missing $opcode")
        }
        fun command(character:Int):ByteArray {
            val packet=org.logh7.protocol.TacticalMoveMessages.reply(org.logh7.protocol.TacticalMoveMessages.Move(character,
                listOf(org.logh7.protocol.TacticalMoveMessages.Unit(1,0f,org.logh7.protocol.TacticalMoveMessages.Point(999f,999f,0f))),
                1f,0f,listOf(3f,9.5f,20f).map {org.logh7.protocol.TacticalMoveMessages.Point(0f,it,0f)},parallel),0,0)
            return packet.copyOfRange(4,packet.size)
        }
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=nav,stockStore=stock,
            worldStateCommitter=commit,powerByIndex={2}).use {fixture ->
            val clients=(0..1).map {i->fixture.join(fixture.account(i),true,false).client.also {c ->
                c.send(SyntheticClient.opcode(0x0f00));through(c,0x0f01)
                c.send(SyntheticClient.opcode(0x0f02));through(c,0x0f03)
            }}.toMutableList()
            clients[1].send(command(10001))
            assertEquals(1,ByteBuffer.wrap(through(clients[1],opcode) {ByteBuffer.wrap(it).int==1}).int)
            clients[0].send(command(10000))
            for(c in clients) {
                val ack=ByteBuffer.wrap(through(c,opcode) {ByteBuffer.wrap(it).getInt(19)==1 && ByteBuffer.wrap(it).getInt(14)==10000})
                assertEquals(0,ack.int);assertEquals(10000,ack.getInt(14));assertEquals(1,ack.getInt(19))
                assertEquals(0f,ack.getFloat(27));assertEquals(0f,ack.getFloat(31));assertEquals(120,ack.getInt(10))
                assertEquals(if(parallel)0.5f else 1f,ack.getFloat(39))
            }
            val crc=ByteBuffer.wrap(through(clients[0],0x033f) {
                val b=ByteBuffer.wrap(it);b.getShort(6).toInt()==2 && b.getInt(8)==10000 && b.getFloat(18)==0f
            })
            assertEquals(10001,crc.getInt(63)) // Keep the other corps in the native replacement cache.
            assertEquals(10000,savedStocks.outfits.getValue(1).activeShips!!.values.single().motionCommander)
            fixture.engine.submit(WorldCommand.AdvanceTo(5292)) // 127 G-seconds: seven after the 120-G-second wait.
            for(c in clients) {
                val progress=through(c,0x0423) {ByteBuffer.wrap(it).getInt(10)==1}
                assertEquals(1,progress[30].toInt())
            }
            assertEquals(1,savedStocks.outfits.getValue(1).activeShips!!.values.single().tactical!!.motion.reportedWaypoints)
            clients[1].close();fixture.awaitState(online=1,connections=1,participants=2)
            clients[1]=fixture.join(fixture.account(1),false,false).client.also {c ->
                c.send(SyntheticClient.opcode(0x0f00));through(c,0x0f01)
                c.send(SyntheticClient.opcode(0x0f02));through(c,0x0f03)
                val resume=ByteBuffer.wrap(through(c,opcode) {ByteBuffer.wrap(it).getInt(19)==1})
                assertEquals(if(parallel)3.5f else 7f,resume.getFloat(31));assertEquals(0,resume.getInt(10))
                fixture.engine.submit(WorldCommand.AdvanceTo(5392)) // Import delay: another two G-seconds.
                val query=ByteBuffer.allocate(8).putShort(0x0348).putShort(1).putInt(1).array()
                c.send(query);through(c,0x0349)
                val live=ByteBuffer.wrap(through(c,opcode) {ByteBuffer.wrap(it).getInt(19)==1})
                assertEquals(if(parallel)4.5f else 9f,live.getFloat(31));assertEquals(0,live.getInt(10))
                // Unit 1 is the last saved path; the next poll must not replay again.
                c.send(query)
                assertEquals(0x0349,ByteBuffer.wrap(c.receive()).getShort(4).toInt())
            }
            fixture.engine.submit(WorldCommand.AdvanceTo(5792)) // Nineteen moving G-seconds, as before the wait was scaled.
            clients.forEachIndexed {i,c ->
                val progress=through(c,0x0423) {ByteBuffer.wrap(it).getInt(10)==1}
                assertEquals(if(i==0)2 else 1,progress[30].toInt())
            }
            val stop=org.logh7.protocol.TacticalStopMessages.reply(org.logh7.protocol.TacticalStopMessages.Stop(10000,listOf(1)),0)
            clients[0].send(stop.copyOfRange(4,stop.size))
            assertEquals(1,ByteBuffer.wrap(through(clients[0],0x040a)).int)
            assertEquals(if(parallel)0.5f else 1f,savedStocks.outfits.getValue(1).activeShips!!.values.single().tactical!!.motion.velocity)
            fixture.engine.submit(WorldCommand.AdvanceTo(19000)) // CRC has grown back to the maximum.
            for(c in clients) {
                val arrived=ByteBuffer.wrap(through(c,0x0423) {ByteBuffer.wrap(it).getInt(10)==1})
                assertEquals(-1,arrived.get(30).toInt());assertEquals(20f,arrived.getFloat(22));assertEquals(0f,arrived.getFloat(14))
            }
            assertEquals(0f,savedStocks.outfits.getValue(1).activeShips!!.values.single().tactical!!.motion.velocity)
            clients[0].send(stop.copyOfRange(4,stop.size))
            for(c in clients) {
                assertEquals(0,ByteBuffer.wrap(through(c,0x040a)).int)
                val stopped=ByteBuffer.wrap(through(c,0x0400) {ByteBuffer.wrap(it).getInt(19)==1})
                assertEquals(0f,stopped.getFloat(39));assertEquals(20f,stopped.getFloat(31))
            }
            assertEquals(0f,savedStocks.outfits.getValue(1).activeShips!!.values.single().tactical!!.motion.velocity)
            assertEquals(10000,savedStocks.outfits.getValue(1).supplies)
        }
    }
}
