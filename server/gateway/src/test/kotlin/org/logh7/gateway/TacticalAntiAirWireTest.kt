package org.logh7.gateway

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class TacticalAntiAirWireTest {
    @TempDir lateinit var directory:Path
    @Test fun explicitAaUsesCommittedCommandQueueAndDispersesAircraftWithoutHullDamage()=exchange(true)
    @Test fun unmarkedArmTwoStillConsumesMissilesAndHitsTheHull()=exchange(false)
    private fun exchange(antiAir:Boolean)=runBlocking {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Battle",3)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val now=SessionRules.load().startSeconds
        fun motion(x:Float,y:Float)=TacticalMotion(2488,now,TacticalPose(TacticalPoint(x,y),0f),0f,0f,listOf(TacticalPoint(x,y)))
        var savedPlayers=mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2488,1000,1000,900,cpPaused=true,
            hull=FlagshipHull(0,now),motion=motion(-180f,-97f)),
            "load-001" to Navigator(1,10001,Power.ALLIANCE,2488,1000,1000,900,cpPaused=true,
                hull=FlagshipHull(89,now),motion=motion(-179f,-95f)))
        val carrier=ProductionUnits.definitions.first {it.ship && it.power==3 && it.name.startsWith("戦闘艇母艦")}.kind
        val stack=UnitStack(carrier)
        var stocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,3,0,100,100,100)),
            outfits=mapOf(1 to OutfitStock(1,3,OutfitKind.FLEET,1000,10,10001,UnitInventory(mapOf(stack to 1)),
                activeShips=ActiveShipUnits(listOf(ActiveShipUnit(99,stack,tactical=NpcTacticalState(motion(-180f,-95f),now,now)))),
                position=OutfitPosition(2488))),warehouses=mapOf(10 to OutfitWarehouse(10,3,OutfitKind.FLEET,1000,1)),
            deploymentInitialized=true,nextShipId=100,tacticalCommands=TacticalCommandState(
                air=mapOf(99 to TacticalAirWing(10001,2488,99,1010000,1,now+1000))))
        val nav=object:NavigationStore {override fun load()=savedPlayers;override fun save(players:Map<String,Navigator>){savedPlayers=players}}
        val stockStore=object:PlanetStockStore {override fun load()=stocks;override fun save(next:PlanetStocks){stocks=next}}
        val commit=WorldStateCommitter {p,s->savedPlayers=p.toMap();stocks=checkNotNull(s)}
        fun through(client:SyntheticClient,opcode:Int):ByteArray {
            repeat(400){val packet=client.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode)return packet}
            error("Missing $opcode")
        }
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=nav,stockStore=stockStore,
            worldStateCommitter=commit,powerByIndex={if(it==1)3 else 2}).use {fixture->
            val clients=(0..1).map {i->fixture.join(fixture.account(i),true,false).client.also {c->
                c.send(SyntheticClient.opcode(0x0f00));through(c,0x0f01)
                c.send(SyntheticClient.opcode(0x0f02));through(c,0x0f03)
            }}
            val before=stocks.outfits.getValue(1).activeShips!!.values.single()
            val command=ByteBuffer.allocate(25).putShort(0x406).putInt(0).putInt(0).putInt(10000).put(1)
                .putInt(1010000).put(2).put((if(antiAir)0x81 else 1).toByte()).putInt(99).array()
            clients[0].send(command)
            val ack=ByteBuffer.wrap(through(clients[0],0x406))
            assertEquals(0,ack.int);assertEquals(120,ack.getInt(10));assertEquals(1,ack.get(24).toInt())
            if(antiAir) {
                assertEquals(0x406,stocks.tacticalCommands.orders.single().command.opcode)
                assertNull(savedPlayers.getValue("load-000").fire)
            } else {
                assertTrue(stocks.tacticalCommands.orders.isEmpty())
                assertEquals(TacticalWeapon.MISSILE,savedPlayers.getValue("load-000").fire!!.pending!!.weapon)
            }
            fixture.engine.submit(WorldCommand.AdvanceTo(5000))
            withTimeout(5000){while(fixture.engine.snapshot().session!!.gameSeconds<now+120)delay(1)}
            val after=stocks.outfits.getValue(1).activeShips!!.values.single()
            if(antiAir) {
                assertTrue(stocks.tacticalCommands.air.isEmpty());assertEquals(before,after)
                assertEquals(10000,savedPlayers.getValue("load-000").supplies)
            } else {
                assertTrue(99 in stocks.tacticalCommands.air)
                assertTrue(after.boats<before.boats || after.damaged>before.damaged)
                assertEquals(9999,savedPlayers.getValue("load-000").supplies)
            }
        }
    }
}
