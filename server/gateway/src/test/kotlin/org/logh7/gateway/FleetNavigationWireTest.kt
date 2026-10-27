package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class FleetNavigationWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private inner class State:WorldStateCommitter {
        var players=mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=4,spotId=10490807,cpRecoveryAt=SessionRules.load().startSeconds))
        var stocks=ActiveShips.initialize(PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
            mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,10,10000,UnitInventory(mapOf(UnitStack(32) to 10)))),
            mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1049,1)),deploymentInitialized=true))
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?) {
            players=navigation.toMap();stocks=checkNotNull(inventory)
        }
        val nav=object:NavigationStore {
            override fun load()=players
            override fun save(players:Map<String,Navigator>) {this@State.players=players.toMap()}
        }
        val inventory=object:PlanetStockStore {
            override fun load()=stocks
            override fun save(stocks:PlanetStocks) {this@State.stocks=stocks}
        }
    }
    private fun through(client:SyntheticClient,opcode:Int) {
        repeat(300) {if(ByteBuffer.wrap(client.receive()).getShort(4).toInt()==opcode)return}
        error("Missing transaction end")
    }
    @Test fun encryptedDepartureSpaceRosterWarpAndRestartRetainFleetAffiliation() {
        val state=State()
        repeat(2) { round ->
            LoadFixture(directory.resolve("run-$round"),1,LoadMetrics(),galaxy,ticking=false,
                stockStore=state.inventory,navigationStore=state.nav,worldStateCommitter=state).use { fixture ->
                val client=fixture.join(fixture.account(0),true,false).client
                client.send(SyntheticClient.opcode(0x0f00));through(client,0x0f01)
                if(round==0) {
                    val depart=PlaceMessages.switched(PlaceMessages.SwitchMode(10000,59,5),0,0,0,5,0,0,true)
                    client.send(depart.copyOfRange(4,depart.size));through(client,0x0b0b)
                    assertEquals(OutfitPosition(2487),state.stocks.outfits.getValue(1).position)
                }
                client.send(ByteBuffer.allocate(11).putShort(0x032e).putInt(1).putInt(0).put(0).array())
                val party=ByteBuffer.wrap(client.expect(0x032f))
                assertEquals(1,party.getInt(6));assertEquals(0,party.getInt(10))
                if(round==0) {
                    val warp=NavigationMessages.accepted(NavigationMessages.Warp(10000,59,2488,0,5),0,0,0)
                    client.send(warp.copyOfRange(4,warp.size));through(client,0x0b0a)
                }
                client.send(ByteBuffer.allocate(8).putShort(0x032c).putInt(2488).put(2).put(0).array())
                assertEquals(1,ByteBuffer.wrap(client.expect(0x032d)).getShort(6).toInt())
                client.send(ByteBuffer.allocate(10).putShort(0x0330).putInt(1).putInt(0).array())
                val unit=ByteBuffer.wrap(client.expect(0x0331))
                assertEquals(5,unit.get(13).toInt());assertEquals(2488,unit.getInt(14))
                assertEquals(OutfitPosition(2488),state.stocks.outfits.getValue(1).position)
                assertEquals(2488,state.players.getValue("load-000").grid)
            }
        }
    }
}
