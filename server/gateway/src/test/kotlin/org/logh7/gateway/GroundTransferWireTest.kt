package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class GroundTransferWireTest {
    @TempDir lateinit var directory: Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f),GalaxyBase(1050,2487,80,0,"Away",0,30f,86400,0,0f,2f)),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private class State: WorldStateCommitter {
        var saves=0
        var players=mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=4,cpRecoveryAt=SessionRules.load().startSeconds))
        private val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32))))
        private val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(2,UnitStack(0),base=1049)))
        var stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300),1050 to PlanetStock(1050,2,0,0,0,0)),
            mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,101,10000,UnitInventory(ships.quantities(),troops.quantities()),
                activeShips=ships,activeGround=troops)),mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1049,1)),
            deploymentInitialized=true,nextShipId=3)
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
    private fun query(action:GroundTransferMessages.Action=GroundTransferMessages.Action.LOAD,values:List<Long> = emptyList(),outfit:Int=1):ByteArray =
        ByteBuffer.allocate(23+values.size*4).putShort(action.opcode.toShort()).putInt(-1).putInt(10000)
            .putInt(Int.MAX_VALUE).putInt(Int.MAX_VALUE).putInt(outfit).put(values.size.toByte()).also {b->values.forEach {b.putInt(it.toInt())}}.array()
    private fun receive(c:SyntheticClient,opcode:Int=0x0b05,accepted:Boolean=true,refresh:Boolean=true):Int {
        val raw=c.receive();val b=ByteBuffer.wrap(raw)
        assertEquals(opcode,b.getShort(4).toInt() and 65535)
        assertEquals(if(accepted)0 else 1,b.getInt(0))
        if(refresh){c.expect(0x0356);if(accepted)c.expect(0x032f)}
        return b.getInt(18)
    }
    @Test fun wholeOutfitIgnoresForgedBalancesRejectsOpaqueValuesAndSurvivesReconnection() {
        val state=State()
        fixture(state,"first").use {server->
            val c=server.join(server.account(0),true,false).client;start(c)
            c.send(query(values=listOf(2)));assertEquals(1000,receive(c,accepted=false,refresh=false));assertEquals(0,state.saves)
            c.send(query(outfit=999));assertEquals(1000,receive(c,accepted=false));assertEquals(0,state.saves)
            c.send(query());assertEquals(920,receive(c));assertEquals(1,state.saves)
            assertEquals(1,state.stocks.outfits.getValue(1).activeGround!!.values.single().carrier)
            c.send(query());assertEquals(920,receive(c));assertEquals(1,state.saves)
        }
        fixture(state,"reopen").use {server->
            val c=server.join(server.account(0),true,false).client;start(c)
            c.send(query());assertEquals(920,receive(c));assertEquals(1,state.saves)
            c.send(query(GroundTransferMessages.Action.UNLOAD));assertEquals(840,receive(c,0x0b04))
            val troop=state.stocks.outfits.getValue(1).activeGround!!.values.single()
            assertEquals(2,troop.id);assertEquals(1049,troop.base);assertEquals(0,troop.carrier);assertEquals(3,state.stocks.nextShipId)
            c.send(query(GroundTransferMessages.Action.UNLOAD));assertEquals(840,receive(c,0x0b04));assertEquals(2,state.saves)
        }
    }
    @Test fun commandAndRosterRefreshUseCurrentStationInsteadOfDepot() {
        val state=State()
        state.players=state.players.mapValues {it.value.copy(baseId=1050)}
        val outfit=state.stocks.outfits.getValue(1)
        state.stocks=state.stocks.copy(outfits=state.stocks.outfits+(1 to outfit.copy(position=OutfitPosition(2487,1050),
            activeGround=ActiveGroundUnits(outfit.activeGround!!.values.map {it.copy(base=1050)}))))
        fixture(state,"away").use {server->
            val c=server.join(server.account(0),true,false).client;start(c)
            c.send(query());assertEquals(920,receive(c))
            assertEquals(1,state.stocks.outfits.getValue(1).activeGround!!.values.single().carrier)
            c.send(query(GroundTransferMessages.Action.UNLOAD));assertEquals(840,receive(c,0x0b04))
            assertEquals(1050,state.stocks.outfits.getValue(1).activeGround!!.values.single().base)
            assertEquals(1049,state.stocks.outfits.getValue(1).baseId)
        }
    }

}
