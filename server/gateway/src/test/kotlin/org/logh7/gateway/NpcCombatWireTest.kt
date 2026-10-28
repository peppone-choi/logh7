package org.logh7.gateway

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class NpcCombatWireTest {
    @TempDir lateinit var directory:Path
    @Test fun autonomousApproachAndShotsReachBothEncryptedClients()=encounter(false)
    @Test fun damagedAutonomousOutfitPreparesThenNotifiesBothClientsOfLegalExit()=encounter(true)
    private fun encounter(withdraw:Boolean)=runBlocking {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Battle",3)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val now=SessionRules.load().startSeconds
        var savedPlayers=(0..1).associate {i->
            val p=TacticalPoint(-180f,-95f+i*20)
            "load-00$i" to Navigator(1,10000+i,Power.ALLIANCE,2488,1000,1000,900,cpPaused=true,
                hull=FlagshipHull(89,now),motion=TacticalMotion(2488,now,TacticalPose(p,0f),0f,0f,listOf(p)))
        }
        val stack=UnitStack(32)
        var savedStocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100)),
            outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,units=UnitInventory(mapOf(stack to 1)),supplies=10000,
                activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack,if(withdraw)75 else 300))),position=OutfitPosition(2488))),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),deploymentInitialized=true,nextShipId=2)
        val nav=object:NavigationStore {override fun load()=savedPlayers;override fun save(players:Map<String,Navigator>){savedPlayers=players.toMap()}}
        val inventory=object:PlanetStockStore {override fun load()=savedStocks;override fun save(stocks:PlanetStocks){savedStocks=stocks}}
        val commit=WorldStateCommitter {p,s->savedPlayers=p.toMap();savedStocks=checkNotNull(s)}
        fun through(c:SyntheticClient,opcode:Int,predicate:(ByteArray)->Boolean={true}):ByteArray {
            repeat(500){val p=c.receive();if(ByteBuffer.wrap(p).getShort(4).toInt()==opcode && predicate(p))return p}
            error("Missing $opcode")
        }
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=nav,stockStore=inventory,
            worldStateCommitter=commit,powerByIndex={3},npcTactics=true).use {fixture->
            val clients=(0..1).map {i->fixture.join(fixture.account(i),true,false).client.also {c->
                c.send(SyntheticClient.opcode(0x0f00));through(c,0x0f01)
                c.send(SyntheticClient.opcode(0x0f02));through(c,0x0f03)
            }}
            for(c in clients) {
                val move=ByteBuffer.wrap(through(c,0x0400){ByteBuffer.wrap(it).getInt(14)==0})
                assertEquals(1,move.getInt(19));assertTrue(move.getFloat(39)>0)
            }
            if(withdraw) {
                fixture.engine.submit(WorldCommand.AdvanceTo(154999))
                withTimeout(5000){while(fixture.engine.snapshot().session!!.gameSeconds<now+3719)delay(1)}
                assertEquals(2488,fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).position!!.grid)
                fixture.engine.submit(WorldCommand.AdvanceTo(155000))
                withTimeout(5000){while(fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).position!!.grid!=2487)delay(1)}
                for(c in clients){val exit=ByteBuffer.wrap(through(c,0x0425));assertEquals(2487,exit.getInt(10));assertEquals(1,exit.getInt(21))}
            } else {
                fixture.engine.submit(WorldCommand.AdvanceTo(20000))
                withTimeout(5000){while(fixture.engine.snapshot().session!!.gameSeconds<now+480)delay(1)}
                for(c in clients)repeat(3) {
                    val hit=ByteBuffer.wrap(through(c,0x0426));assertEquals(1,hit.getInt(10));assertEquals(1010000,hit.getInt(16))
                }
                assertEquals(8200,fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).supplies)
                assertEquals(3,fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).activeShips!!.values.single().tactical!!.sequence)
            }
        }
    }
}
