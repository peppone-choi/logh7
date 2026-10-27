package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

/** Regression for a second player's strategy presence replacing the native battlefield. Author: 최병호. */
class TacticalEntryWireTest {
    @TempDir lateinit var directory:Path
    @Test fun peerJoinPreservesAllBattlefieldDefinitionsAndQueriesStayWithinGrid() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Battle",3)),
            listOf(GalaxyBase(1049,2487,80,0,"Battle",0,20f,86400,0,90f,2f)),Power.entries.associateWith {2487})
        var savedPlayers=(0..1).associate {index->"load-%03d".format(index) to
            Navigator(1,10000+index,Power.EMPIRE,2487,1000,1000,1000,cpRecoveryAt=SessionRules.load().startSeconds)}
        var savedStocks=ActiveShips.initialize(PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,3,0,100,200,300)),
            mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,10,10000,UnitInventory(mapOf(UnitStack(32) to 10)),
                aideId=10001,position=OutfitPosition(2487)),
                2 to OutfitStock(2,3,OutfitKind.FLEET,1049,11,500,UnitInventory(mapOf(UnitStack(119) to 10)),
                    position=OutfitPosition(2487))),
            mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1049,1),
                11 to OutfitWarehouse(11,3,OutfitKind.FLEET,1049,2)),deploymentInitialized=true))
        val nav=object:NavigationStore {
            override fun load()=savedPlayers
            override fun save(players:Map<String,Navigator>){savedPlayers=players.toMap()}
        }
        val inventory=object:PlanetStockStore {
            override fun load()=savedStocks
            override fun save(stocks:PlanetStocks){savedStocks=stocks}
        }
        val committer=object:WorldStateCommitter {
            override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?){
                savedPlayers=navigation.toMap();savedStocks=checkNotNull(inventory)
            }
        }
        fun through(client:SyntheticClient,opcode:Int):ByteArray {
            repeat(300){val bytes=client.receive();if(ByteBuffer.wrap(bytes).getShort(4).toInt()==opcode)return bytes}
            error("Missing opcode $opcode")
        }
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,stockStore=inventory,navigationStore=nav,
            worldStateCommitter=committer).use {fixture->
            val first=fixture.join(fixture.account(0),true,false).client
            first.send(SyntheticClient.opcode(0x0f00));through(first,0x0f01)
            assertTrue(savedPlayers.getValue(fixture.account(0)).cpPaused)
            val second=fixture.join(fixture.account(1),true,false).client
            second.send(SyntheticClient.opcode(0x0f00));through(second,0x0f01)
            val transition=ByteBuffer.wrap(through(first,0x0f1f))
            assertEquals(1,transition.get(6).toInt());assertEquals(2487,transition.getInt(7))
            first.send(SyntheticClient.opcode(0x0f02))
            // Initialization first sends the viewer's individual definition, followed by
            // the complete battlefield definition before the 0f03 acknowledgement.
            var definitions=ByteBuffer.wrap(through(first,0x0325))
            while(definitions.getShort(6).toInt()==1) definitions=ByteBuffer.wrap(through(first,0x0325))
            assertEquals(22,definitions.getShort(6).toInt())
            val ids=(0 until 22).map {definitions.getInt(8+it*42)}
            assertTrue(ids.containsAll(listOf(1010000,1010001)))
            val initialStates=ByteBuffer.wrap(through(first,0x033b))
            assertEquals(22,initialStates.getShort(6).toInt())
            // Native 004c1d20 imports the first TWO wire coordinates into its map plane.
            // Keeping the row spacing in wire z collapsed each faction onto a line.
            val locations=(0 until 22).map { initialStates.getFloat(18+47*it) to initialStates.getFloat(22+47*it) }
            assertEquals(22,locations.distinct().size)
            assertTrue(locations.map {it.second}.distinct().size>1)
            assertTrue((0 until 22).all {initialStates.getFloat(26+47*it)==0f})
            through(first,0x0f03)
            first.send(ByteBuffer.allocate(12).putShort(0x0348).putShort(2).putInt(ids.first()).putInt(Int.MAX_VALUE).array())
            val positions=ByteBuffer.wrap(through(first,0x0349))
            assertEquals(1,positions.getShort(6).toInt());assertEquals(ids.first(),positions.getInt(8))
            first.send(ByteBuffer.allocate(3).putShort(0x034a).put(0).array())
            assertEquals(7,through(first,0x034b).size)
            first.send(ByteBuffer.allocate(7).putShort(0x034a).put(1).putInt(1049).array())
            val basePosition=ByteBuffer.wrap(through(first,0x034b))
            assertEquals(1049,basePosition.getInt(7))
            assertEquals(0f,basePosition.getFloat(11),0.0001f)
            assertEquals(20000f,basePosition.getFloat(15));assertEquals(0f,basePosition.getFloat(19))
            val control=org.logh7.protocol.TacticsMessages.Control(10000,1010000,listOf(40,0,20,60,20)+List(6){20})
            val request=org.logh7.protocol.TacticsMessages.controlReply(control,0,0)
            first.send(request.copyOfRange(4,request.size))
            val controlled=ByteBuffer.wrap(through(first,0x040c))
            assertEquals(0,controlled.getInt(0));assertEquals(10000,controlled.getInt(14))
            assertEquals(TacticalEnergy(control.energy),savedPlayers.getValue(fixture.account(0)).energy)
            val move=org.logh7.protocol.TacticalMoveMessages.Move(10000,listOf(
                org.logh7.protocol.TacticalMoveMessages.Unit(1010000,0f,
                    org.logh7.protocol.TacticalMoveMessages.Point(9999f,9999f,0f))),0.4f,1f,
                listOf(org.logh7.protocol.TacticalMoveMessages.Point(800f,0f,0f)))
            fun sendMove(command:org.logh7.protocol.TacticalMoveMessages.Move) {
                val packet=org.logh7.protocol.TacticalMoveMessages.reply(command,0,0)
                first.send(packet.copyOfRange(4,packet.size))
            }
            sendMove(move)
            val moved=through(first,0x0400)
            val authoritative=org.logh7.protocol.TacticalMoveMessages.move(moved.copyOfRange(4,moved.size))
            val ownIndex=ids.indexOf(1010000)
            assertEquals(locations[ownIndex].first,authoritative.units.single().position.x)
            assertEquals(locations[ownIndex].second,authoritative.units.single().position.y)
            assertEquals(5,ByteBuffer.wrap(moved).getInt(10))
            assertContentEquals(moved,through(second,0x0400))
            val savedMotion=savedPlayers.getValue(fixture.account(0)).motion
            assertNotNull(savedMotion);assertEquals(1000,savedPlayers.getValue(fixture.account(0)).mcp)
            sendMove(move.copy(units=listOf(move.units.single().copy(id=1010001))))
            assertEquals(1,ByteBuffer.wrap(through(first,0x0400)).getInt(0))
            assertEquals(savedMotion,savedPlayers.getValue(fixture.account(0)).motion)
            sendMove(move.copy(velocity=0.41f))
            assertEquals(1,ByteBuffer.wrap(through(first,0x0400)).getInt(0))
            assertEquals(savedMotion,savedPlayers.getValue(fixture.account(0)).motion)
            first.send(SyntheticClient.opcode(0x0f02))
            val corps=ByteBuffer.wrap(through(first,0x033f))
            assertEquals(10000,corps.getInt(8));assertEquals(control.energy,(24..34).map {corps.get(it).toInt()})
            through(first,0x0f03)
            assertContentEquals(moved,through(first,0x0400))
        }
    }
}
