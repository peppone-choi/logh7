package org.logh7.gateway

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.TacticalTurnMessages
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class TacticalManeuverWireTest {
    @TempDir lateinit var directory:Path
    @Test
    fun reconnectOmitsDeadCommandersRotationAndRestoresTheLivingGroup() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        fun motion(id:Int,selection:List<Int>):TacticalMotion {
            val point=TacticalPoint(id.toFloat()%10,0f)
            return TacticalMotion(2487,100,TacticalPose(point,0f),0f,1f,listOf(point),
                rotation=TacticalRotation(TacticalManeuver.TURN,selection.first(),120,selection))
        }
        val living=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,900,cpPaused=true,
            motion=motion(1010000,listOf(1010000)))
        val dead=living.copy(characterId=10001,motion=motion(1010001,listOf(1,1010001)))
        val ship=ActiveShipUnit(1,UnitStack(32),motionCommander=dead.characterId,
            tactical=NpcTacticalState(motion(1,listOf(1,1010001)),100,100))
        val ships=ActiveShipUnits(listOf(ship))
        val outfit=OutfitStock(1,2,OutfitKind.FLEET,1000,10,dead.characterId,UnitInventory(ships.quantities()),
            activeShips=ships,position=OutfitPosition(2487))
        val stocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0)),
            outfits=mapOf(1 to outfit),warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),
            strategic=StrategicState(people=mapOf(dead.characterId to StrategicPerson(deceasedAt=105))),nextShipId=2)
        val snapshot=WorldSnapshot(0,0,SessionSnapshot(1,110,0,0,emptyMap(),1,1,null),
            navigators=mapOf("living" to living,"dead" to dead),planetStocks=stocks)
        val turns=TacticalBootstrap(snapshot,galaxy,living){null}.resume()
            .filter {ByteBuffer.wrap(it).getShort(4).toInt()==0x0401}
        val turn=turns.single()
        assertEquals(110,ByteBuffer.wrap(turn).getInt(10))
        assertEquals(listOf(1010000),TacticalTurnMessages.command(turn.copyOfRange(4,turn.size)).units.map {it.id})
    }

    @ParameterizedTest @ValueSource(strings=["turn","left","right","legacy-right"])
    fun formationCommandUsesServerHeadingsResumesAsOneGroupAndCompletesForBothPeers(commandKind:String)=runBlocking {
        val reverse=commandKind!="turn"
        val reverseSide=if(commandKind.endsWith("right"))1 else 0
        val initialDirection=if(commandKind=="right") .5f else 0f
        val target=initialDirection+(if(!reverse) Math.PI/2 else if(reverseSide==0) -Math.PI else Math.PI)
        val completedDirection=target.toFloat()
        val requestHeading=when(commandKind) {"left"->2f;"right"->1f;else->2.5f}
        val opcode=if(reverse)0x0403 else 0x0401
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Battle",3)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val now=SessionRules.load().startSeconds
        fun motion(y:Float)=TacticalMotion(2488,now,TacticalPose(TacticalPoint(0f,y),initialDirection),0f,initialDirection,listOf(TacticalPoint(0f,y)))
        var savedPlayers=(0..1).associate {i->"load-00$i" to Navigator(1,10000+i,Power.EMPIRE,2488,1000,1000,900,
            cpPaused=true,supplies=if(i==0)400 else 1000,hull=FlagshipHull(0,now,hp=100),motion=motion(0.5f-i*0.25f))}
        val kind=32
        val stack=UnitStack(kind);val enemy=UnitStack(119)
        var savedStocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100)),
            outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,commanderId=10000,
                units=UnitInventory(mapOf(stack to 2)),supplies=10000,
                activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack,supplies=10000,
                    tactical=NpcTacticalState(motion(0f),now,now)),
                    ActiveShipUnit(2,stack,tactical=NpcTacticalState(motion(1f),now,now)))),position=OutfitPosition(2488)),
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
        fun command(character:Int)=TacticalTurnMessages.reply(TacticalTurnMessages.Command(character,
            listOf(1,2,1010000).map {TacticalTurnMessages.Unit(it,requestHeading)},
            if(reverse)null else (Math.PI/2).toFloat(),if(commandKind=="legacy-right")1 else 0),123,999).let {it.copyOfRange(4,it.size)}
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=nav,stockStore=stock,
            worldStateCommitter=commit,powerByIndex={2}).use {fixture ->
            fun join(i:Int,first:Boolean)=fixture.join(fixture.account(i),first,false).client.also {c ->
                c.send(SyntheticClient.opcode(0x0f00));through(c,0x0f01)
                c.send(SyntheticClient.opcode(0x0f02));through(c,0x0f03)
            }
            val clients=mutableListOf(join(0,true),join(1,true))
            clients[1].send(command(10001))
            assertEquals(1,ByteBuffer.wrap(through(clients[1],opcode)).int)
            if(reverse) {
                val beforePlayers=savedPlayers
                val beforeStocks=savedStocks
                val unsupported=command(10000).also {it[it.lastIndex]=2}
                clients[0].send(unsupported)
                val rejection=ByteBuffer.wrap(through(clients[0],opcode))
                assertEquals(1,rejection.int)
                assertEquals(0,rejection.getInt(10))
                assertEquals(beforePlayers,savedPlayers)
                assertEquals(beforeStocks,savedStocks)
            }
            clients[0].send(command(10000))
            for(c in clients) {
                val bytes=through(c,opcode);val packet=ByteBuffer.wrap(bytes)
                assertEquals(0,packet.int);assertEquals(if(reverse)240 else 120,packet.getInt(10))
                assertEquals(3,packet.get(18).toInt());assertEquals(1,packet.getInt(19))
                assertEquals(initialDirection,packet.getFloat(23));assertEquals(2,packet.getInt(27))
                assertEquals(1010000,packet.getInt(35));assertEquals(initialDirection,packet.getFloat(39))
            }
            val pending=savedStocks.outfits.getValue(1).activeShips!!.values.single {it.id==2}.tactical!!.motion
            assertNotNull(pending.rotation);assertEquals(1,pending.rotation!!.leader)
            assertEquals(reverseSide,checkNotNull(pending.rotation).reverseSide)
            fixture.engine.submit(WorldCommand.AdvanceTo(2000)) // 48 G-seconds: still inside the manual wait.
            assertEquals(pending,savedStocks.outfits.getValue(1).activeShips!!.values.single {it.id==2}.tactical!!.motion)
            clients[1].close();fixture.awaitState(online=1,connections=1,participants=2)
            clients[1]=join(1,false).also {c ->
                val resumed=ByteBuffer.wrap(through(c,opcode))
                assertEquals(3,resumed.get(18).toInt());assertEquals(1,resumed.getInt(19))
                assertEquals(if(reverse)192 else 72,resumed.getInt(10))
            }
            fixture.engine.submit(WorldCommand.AdvanceTo(10000))
            for(c in clients) {
                val ids=mutableSetOf<Int>()
                repeat(3) {
                    val moved=ByteBuffer.wrap(through(c,if(reverse)0x0424 else 0x0423))
                    if(!reverse)assertEquals(-1,moved.get(30).toInt())
                    assertEquals(completedDirection,moved.getFloat(14),0.00001f)
                    ids+=moved.getInt(10)
                }
                assertEquals(setOf(1,2,1010000),ids)
            }
            val done=savedStocks.outfits.getValue(1).activeShips!!.values.single {it.id==2}.tactical!!.motion
            assertNull(done.rotation);assertEquals(0f,done.velocity)
            assertEquals(if(reverse)0f else 1f,done.origin.position.x,0.00001f)
            assertEquals(if(reverse)1f else 0f,done.origin.position.y,0.00001f)
            val own=savedPlayers.getValue(fixture.account(0)).motion!!
            assertEquals(if(reverse)0f else 0.5f,own.origin.position.x,0.00001f)
            assertEquals(kotlin.math.atan2(kotlin.math.sin(target),kotlin.math.cos(target)).toFloat(),own.direction,0.00001f)

            clients[0].send(command(10000))
            for(c in clients)through(c,opcode)
            val stop=org.logh7.protocol.TacticalStopMessages.reply(
                org.logh7.protocol.TacticalStopMessages.Stop(10000,listOf(1010000)),0)
            clients[0].send(stop.copyOfRange(4,stop.size))
            assertEquals(0,ByteBuffer.wrap(through(clients[0],0x040a)).int)
            val peerStop=through(clients[1],0x040a)
            assertEquals(listOf(1010000),
                org.logh7.protocol.TacticalStopMessages.stop(peerStop.copyOfRange(4,peerStop.size)).units)
            val peerPose=through(clients[1],0x0400) { ByteBuffer.wrap(it).getInt(19)==1010000 }
            assertEquals(0,ByteBuffer.wrap(peerPose).getInt(10))
            assertNull(savedPlayers.getValue(fixture.account(0)).motion!!.rotation)
            assertTrue(savedStocks.outfits.getValue(1).activeShips!!.values.first().tactical!!.motion.rotation!=null)
        }
    }
}
