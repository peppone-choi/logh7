package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class ReorganizationWireTest {
    @Test fun allFormationKindsRejectOverLimitPreviewAndCommitWithoutPaymentAndKeepTheBoundaryAfterReconnect() {
        for ((kind, limit) in listOf(OutfitKind.FLEET to 60,OutfitKind.TRANSPORT to 23,
                OutfitKind.PATROL to 3,OutfitKind.GROUND to 3)) {
            val state=State(10000)
            state.stocks=state.stocks.copy(outfits=mapOf(1 to state.stocks.outfits.getValue(1).copy(kind=kind)),
                warehouses=mapOf(101 to state.stocks.warehouses.getValue(101).copy(kind=kind.warehouseKind,
                    outfitId=if (kind in setOf(OutfitKind.FLEET,OutfitKind.TRANSPORT)) 1 else 0,
                    units=UnitInventory(mapOf(UnitStack(32) to limit+1),mapOf(UnitStack(3) to (limit+1)*5)))))
            fixture(state,"limit-${kind.name}").use { server ->
                val client=server.join(server.account(0),true,false).client;start(client)
                val before=state.stocks;val saves=state.saves
                val preview=query(limit+1,0).copy(kind=kind.wire,mode=0,
                    moves=LogisticsMessages.Moves(listOf(LogisticsMessages.ShipMove(32,limit+1,0)),emptyList()))
                client.send(LogisticsMessages.encode(preview))
                val denied=client.receive()
                assertEquals(0x0c02,ByteBuffer.wrap(denied).getShort(4).toInt())
                assertEquals(1,ByteBuffer.wrap(denied).int)
                response(client,query(limit+1,(limit+1)*5).copy(kind=kind.wire),false,1000)
                assertEquals(before,state.stocks);assertEquals(saves,state.saves)
                response(client,query(limit,limit*5).copy(kind=kind.wire),true,840)
                client.expect(0x032f);client.expect(0x0327)
                val committed=state.stocks;val paid=state.players;val committedSaves=state.saves
                response(client,query(1,5).copy(kind=kind.wire),false,840)
                assertEquals(committed,state.stocks);assertEquals(paid,state.players);assertEquals(committedSaves,state.saves)
            }
            fixture(state,"reopen-${kind.name}").use { server ->
                val client=server.join(server.account(0),true,false).client;start(client)
                assertEquals(limit,state.stocks.outfits.getValue(1).units.ships.values.sum())
                response(client,query(-1,-5).copy(kind=kind.wire),true,680)
                client.expect(0x032f);client.expect(0x0327)
                assertEquals(limit-1,state.stocks.outfits.getValue(1).units.ships.values.sum())
                assertEquals(limit+1,state.stocks.outfits.getValue(1).units.ships.values.sum()+
                    state.stocks.warehouses.getValue(101).units.ships.values.sum())
            }
        }
    }
    @TempDir lateinit var directory: Path
    private val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private class State(commander: Int) : WorldStateCommitter {
        var saves = 0
        var players = mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=4,cpRecoveryAt=SessionRules.load().startSeconds))
        var stocks = PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
            mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,101,commander)),
            mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1049,1,
                UnitInventory(mapOf(UnitStack(32,0) to 1,UnitStack(32,1) to 1),mapOf(UnitStack(3) to 10)))),
            deploymentInitialized=true)
        override fun saveWorld(navigation: Map<String, Navigator>, inventory: PlanetStocks?) {
            saves++; players=navigation.toMap(); stocks=checkNotNull(inventory)
        }
        val navigationStore = object : NavigationStore {
            override fun load() = players
            override fun save(players: Map<String, Navigator>) { this@State.players=players.toMap() }
        }
        val stockStore = object : PlanetStockStore {
            override fun load() = stocks
            override fun save(stocks: PlanetStocks) { this@State.stocks=stocks }
        }
    }
    private fun query(units: Int = 2, crew: Int = 10) = LogisticsMessages.Reorganization(
        LogisticsMessages.Header(-1,10000,Int.MAX_VALUE,Int.MAX_VALUE),1,1049,1,0,
        LogisticsMessages.Moves(listOf(LogisticsMessages.ShipMove(32,units,0)),
            listOf(LogisticsMessages.TroopMove(1003,0,crew))),Int.MAX_VALUE,Int.MAX_VALUE,0)
    private fun start(client: SyntheticClient) {
        client.send(SyntheticClient.opcode(0x0f00))
        repeat(300) { if (ByteBuffer.wrap(client.receive()).getShort(4).toInt() == 0x0f01) return }
        error("Game did not start")
    }
    private fun response(client: SyntheticClient, query: LogisticsMessages.Reorganization, accepted: Boolean, mcp: Int) {
        client.send(LogisticsMessages.encode(query))
        val message=client.receive(); val input=ByteBuffer.wrap(message)
        assertEquals(if (accepted) 0 else 1,input.int)
        val decoded=LogisticsMessages.reorganization(message.copyOfRange(4,message.size))
        assertEquals(10000,decoded.header.characterId); assertEquals(1000,decoded.header.pcp)
        assertEquals(mcp,decoded.header.mcp); assertEquals(1,decoded.mode)
        assertEquals(0,decoded.maxTroops); assertEquals(0,decoded.maxCrew)
        assertEquals(if (accepted) query.moves else LogisticsMessages.Moves(emptyList(),emptyList()),decoded.moves)
        client.expect(0x0356)
    }
    private fun fixture(state: State, folder: String, capacity: Int = 1) = LoadFixture(directory.resolve(folder),capacity,LoadMetrics(),galaxy,
        ticking=false,stockStore=state.stockStore,navigationStore=state.navigationStore,worldStateCommitter=state)
    @Test fun encryptedCommitRefreshesRosterWarehouseAndCpAndCanReturnUnitsAfterReconnect() {
        val state=State(10000)
        fixture(state,"first").use { server ->
            val client=server.join(server.account(0),true,false).client; start(client)
            val before=state.stocks; val balance=state.players; val saves=state.saves
            val preview=query(2,0).copy(mode=0,moves=LogisticsMessages.Moves(
                listOf(LogisticsMessages.ShipMove(32,2,0)),emptyList()))
            client.send(LogisticsMessages.encode(preview))
            val packet=client.expect(0x0c02)
            assertEquals(0,ByteBuffer.wrap(packet).int)
            val capacity=LogisticsMessages.reorganization(packet.copyOfRange(4,packet.size))
            assertEquals(0,capacity.mode); assertEquals(10,capacity.maxCrew); assertEquals(0,capacity.maxTroops)
            assertEquals(0,capacity.supplies)
            assertEquals(1000,capacity.header.mcp); assertTrue(capacity.moves.ships.isEmpty())
            assertEquals(before,state.stocks); assertEquals(balance,state.players); assertEquals(saves,state.saves)
            client.send(LogisticsMessages.encode(preview.copy(moves=LogisticsMessages.Moves(
                listOf(LogisticsMessages.ShipMove(32,3,0)),emptyList()))))
            val denied=client.receive()
            assertEquals(0x0c02,ByteBuffer.wrap(denied).getShort(4).toInt())
            assertEquals(1,ByteBuffer.wrap(denied).int)
            assertEquals(before,state.stocks); assertEquals(saves,state.saves)
            // Invalid crew must leave no prepared lock, payment, or optimistic roster update.
            response(client,query(crew=9),false,1000)
            assertTrue(state.stocks.outfits.getValue(1).units.ships.isEmpty())
            response(client,query(),true,840)
            val party=client.expect(0x032f); val warehouse=client.expect(0x0327)
            assertEquals(1,ByteBuffer.wrap(party).getInt(6))
            assertEquals(1049,ByteBuffer.wrap(warehouse).getInt(6)); assertEquals(1,ByteBuffer.wrap(warehouse).getInt(10))
            val units=state.stocks.outfits.getValue(1).units
            assertEquals(mapOf(UnitStack(32,0) to 1,UnitStack(32,1) to 1),units.ships)
            assertEquals(mapOf(UnitStack(3) to 10),units.troops)
            assertTrue(state.stocks.warehouses.getValue(101).units.ships.isEmpty())
            assertEquals(840,state.players.getValue("load-000").mcp)
            val committed=state.stocks;val committedSaves=state.saves
            client.send(LogisticsMessages.encode(query(-1,0).copy(mode=0,
                moves=LogisticsMessages.Moves(listOf(LogisticsMessages.ShipMove(32,-1,0)),emptyList()))))
            val smaller=client.expect(0x0c02)
            assertEquals(0,ByteBuffer.wrap(smaller).int)
            val delta=LogisticsMessages.reorganization(smaller.copyOfRange(4,smaller.size))
            assertEquals(-5,delta.maxCrew);assertEquals(0,delta.supplies)
            assertEquals(committed,state.stocks);assertEquals(committedSaves,state.saves)
            assertEquals(840,state.players.getValue("load-000").mcp)
        }
        fixture(state,"second").use { server ->
            val client=server.join(server.account(0),true,false).client; start(client)
            response(client,query(-2,-10),true,680)
            client.expect(0x032f); client.expect(0x0327)
            assertTrue(state.stocks.outfits.getValue(1).units.ships.isEmpty())
            assertEquals(2,state.stocks.warehouses.getValue(101).units.ships.values.sum())
            assertEquals(10,state.stocks.warehouses.getValue(101).units.troops.values.sum())
            assertEquals(680,state.players.getValue("load-000").mcp)
        }
    }
    @Test fun unauthorizedOutfitReturnsNoOtherCommandersRosterOrWarehouse() {
        val state=State(10001)
        fixture(state,"denied").use { server ->
            val client=server.join(server.account(0),true,false).client; start(client)
            val before=state.stocks
            response(client,query(),false,1000)
            client.send(ByteBuffer.allocate(7).putShort(0x031e).put(1).putInt(1049).array())
            client.expect(0x031f) // No unsolicited 032f/0327 was queued after rejection.
            assertEquals(before,state.stocks); assertEquals(1000,state.players.getValue("load-000").mcp)
        }
    }
    @Test fun twoAuthenticatedCommandersCannotBothConsumeTheSameSharedWarehouse() {
        val state=State(10000)
        state.players=state.players + ("load-001" to state.players.getValue("load-000").copy(characterId=10001))
        val first=state.stocks.outfits.getValue(1).copy(kind=OutfitKind.PATROL)
        state.stocks=state.stocks.copy(outfits=mapOf(1 to first,2 to first.copy(id=2,commanderId=10001)),
            warehouses=mapOf(101 to state.stocks.warehouses.getValue(101).copy(kind=OutfitKind.PATROL,outfitId=0)))
        fixture(state,"shared",2).use { server ->
            val clients=(0..1).map { server.join(server.account(it),true,false).client.also(::start) }
            clients.forEachIndexed { index,client ->
                val request=query().copy(header=query().header.copy(characterId=10000+index),outfit=1+index,kind=2)
                client.send(LogisticsMessages.encode(request))
            }
            val statuses=clients.map { client ->
                var reply: ByteArray?=null
                for (ignored in 0..100) {
                    val packet=client.receive()
                    if (ByteBuffer.wrap(packet).getShort(4).toInt() == 0x0c02) { reply=packet; break }
                }
                ByteBuffer.wrap(assertNotNull(reply)).int
            }
            assertEquals(listOf(0,1),statuses.sorted())
            assertEquals(listOf(840,1000),state.players.values.map { it.mcp }.sorted())
            assertEquals(2,state.stocks.outfits.values.sumOf { it.units.ships.values.sum() })
            assertEquals(10,state.stocks.outfits.values.sumOf { it.units.troops.values.sum() })
            assertTrue(state.stocks.warehouses.getValue(101).units.ships.isEmpty())
            assertTrue(state.stocks.warehouses.getValue(101).units.troops.isEmpty())
            assertEquals(2,state.stocks.outfits.values.flatMap { it.activeShips!!.values }.map { it.id }.distinct().size)
        }
    }
}
