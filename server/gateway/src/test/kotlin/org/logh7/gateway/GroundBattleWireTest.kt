package org.logh7.gateway

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

/** Actual encrypted peers receive only committed ground casualties. Author: 최병호. */
class GroundBattleWireTest {
    @TempDir lateinit var directory:Path
    @Test fun automaticExchangeUpdatesBothNativeBaseBarsAndReloadKeepsPartialStrength()=runBlocking {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,0f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val now=SessionRules.load().startSeconds
        var savedPlayers=(0..1).associate {i->"load-00$i" to Navigator(1,10000+i,Power.EMPIRE,2487,1000,1000,900,
            cpPaused=true,hull=FlagshipHull(0,now))}
        val outfits=(0..1).associate {i->
            val roster=ActiveGroundUnits(listOf(ActiveGroundUnit(i+1,UnitStack(if(i==0)0 else 4),base=1000)))
            i+1 to OutfitStock(i+1,i+2,OutfitKind.GROUND,1000,10+i,units=UnitInventory(troops=roster.quantities()),activeGround=roster)
        }
        var savedStocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0)),outfits,
            (0..1).associate {10+it to OutfitWarehouse(10+it,2+it,OutfitKind.PATROL,1000)},deploymentInitialized=true,nextShipId=3)
        val nav=object:NavigationStore {override fun load()=savedPlayers;override fun save(players:Map<String,Navigator>){savedPlayers=players}}
        val stock=object:PlanetStockStore {override fun load()=savedStocks;override fun save(stocks:PlanetStocks){savedStocks=stocks}}
        val commit=WorldStateCommitter {p,s->savedPlayers=p.toMap();savedStocks=checkNotNull(s)}
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,stockStore=stock,navigationStore=nav,worldStateCommitter=commit).use {fixture->
            fun through(c:SyntheticClient,opcode:Int):ByteArray {
                repeat(300){val raw=c.receive();if(ByteBuffer.wrap(raw).getShort(4).toInt()==opcode)return raw}
                error("Missing opcode $opcode")
            }
            fun join(i:Int,create:Boolean):SyntheticClient {
                val c=fixture.join(fixture.account(i),create,false).client
                c.send(SyntheticClient.opcode(0x0f00));through(c,0x0f01)
                c.send(SyntheticClient.opcode(0x0f02));through(c,0x0f03);return c
            }
            val clients=listOf(join(0,true),join(1,true))
            assertEquals(1000,savedStocks.outfits.getValue(1).activeGround!!.values.single().strength)
            fixture.engine.submit(WorldCommand.AdvanceTo(5000))
            withTimeout(5000){while(fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).activeGround!!.values.single().strength==1000)delay(1)}
            for(c in clients)assertEquals(800,ByteBuffer.wrap(through(c,0x042a)).getShort(14).toInt())
            assertEquals(800,savedStocks.outfits.getValue(2).activeGround!!.values.single().strength)
            clients[1].close();fixture.awaitState(online=1,connections=1,participants=2)
            val reopened=join(1,false)
            reopened.send(ByteBuffer.allocate(7).putShort(0x0344).put(1).putInt(1000).array())
            assertEquals(800,ByteBuffer.wrap(through(reopened,0x0345)).getShort(33).toInt())
            assertEquals(now+120,savedStocks.groundBattles[1000])
            reopened.close();clients[0].close()
        }
    }
}
