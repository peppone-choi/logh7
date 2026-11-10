package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.ChatMessages
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
    private fun fixture(state:State, folder:String, world:Galaxy=galaxy)=LoadFixture(directory.resolve(folder),1,LoadMetrics(),world,
        ticking=false,stockStore=state.stockStore,navigationStore=state.navigationStore,worldStateCommitter=state)
    private fun start(client:SyntheticClient) {
        client.send(SyntheticClient.opcode(0x0f00))
        repeat(300) {if(ByteBuffer.wrap(client.receive()).getShort(4).toInt()==0x0f01)return}
        error("Game did not start")
    }
    private fun request(from:Int=1,to:Int=2)=ByteBuffer.allocate(32).putShort(0x0c01)
        .putInt(-1).putInt(10000).putInt(Int.MAX_VALUE).putInt(Int.MAX_VALUE)
        .putInt(from).putInt(to).putInt(Int.MAX_VALUE).put(0).put(0).array()
    private fun acknowledge(client:SyntheticClient,accepted:Boolean,notice:String,mcp:Int=840) {
        val packet=ByteBuffer.wrap(client.receive())
        assertEquals(0x0c01,packet.getShort(4).toInt())
        assertEquals(if(accepted)0 else 1,packet.int)
        assertEquals(1000,packet.getInt(14));assertEquals(mcp,packet.getInt(18))
        if(accepted) assertEquals(1,packet.getInt(22))
        assertEquals(if(accepted)2 else 0,packet.getInt(26))
        assertEquals(if(accepted)1000 else 0,packet.getInt(30))
        client.expect(0x0356)
        if(accepted) {client.expect(0x032f);client.expect(0x032f)}
        val message=client.expect(0x0f1d)
        assertEquals("[보급] $notice",ChatMessages.spot(message.copyOfRange(4,message.size)).text)
    }
    private fun unitSupplies(client:SyntheticClient,id:Int):Int {
        client.send(ByteBuffer.allocate(10).putShort(0x0330).putInt(id).putInt(1049).array())
        val packet=ByteBuffer.wrap(client.expect(0x0331))
        assertEquals(1,packet.get(6).toInt());assertEquals(id,packet.getInt(7))
        return packet.getInt(37)
    }
    @Test fun optedInNativeDirectoryQuotesThenRejectsAnUnmatchedConfirmation() {
        val state=State()
        fixture(state,"quote").use { server ->
            val client=server.join(server.account(0),true,false).client;start(client)
            client.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(14).putShort(124)
                .putShort(0x5351).putShort(99).also {it.putInt(19,2487)}.array())
            val quote=ByteBuffer.wrap(client.expect(0x1200))
            assertEquals(0x7f00,quote.getShort(10).toInt())
            assertEquals(62,quote.getShort(12).toInt())
            assertEquals(99,quote.getShort(14).toInt())
            assertEquals(0,quote.getShort(16).toInt())
            assertEquals(1,quote.getInt(18));assertEquals(1,quote.get(22).toInt())
            assertEquals(2,quote.getInt(23));assertEquals(1000,quote.getInt(29))
            val echo=ByteBuffer.wrap(client.expect(0x1200))
            assertEquals(14,echo.getShort(10).toInt());assertEquals(0,echo.getInt(14))
            client.expect(0x120b);client.expect(0x1201)
            val saves=state.saves
            fun confirmed(amount:Int)=request().also { ByteBuffer.wrap(it).putInt(10,0x53510001).putInt(26,amount) }
            client.send(confirmed(999));acknowledge(client,false,"이전량·CP 변경 · 다시 조회",1000)
            assertEquals(saves,state.saves)
            client.send(confirmed(1000));acknowledge(client,true,"1000 이전 · MCP -160")
        }
    }
    @Test fun lostHomeRemovesVisitingTransportFromDirectoryAndRejectsTheOldQuote() {
        val state=State()
        val home=GalaxyBase(1050,2487,80,0,"Forward",0,20f,86400,0,0f,2f)
        val world=Galaxy(galaxy.systems,galaxy.bases+home,Power.entries.associateWith {2487})
        val source=state.stocks.outfits.getValue(1)
        state.stocks=state.stocks.copy(
            bases=state.stocks.bases+(1050 to PlanetStock(1050,2,0,0,0,0)),
            outfits=state.stocks.outfits+(1 to source.copy(baseId=1050,position=OutfitPosition(2487,1049))),
            warehouses=state.stocks.warehouses+(101 to state.stocks.warehouses.getValue(101).copy(baseId=1050)))
        fun quote(client:SyntheticClient) {
            client.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(14).putShort(124)
                .putShort(0x5351).putShort(99).also {it.putInt(19,2487)}.array())
        }
        fixture(state,"home-before",world).use { server ->
            val client=server.join(server.account(0),true,false).client
            start(client)
            quote(client)
            val response=ByteBuffer.wrap(client.expect(0x1200))
            assertEquals(0x7f00,response.getShort(10).toInt())
            assertEquals(1000,response.getInt(29))
            client.expect(0x1200);client.expect(0x120b);client.expect(0x1201)
        }
        state.stocks=state.stocks.copy(bases=state.stocks.bases+(1050 to
            state.stocks.bases.getValue(1050).copy(owner=Allegiance.Country(Power.ALLIANCE.country))))
        fixture(state,"home-captured",world).use { server ->
            val client=server.join(server.account(0),true,false).client
            start(client)
            quote(client)
            assertEquals(14,ByteBuffer.wrap(client.expect(0x1200)).getShort(10).toInt())
            assertEquals(0,ByteBuffer.wrap(client.expect(0x120b)).getShort(6).toInt())
            client.expect(0x1201)
            val saves=state.saves
            client.send(request().also {ByteBuffer.wrap(it).putInt(10,0x53510001).putInt(26,1000)})
            acknowledge(client,false,"대상·수송 지휘권 확인",1000)
            assertEquals(saves,state.saves)
            assertEquals(1000,state.stocks.outfits.getValue(1).supplies)
            assertEquals(0,state.stocks.outfits.getValue(2).supplies)
        }
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
            client.send(request(from=0));acknowledge(client,true,"1000 이전 · MCP -160")
            assertEquals(0,state.stocks.outfits.getValue(1).supplies)
            assertEquals(1000,state.stocks.outfits.getValue(2).supplies)
            assertEquals(0,unitSupplies(client,1));assertEquals(1000,unitSupplies(client,2))
            assertEquals(0,unitSupplies(client,3))
            val saves=state.saves
            client.send(request());acknowledge(client,true,"보급원 물자 없음 · 비용 0")
            assertEquals(saves,state.saves);assertEquals(840,state.players.getValue("load-000").mcp)
            client.send(request(to=999));acknowledge(client,false,"대상·수송 지휘권 확인")
            assertEquals(saves,state.saves)
        }
        fixture(state,"reopen").use { server ->
            val client=server.join(server.account(0),true,false).client;start(client)
            assertEquals(1000,unitSupplies(client,2));assertEquals(0,unitSupplies(client,1))
            assertEquals(840,state.players.getValue("load-000").mcp)
        }
    }

    @Test fun cargoAboardLimitsBothTheDirectoryQuoteAndConfirmedSupply() {
        val state=State()
        val target=state.stocks.outfits.getValue(2).copy(kind=OutfitKind.TRANSPORT,commanderId=0)
        val capacity=OutfitSupplies.capacity(target)
        val ships=target.activeShips!!.values
        val stocked=OutfitSupplies.withBalances(target,ships.associate {
            it.id to (OutfitSupplies.unitCapacity(it)-if(it==ships.last())100 else 0)
        })
        val cargo=TransportPackage(1,CountryId(2),1049,1050,90,carrier=target.id,carrierBoats=ships.sumOf {it.boats})
        state.stocks=state.stocks.copy(outfits=state.stocks.outfits+(target.id to stocked),
            warehouses=state.stocks.warehouses+(102 to state.stocks.warehouses.getValue(102).copy(kind=OutfitKind.TRANSPORT)),
            strategic=state.stocks.strategic.copy(transports=listOf(cargo)))
        fixture(state,"cargo-capacity").use { server ->
            val client=server.join(server.account(0),true,false).client
            start(client)
            client.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(14).putShort(124)
                .putShort(0x5351).putShort(99).also {it.putInt(19,2487)}.array())
            assertEquals(10,ByteBuffer.wrap(client.expect(0x1200)).getInt(29))
            client.expect(0x1200);client.expect(0x120b);client.expect(0x1201)
            client.send(request().also {ByteBuffer.wrap(it).putInt(10,0x53510001).putInt(26,10)})
            val ack=ByteBuffer.wrap(client.expect(0x0c01))
            assertEquals(0,ack.int)
            assertEquals(capacity-90,ack.getInt(30))
            assertEquals(990,state.stocks.outfits.getValue(1).supplies)
            assertEquals(capacity-90,state.stocks.outfits.getValue(2).supplies)
            assertEquals(listOf(cargo),state.stocks.strategic.transports)
        }
    }
}
