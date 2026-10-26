package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.LogisticsMessages
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

/** Encrypted native assignment requests through the world actor and combined store. Author: 최병호. */
class StockAssignmentWireTest {
    @TempDir lateinit var directory: Path
    private val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"オーディン",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private class State : WorldStateCommitter {
        var saves = 0
        var fail = false
        var players = mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=4,cpRecoveryAt=SessionRules.load().startSeconds))
        var stocks = PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,1000,200,300,
            units=UnitInventory(mapOf(UnitStack(32,0) to 1,UnitStack(32,2) to 1),mapOf(UnitStack(3) to 10)))),
            mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,101,10001)),
            mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1049,1)),
            mapOf(10000 to LogisticsOffice.EMPIRE_FIRST),deploymentInitialized=true)
        override fun saveWorld(navigation: Map<String, Navigator>, inventory: PlanetStocks?) {
            if (fail) throw java.io.IOException("assignment test write failure")
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
    private fun fixture(state: State, folder: String) = LoadFixture(directory.resolve(folder),1,LoadMetrics(),galaxy,
        ticking=false,stockStore=state.stockStore,navigationStore=state.navigationStore,worldStateCommitter=state)
    private fun start(client: SyntheticClient) {
        client.send(SyntheticClient.opcode(0x0f00))
        repeat(300) { if (ByteBuffer.wrap(client.receive()).getShort(4).toInt() == 0x0f01) return }
        error("Game did not start")
    }
    private fun query(units: Int = 1, crew: Int = 5, supplies: Int = 100) = LogisticsMessages.Assignment(
        LogisticsMessages.Header(-1,10000,Int.MAX_VALUE,Int.MAX_VALUE),1049,1,0,
        LogisticsMessages.Moves(if (units==0) emptyList() else listOf(LogisticsMessages.ShipMove(32,units,0)),
            if (crew==0) emptyList() else listOf(LogisticsMessages.TroopMove(1003,0,crew))),supplies,
        LogisticsMessages.Inventory(listOf(LogisticsMessages.ShipStock(56,255,65535)),emptyList()),Int.MAX_VALUE,
        LogisticsMessages.Inventory(listOf(LogisticsMessages.ShipStock(40,255,65535)),emptyList()),Int.MAX_VALUE)
    private fun response(client: SyntheticClient, query: LogisticsMessages.Assignment, accepted: Boolean, mcp: Int): LogisticsMessages.Assignment {
        client.send(LogisticsMessages.encode(query))
        val message=client.receive()
        assertEquals(if (accepted) 0 else 1,ByteBuffer.wrap(message).int)
        val decoded=LogisticsMessages.assignment(message.copyOfRange(4,message.size))
        assertEquals(mcp,decoded.header.mcp); assertEquals(1000,decoded.header.pcp)
        client.expect(0x0356)
        if (accepted) {client.expect(0x0327);client.expect(0x0327)}
        return decoded
    }
    @Test fun assignsAndReturnsRealStocksWithPaymentAndPreservedLevelsAfterServerReopen() {
        val state=State()
        fixture(state,"first").use { server ->
            val client=server.join(server.account(0),true,false).client; start(client)
            val originalOutfit=state.stocks.outfits.getValue(1)
            val reply=response(client,query(),true,840)
            assertEquals(listOf(LogisticsMessages.ShipStock(32,1,300)),reply.resultBase.ships)
            assertEquals(listOf(LogisticsMessages.ShipStock(32,1,300)),reply.resultOutfit.ships)
            assertEquals(900,reply.resultBaseSupplies); assertEquals(100,reply.resultOutfitSupplies)
            assertEquals(mapOf(UnitStack(32,0) to 1),state.stocks.warehouses.getValue(101).units.ships)
            assertEquals(mapOf(UnitStack(32,2) to 1),state.stocks.bases.getValue(1049).units.ships)
            assertEquals(originalOutfit,state.stocks.outfits.getValue(1)) // Allocating does not create active ships.
            assertEquals(1,state.saves)
            val before=state.stocks; val saves=state.saves
            response(client,query(2),false,840)
            assertEquals(before,state.stocks);assertEquals(saves,state.saves)
        }
        fixture(state,"reopen").use { server ->
            val client=server.join(server.account(0),true,false).client;start(client)
            response(client,query(-1,-5,-100),true,680)
            assertTrue(state.stocks.warehouses.getValue(101).units.ships.isEmpty())
            assertTrue(state.stocks.warehouses.getValue(101).units.troops.isEmpty())
            assertEquals(0,state.stocks.warehouses.getValue(101).supplies)
            assertEquals(1000,state.stocks.bases.getValue(1049).supplies)
            assertEquals(2,state.stocks.bases.getValue(1049).units.ships.values.sum())
        }
    }
    @Test fun rejectsMissingOfficeWrongRoleWrongBaseAndInsufficientCpWithoutChangingStocks() {
        for (case in 0..3) {
            val state=State()
            if (case==0) state.stocks=state.stocks.copy(logisticsOfficers=emptyMap())
            if (case==1) state.stocks=state.stocks.copy(logisticsOfficers=mapOf(10000 to LogisticsOffice.EMPIRE_SECOND))
            if (case==3) state.players=state.players.mapValues { (_,p) -> p.copy(pcp=0,mcp=0) }
            fixture(state,"denied-$case").use { server ->
                val client=server.join(server.account(0),true,false).client;start(client)
                val before=state.stocks;val saves=state.saves
                val request=if (case==2) query().copy(base=1050) else query()
                // Custom balances in the CP case are authoritative even though the request claims MAX_VALUE.
                client.send(LogisticsMessages.encode(request))
                val packet=client.receive();assertEquals(1,ByteBuffer.wrap(packet).int)
                val reply=LogisticsMessages.assignment(packet.copyOfRange(4,packet.size))
                assertTrue(reply.resultBase.ships.isEmpty());assertTrue(reply.resultOutfit.ships.isEmpty())
                assertEquals(if (case==3) 0 else 1000,reply.header.mcp)
                client.expect(0x0356)
                assertEquals(before,state.stocks);assertEquals(saves,state.saves)
            }
        }
    }
    @Test fun emptyCommandsDoNotChargeAndSupplyOverdraftPartialShipsAndDuplicatesDoNotCommit() {
        val state=State()
        fixture(state,"invalid").use { server ->
            val client=server.join(server.account(0),true,false).client;start(client)
            val before=state.stocks;val saves=state.saves
            response(client,query(0,0,0),true,1000)
            val base=query()
            for (request in listOf(query(supplies=1001),query(supplies=-1),query(crew=11),
                base.copy(moves=base.moves.copy(ships=listOf(LogisticsMessages.ShipMove(32,1,300)))),
                base.copy(moves=base.moves.copy(ships=base.moves.ships+base.moves.ships)),
                base.copy(moves=base.moves.copy(troops=base.moves.troops+base.moves.troops)))) {
                response(client,request,false,1000)
                assertEquals(before,state.stocks);assertEquals(saves,state.saves)
            }
            response(client,query(),true,840) // A rejection must not leave a stale reservation.
        }
    }
    @Test fun storageFailureKeepsBothInventoriesAndCpAndClosesFurtherMutationsUntilRestart() {
        val state=State()
        fixture(state,"failed-write").use { server ->
            val client=server.join(server.account(0),true,false).client;start(client)
            val before=state.stocks;val players=state.players;val saves=state.saves
            state.fail=true
            client.send(LogisticsMessages.encode(query()))
            assertFails { client.receive() }
            assertEquals(before,state.stocks);assertEquals(players,state.players);assertEquals(saves,state.saves)
            assertEquals(EngineHealth.STORAGE_FAILED,server.engine.health())
            assertEquals(before,server.engine.snapshot().planetStocks)
        }
        state.fail=false
        fixture(state,"after-write-failure").use { server ->
            val client=server.join(server.account(0),true,false).client;start(client)
            response(client,query(),true,840)
        }
    }
    @Test fun nativeReplyBoatLimitIsCheckedBeforeSavingOrCharging() {
        val state=State()
        state.stocks=state.stocks.copy(
            bases=mapOf(1049 to state.stocks.bases.getValue(1049).copy(units=UnitInventory(mapOf(UnitStack(32) to 2)))),
            warehouses=mapOf(101 to state.stocks.warehouses.getValue(101).copy(units=UnitInventory(mapOf(UnitStack(32) to 217)))))
        fixture(state,"reply-capacity").use { server ->
            val client=server.join(server.account(0),true,false).client;start(client)
            val reply=response(client,query(1,0,0),true,840)
            assertEquals(listOf(LogisticsMessages.ShipStock(32,218,65400)),reply.resultOutfit.ships)
            val before=state.stocks;val saves=state.saves
            response(client,query(1,0,0),false,840)
            assertEquals(before,state.stocks);assertEquals(saves,state.saves)
        }
    }
    @Test fun logisticsOfficeRosterReturnsTheStoredHolderAndNoInventedOfficer() {
        val state=State()
        fixture(state,"office-roster").use { server ->
            val client=server.join(server.account(0),true,false).client;start(client)
            for ((card,count) in listOf(24 to 1,25 to 0,150 to 0)) {
                client.send(ByteBuffer.allocate(4).putShort(0x034e).putShort(card.toShort()).array())
                val reply=ByteBuffer.wrap(client.expect(0x034f))
                assertEquals(card,reply.getShort(6).toInt());assertEquals(count,reply.get(8).toInt())
            }
        }
    }
    @Test fun originalDeploymentPickerListsOnlyAuthorizedFriendlyLocationsWithoutCharging() {
        for (permitted in listOf(true,false)) {
            val state=State()
            if (!permitted) state.stocks=state.stocks.copy(logisticsOfficers=emptyMap())
            fixture(state,"deployment-$permitted").use { server ->
                val client=server.join(server.account(0),true,false).client;start(client)
                val before=state.stocks;val saves=state.saves
                val request=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(32).putShort(80).array()
                client.send(request);client.expect(0x1200)
                val reply=ByteBuffer.wrap(client.expect(0x1204))
                assertEquals(if(permitted)1 else 0,reply.get(6).toInt())
                if(permitted) {
                    assertEquals(1049,reply.getInt(7))
                    assertEquals(2487,reply.getShort(11).toInt())
                    assertEquals("オーディン\u0000", String(ByteArray(reply.get(15).toInt()*2).also { reply.position(16);reply.get(it) }, Charsets.UTF_16BE))
                }
                client.expect(0x1201)
                assertEquals(before,state.stocks);assertEquals(saves,state.saves)
                assertEquals(1000,state.players.getValue("load-000").mcp)
            }
        }
    }
    @Test fun outfitPickerRequiresStoredOfficeMatchingCardAndFriendlyBase() {
        for (card in listOf(24,25)) {
            val state=State()
            state.stocks=state.stocks.copy(outfits=mapOf(1 to state.stocks.outfits.getValue(1).copy(number=7)))
            fixture(state,"outfit-picker-$card").use { server ->
                val client=server.join(server.account(0),true,false).client;start(client)
                val before=state.stocks;val saves=state.saves
                val request=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(31).putShort(124)
                    .putInt(25,1049).putShort(29,card.toShort()).array()
                client.send(request);client.expect(0x1200)
                val reply=ByteBuffer.wrap(client.expect(0x1203))
                assertEquals(if(card==24)1 else 0,reply.get(6).toInt())
                if(card==24) {
                    assertEquals(1,reply.getInt(7));assertEquals(7,reply.get(11).toInt());assertEquals(0,reply.get(12).toInt())
                    assertEquals(1,reply.get(13).toInt());assertEquals(1049,reply.getInt(14))
                    assertEquals(2487,reply.getShort(18).toInt())
                }
                client.expect(0x1201)
                assertEquals(before,state.stocks);assertEquals(saves,state.saves)
                assertEquals(1000,state.players.getValue("load-000").mcp)
            }
        }
    }

}
