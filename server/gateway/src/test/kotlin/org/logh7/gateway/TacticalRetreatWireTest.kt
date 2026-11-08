package org.logh7.gateway

import kotlinx.coroutines.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class TacticalRetreatWireTest {
    @TempDir lateinit var directory:Path
    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun retreatPublishesCommandRangeAndKeepsThePersonalTransition(regular:Boolean) = runBlocking {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Battle",3)),
            listOf(GalaxyBase(1049,2487,80,0,"Battle",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val pose=TacticalPose(TacticalPoint(-300f,0f),1f)
        var saved=(0..1).associate { i->"load-%03d".format(i) to
            Navigator(1,10000+i,Power.EMPIRE,2487,1000,1000,1000,cpPaused=true,
                energy=TacticalEnergy(listOf(0,50)+List(9){0}),
                motion=TacticalMotion(2487,0,pose,0f,1f,listOf(pose.position))) }
        val store=object:NavigationStore {
            override fun load()=saved
            override fun save(players:Map<String,Navigator>){saved=players.toMap()}
        }
        val stack=UnitStack(32)
        val unit=ActiveShipUnit(1,stack,tactical=NpcTacticalState(TacticalMotion(2487,0,pose,0f,1f,listOf(pose.position)),0,0))
        val enemy=UnitStack(119)
        var savedStocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,100,100)),
            outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,10,commanderId=10000,
                units=UnitInventory(mapOf(stack to 1)),activeShips=ActiveShipUnits(listOf(unit)),position=OutfitPosition(2487)),
                2 to OutfitStock(2,3,OutfitKind.FLEET,1049,20,units=UnitInventory(mapOf(enemy to 1)),
                    activeShips=ActiveShipUnits(listOf(ActiveShipUnit(2,enemy))),position=OutfitPosition(2487))),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1049,1),20 to OutfitWarehouse(20,3,OutfitKind.FLEET,1049,2)),
            deploymentInitialized=true,nextShipId=3)
        val stock=object:PlanetStockStore {
            override fun load()=savedStocks
            override fun save(stocks:PlanetStocks){savedStocks=stocks}
        }
        val commit=WorldStateCommitter {players,inventory->saved=players.toMap();savedStocks=checkNotNull(inventory)}
        fun through(client:SyntheticClient,op:Int):ByteArray {
            repeat(300){val p=client.receive();if(ByteBuffer.wrap(p).getShort(4).toInt()==op)return p}
            error("Missing $op")
        }
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=store,
            stockStore=if(regular)stock else null,worldStateCommitter=if(regular)commit else null).use { fixture->
            val first=fixture.join(fixture.account(0),true,false).client
            first.send(SyntheticClient.opcode(0x0f00));through(first,0x0f01)
            val second=fixture.join(fixture.account(1),true,false).client
            second.send(SyntheticClient.opcode(0x0f00));through(second,0x0f01)
            first.send(SyntheticClient.opcode(0x0f02))
            assertEquals(20f,ByteBuffer.wrap(through(first,0x033f)).getFloat(18))
            through(first,0x0f03)
            val packet=TacticalRetreatMessages.reply(TacticalRetreatMessages.Retreat(10000,listOf(if(regular)1 else 1010000)),0,0)
            first.send(packet.copyOfRange(4,packet.size))
            val accepted=ByteBuffer.wrap(through(first,0x0404))
            assertEquals(0,accepted.int);assertEquals(120,accepted.getInt(10))
            val range=ByteBuffer.wrap(through(first,0x033f))
            assertEquals(10000,range.getInt(8))
            assertEquals(if(regular)0f else 20f,range.getFloat(18))
            assertEquals(2,range.getShort(6).toInt())
            assertEquals(10001,range.getInt(63))
            first.send(SyntheticClient.opcode(0x0300));through(first,0x0301)
            val refreshed=ByteBuffer.wrap(through(first,0x033f))
            assertEquals(2,refreshed.getShort(6).toInt())
            assertEquals(10001,refreshed.getInt(63))
            if(regular) {
                val pending=savedStocks.outfits.getValue(1).activeShips!!.values.single().tactical!!.retreat
                assertEquals(2486,pending?.destination)
                return@use
            }
            val pending=saved.getValue(fixture.account(0)).retreat!!
            assertEquals(2486,pending.destination)
            first.send(SyntheticClient.opcode(0x0f02))
            assertEquals(20f,ByteBuffer.wrap(through(first,0x033f)).getFloat(18))
            through(first,0x0f03)
            through(first,0x0404) // Preparation is restored rather than charged/submitted again.
            fixture.engine.submit(WorldCommand.AdvanceTo(154999))
            withTimeout(5000){while(fixture.engine.snapshot().session!!.gameSeconds<3719)delay(1)}
            assertEquals(2487,saved.getValue(fixture.account(0)).grid)
            fixture.engine.submit(WorldCommand.AdvanceTo(155000))
            withTimeout(5000){while(fixture.engine.snapshot().navigators[fixture.account(0)]?.grid!=2486)delay(1)}
            // A position poll may beat the periodic world refresh at the exact completion tick.
            first.send(ByteBuffer.allocate(8).putShort(0x0348).putShort(1).putInt(1010000).array())
            val notification=ByteBuffer.wrap(through(first,0x0425))
            assertEquals(2486,notification.getInt(10));assertEquals(1010000,notification.getInt(21))
            val exit=ByteBuffer.wrap(through(first,0x0f1f))
            assertEquals(0,exit.get(6).toInt());assertEquals(2486,exit.getInt(7))
            first.send(SyntheticClient.opcode(0x0f02))
            val grid=ByteBuffer.wrap(through(first,0x0317))
            assertEquals(2486,grid.getShort(6).toInt());assertEquals(0,grid.get(8).toInt())
            through(first,0x0f03)
            first.send(ByteBuffer.allocate(8).putShort(0x0348).putShort(1).putInt(1010000).array())
            assertEquals(0,ByteBuffer.wrap(through(first,0x0349)).getShort(6).toInt())
            first.send(SyntheticClient.opcode(0x0300));through(first,0x0301)
            val exited=saved.getValue(fixture.account(0))
            assertFalse(exited.cpPaused);assertNull(exited.retreat);assertNull(exited.motion)
            assertEquals(1000,exited.mcp);assertEquals(1000,exited.cruise)
            assertEquals(2487,saved.getValue(fixture.account(1)).grid)
        }
    }
}
