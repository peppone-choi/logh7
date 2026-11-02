package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class CompletenessSupplyWireTest {
    @TempDir lateinit var directory: Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private class State : WorldStateCommitter {
        var saves=0
        var players=mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=4,cpRecoveryAt=SessionRules.load().startSeconds))
        var stocks=ActiveShips.initialize(PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
            outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.TRANSPORT,1049,101,10000,
                UnitInventory(mapOf(UnitStack(32) to 1)),supplies=1000),
                2 to OutfitStock(2,2,OutfitKind.FLEET,1049,102,10000,UnitInventory(mapOf(UnitStack(32) to 2)))),
            warehouses=mapOf(101 to OutfitWarehouse(101,2,OutfitKind.TRANSPORT,1049,1),
                102 to OutfitWarehouse(102,2,OutfitKind.FLEET,1049,2)),deploymentInitialized=true))
        override fun saveWorld(navigation: Map<String,Navigator>, inventory: PlanetStocks?) {
            saves++;players=navigation.toMap();stocks=checkNotNull(inventory)
        }
        val navigationStore=object:NavigationStore {
            override fun load()=players
            override fun save(players:Map<String,Navigator>) {this@State.players=players.toMap()}
        }
        val stockStore=object:PlanetStockStore {
            override fun load()=stocks
            override fun save(stocks:PlanetStocks) {this@State.stocks=stocks}
        }
    }
    private fun fixture(state:State, folder:String)=LoadFixture(directory.resolve(folder),1,LoadMetrics(),galaxy,
        ticking=false,stockStore=state.stockStore,navigationStore=state.navigationStore,worldStateCommitter=state)
    private fun start(client:SyntheticClient) {
        client.send(SyntheticClient.opcode(0x0f00))
        repeat(300) {if(ByteBuffer.wrap(client.receive()).getShort(4).toInt()==0x0f01)return}
        error("Game did not start")
    }
    private fun request(from:Int=1,to:Int=2)=ByteBuffer.allocate(32).putShort(0x0c01)
        .putInt(-1).putInt(10000).putInt(Int.MAX_VALUE).putInt(Int.MAX_VALUE)
        .putInt(from).putInt(to).putInt(Int.MAX_VALUE).put(0).put(0).array()
    private fun acknowledge(client:SyntheticClient,accepted:Boolean) {
        val packet=ByteBuffer.wrap(client.receive())
        assertEquals(0x0c01,packet.getShort(4).toInt())
        assertEquals(if(accepted)0 else 1,packet.int)
        assertEquals(1000,packet.getInt(14));assertEquals(840,packet.getInt(18))
        assertEquals(if(accepted)2 else 0,packet.getInt(26))
        assertEquals(if(accepted)1000 else 0,packet.getInt(30))
        client.expect(0x0356)
        if(accepted) {client.expect(0x032f);client.expect(0x032f)}
    }
    private fun unitSupplies(client:SyntheticClient,id:Int):Int {
        client.send(ByteBuffer.allocate(10).putShort(0x0330).putInt(id).putInt(1049).array())
        val packet=ByteBuffer.wrap(client.expect(0x0331))
        assertEquals(1,packet.get(6).toInt());assertEquals(id,packet.getInt(7))
        return packet.getInt(37)
    }
    @Test fun encryptedTransferIgnoresForgedOutcomesAndQueriesStayConsistentAfterReconnect() {
        val state=State()
        val target=state.stocks.outfits.getValue(2)
        state.stocks=state.stocks.copy(outfits=state.stocks.outfits+(2 to target.copy(activeShips=ActiveShipUnits(
            target.activeShips!!.values.mapIndexed {index,u->if(index==0)u.copy(boats=150) else u}))))
        fixture(state,"first").use { server ->
            val client=server.join(server.account(0),true,false).client;start(client)
            client.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(14).putShort(124)
                .also {it.putInt(19,2487)}.array())
            client.expect(0x1200)
            val directory=ByteBuffer.wrap(client.expect(0x120b))
            assertEquals(1,directory.getShort(6).toInt());assertEquals(0,directory.getInt(8))
            assertEquals(450*760,directory.getInt(12))
            assertEquals(2,directory.getInt(16));client.expect(0x1201)
            client.send(request());acknowledge(client,true)
            assertEquals(0,state.stocks.outfits.getValue(1).supplies)
            assertEquals(1000,state.stocks.outfits.getValue(2).supplies)
            assertEquals(0,unitSupplies(client,1));assertEquals(1000,unitSupplies(client,2))
            assertEquals(0,unitSupplies(client,3))
            val saves=state.saves
            client.send(request());acknowledge(client,true)
            assertEquals(saves,state.saves);assertEquals(840,state.players.getValue("load-000").mcp)
            client.send(request(to=999));acknowledge(client,false)
            assertEquals(saves,state.saves)
        }
        fixture(state,"reopen").use { server ->
            val client=server.join(server.account(0),true,false).client;start(client)
            assertEquals(1000,unitSupplies(client,2));assertEquals(0,unitSupplies(client,1))
            assertEquals(840,state.players.getValue("load-000").mcp)
        }
    }
}
