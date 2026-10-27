package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class SupplementWireTest {
    @TempDir lateinit var directory: Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private class State: WorldStateCommitter {
        var saves=0
        var players=mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=4,cpRecoveryAt=SessionRules.load().startSeconds))
        var stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
            mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,101,10000,UnitInventory(mapOf(UnitStack(32) to 1)),
                activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32),290))))),
            mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1049,1,
                UnitInventory(mapOf(UnitStack(32) to 1),mapOf(UnitStack(3) to 100)),supplies=1000)),
            deploymentInitialized=true,nextShipId=2)
        override fun saveWorld(navigation: Map<String,Navigator>,inventory: PlanetStocks?) {
            saves++;players=navigation.toMap();stocks=checkNotNull(inventory)
        }
        val navigationStore=object: NavigationStore {
            override fun load()=players
            override fun save(players: Map<String,Navigator>){this@State.players=players.toMap()}
        }
        val stockStore=object: PlanetStockStore {
            override fun load()=stocks
            override fun save(stocks: PlanetStocks){this@State.stocks=stocks}
        }
    }
    private fun fixture(s: State,name: String)=LoadFixture(directory.resolve(name),1,LoadMetrics(),galaxy,
        ticking=false,stockStore=s.stockStore,navigationStore=s.navigationStore,worldStateCommitter=s)
    private fun start(c: SyntheticClient) {
        c.send(SyntheticClient.opcode(0x0f00))
        repeat(300){if(ByteBuffer.wrap(c.receive()).getShort(4).toInt()==0x0f01)return}
        error("Game did not start")
    }
    private fun query(mode: Int=1,supplies: Int=100,id: Int=1)=SupplementMessages.Query(
        LogisticsMessages.Header(-1,10000,Int.MAX_VALUE,Int.MAX_VALUE),mode,1049,1,0,
        listOf(SupplementMessages.Units(32,listOf(id))),listOf(LogisticsMessages.TroopMove(1007,3,32767)),supplies,
        LogisticsMessages.Inventory(listOf(LogisticsMessages.ShipStock(32,255,65535)),emptyList()),Int.MAX_VALUE,Int.MAX_VALUE,Int.MAX_VALUE)
    private fun receive(c: SyntheticClient,mode: Int=1,accepted: Boolean=true): SupplementMessages.Query {
        val raw=c.receive();assertEquals(0x0c05,ByteBuffer.wrap(raw).getShort(4).toInt())
        assertEquals(if(accepted)0 else 1,ByteBuffer.wrap(raw).int)
        val result=SupplementMessages.query(raw.copyOfRange(4,raw.size))
        if(mode==1){c.expect(0x0356);if(accepted){c.expect(0x032f);c.expect(0x0327)}}
        return result
    }
    @Test fun authenticatedPreviewAndCommitIgnoreForgedOutcomesAndReconnectDoesNotRefillOrRecharge() {
        val state=State()
        fixture(state,"first").use {server ->
            val c=server.join(server.account(0),true,false).client;start(c)
            c.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(34).putShort(0).also {it.putShort(23,32)}.array())
            c.expect(0x1200);val directory=ByteBuffer.wrap(c.expect(0x1207))
            assertEquals(1,directory.getShort(6).toInt());assertEquals(1,directory.getInt(8))
            assertEquals(32,directory.get(12).toInt());assertEquals(10,directory.getShort(13).toInt());c.expect(0x1201)
            c.send(SupplementMessages.encode(query(mode=0)));val preview=receive(c,mode=0)
            assertEquals(1000,preview.header.mcp);assertEquals(300,preview.result.ships.single().boats)
            assertEquals(0,state.saves);assertEquals(290,state.stocks.outfits.getValue(1).activeShips!!.values.single().boats)
            c.send(SupplementMessages.encode(query()));val result=receive(c)
            assertEquals(840,result.header.mcp);assertEquals(1000,result.header.pcp)
            assertEquals(100,result.resultSupplies);assertTrue(result.troops.all {it.kind==1003})
            assertEquals(300,state.stocks.outfits.getValue(1).activeShips!!.values.single().boats)
            assertEquals(290,state.stocks.warehouses.getValue(101).spareShips.values[UnitStack(32)])
            val saved=state.saves
            c.send(SupplementMessages.encode(query(supplies=0)));assertEquals(840,receive(c).header.mcp)
            assertEquals(saved,state.saves)
            c.send(SupplementMessages.encode(query(id=999)));receive(c,accepted=false);assertEquals(saved,state.saves)
        }
        fixture(state,"reopen").use {server ->
            val c=server.join(server.account(0),true,false).client;start(c)
            c.send(SupplementMessages.encode(query(mode=0,supplies=0)));val result=receive(c,mode=0)
            assertEquals(840,result.header.mcp);assertEquals(300,result.result.ships.single().boats)
            assertEquals(100,result.resultSupplies);assertEquals(290,state.stocks.warehouses.getValue(101).spareShips.values[UnitStack(32)])
            c.send(SupplementMessages.encode(query(supplies=-50)));val returned=receive(c)
            assertEquals(680,returned.header.mcp);assertEquals(50,returned.resultSupplies)
            assertEquals(950,state.stocks.warehouses.getValue(101).supplies)
        }
    }
}
