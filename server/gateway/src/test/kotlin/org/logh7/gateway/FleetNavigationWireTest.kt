package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.engine.strategy.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class FleetNavigationWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private inner class State(aide: Boolean = false):WorldStateCommitter {
        var players=mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=4,spotId=10490807,cpRecoveryAt=SessionRules.load().startSeconds)).let { players ->
                if(aide) players + ("load-001" to players.getValue("load-000").copy(characterId=10001)) else players
            }
        var stocks=ActiveShips.initialize(PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
            mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,10,10000,UnitInventory(mapOf(UnitStack(32) to 10)),
                aideId=if(aide)10001 else 0)),
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
    private fun State.restoreWarpWithLowFuelAide() {
        val now = SessionRules.load().startSeconds * 1000
        val due = now + 3600_000
        val job = StrategyJob(0, "operation/ワープ航行", now, due, due,
            PaymentResult.Paid(CpBalance(1000, 920), CpPool.MCP, 80, 80, false), JobState.WAITING)
        players = players.mapValues { (account, player) ->
            val underway = player.copy(baseId = 0, mode = 5, spotId = 0, moves = 1)
            if (account == "load-000") underway.copy(mcp = 920, warpTarget = 2489,
                strategy = StrategyCheckpoint(1, now, listOf(job))) else underway.copy(cruise = 100)
        }
        stocks = stocks.copy(outfits = mapOf(1 to stocks.outfits.getValue(1).copy(position = OutfitPosition(2487))))
    }
    private fun through(client:SyntheticClient,opcode:Int,character:Int?=null):ByteArray {
        repeat(300) {val bytes=client.receive();val body=ByteBuffer.wrap(bytes)
            if(body.getShort(4).toInt()==opcode && (character==null || body.getInt(10)==character))return bytes}
        error("Missing transaction end")
    }
    @Test fun awardedOriginalEntersAtOdinWithTheSecondFleetAideCard() {
        val state=State()
        state.players=emptyMap()
        state.stocks=state.stocks.copy(outfits=mapOf(2 to state.stocks.outfits.getValue(1).copy(id=2,number=2,commanderId=0)),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1049,2)))
        val file=directory.resolve("characters.properties")
        CharacterCreation(file,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),10).apply("load-000",listOf(1001))
        val won=assertNotNull(CharacterCreation(file,Clock.fixed(Instant.ofEpochMilli(10),ZoneOffset.UTC),10).character("load-000"))
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false,stockStore=state.inventory,navigationStore=state.nav,
            worldStateCommitter=state).use {fixture ->
            val joined=fixture.join(fixture.account(0),false,false)
            assertTrue(joined.accepted)
            joined.client.send(SyntheticClient.opcode(0x0f00))
            val card=through(joined.client,0x0323)
            val finalCard=ByteBuffer.wrap(card).position(card.size-7)
            assertEquals(43,finalCard.short.toInt())
            assertEquals(2,finalCard.int)
            assertContentEquals(BootstrapMessages.unit(won,2487,1000,1049,4,2),through(joined.client,0x0325))
            through(joined.client,0x0f01)
            assertEquals(1001,state.stocks.outfits.getValue(2).aideId)
        }
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
    @Test fun onlineAideReceivesOwnDepartureAndWarpNotificationsAndRestoresWithCommander() {
        val state=State(aide=true)
        repeat(2) {round ->
            LoadFixture(directory.resolve("group-$round"),2,LoadMetrics(),galaxy,ticking=false,
                stockStore=state.inventory,navigationStore=state.nav,worldStateCommitter=state).use {fixture ->
                val commander=fixture.join(fixture.account(0),true,false).client
                commander.send(SyntheticClient.opcode(0x0f00));through(commander,0x0f01)
                val aide=fixture.join(fixture.account(1),true,false).client
                aide.send(SyntheticClient.opcode(0x0f00));through(aide,0x0f01)
                if(round==0) {
                    val depart=PlaceMessages.switched(PlaceMessages.SwitchMode(10000,59,5),0,0,0,5,0,0,true)
                    commander.send(depart.copyOfRange(4,depart.size));through(commander,0x0b0b)
                    aide.send(SyntheticClient.opcode(0x0300))
                    val departed=ByteBuffer.wrap(through(aide,0x0b0b,10001))
                    assertEquals(10001,departed.getInt(10));assertEquals(0,departed.getInt(14))
                    assertEquals(5,departed.getShort(18).toInt())
                    val warp=NavigationMessages.accepted(NavigationMessages.Warp(10000,59,2488,0,5),0,0,0)
                    commander.send(warp.copyOfRange(4,warp.size));through(commander,0x0b07)
                    aide.send(SyntheticClient.opcode(0x0300))
                    val moved=ByteBuffer.wrap(through(aide,0x0b07,10001))
                    assertEquals(10001,moved.getInt(10));assertEquals(2488,moved.getInt(14))
                    assertEquals(1010001,moved.getInt(25));assertEquals(900,moved.getInt(29))
                }
                assertEquals(2488,state.players.getValue("load-001").grid)
                assertEquals(1000,state.players.getValue("load-001").mcp)
                assertEquals(900,state.players.getValue("load-001").cruise)
                assertEquals(OutfitPosition(2488),state.stocks.outfits.getValue(1).position)
            }
        }
    }

    @Test fun aMembersReservedWarpRejectsTheCommandersMoveWithoutPaymentOrConnectionLoss() {
        val state=State(aide=true)
        LoadFixture(directory.resolve("pending"),2,LoadMetrics(),galaxy,ticking=false,
            stockStore=state.inventory,navigationStore=state.nav,worldStateCommitter=state).use {fixture ->
            fun connected(client:SyntheticClient) {
                client.send(SyntheticClient.opcode(0x0300));through(client,0x0301)
            }
            val commander=fixture.join(fixture.account(0),true,false).client
            commander.send(SyntheticClient.opcode(0x0f00));through(commander,0x0f01)
            val aide=fixture.join(fixture.account(1),true,false).client
            aide.send(SyntheticClient.opcode(0x0f00));through(aide,0x0f01)
            val depart=PlaceMessages.switched(PlaceMessages.SwitchMode(10000,59,5),0,0,0,5,0,0,true)
            commander.send(depart.copyOfRange(4,depart.size));through(commander,0x0b0b)
            val personal=NavigationMessages.accepted(NavigationMessages.Warp(10001,59,2489,0,5),0,0,0)
            aide.send(personal.copyOfRange(4,personal.size))
            val reserved=ByteBuffer.wrap(through(aide,0x0b01))
            assertEquals(0,reserved.getInt(0));assertEquals(3600,reserved.getInt(10))
            connected(aide) // Drain experience earned by the accepted reservation before comparing later rejections.
            val before=state.players.toMap();val stocks=state.stocks
            for(destination in listOf(2488,2489)) {
                val fleet=NavigationMessages.accepted(NavigationMessages.Warp(10000,59,destination,0,5),0,0,0)
                commander.send(fleet.copyOfRange(4,fleet.size))
                assertEquals(1,ByteBuffer.wrap(through(commander,0x0b01)).getInt(0))
                assertEquals(before,state.players);assertEquals(stocks,state.stocks)
                connected(commander);connected(aide)
            }
        }
    }

    @Test fun resigningAtWarpCompletionSendsArrivalToBothFlagshipsAndDoesNotMoveOrChargeOnReplay()=runBlocking {
        val state=State(aide=true)
        LoadFixture(directory.resolve("recover"),2,LoadMetrics(),galaxy,ticking=false,
            stockStore=state.inventory,navigationStore=state.nav,worldStateCommitter=state).use {fixture ->
            val commander=fixture.join(fixture.account(0),true,false).client
            commander.send(SyntheticClient.opcode(0x0f00));through(commander,0x0f01)
            val aide=fixture.join(fixture.account(1),true,false).client
            aide.send(SyntheticClient.opcode(0x0f00));through(aide,0x0f01)
            val depart=PlaceMessages.switched(PlaceMessages.SwitchMode(10000,59,5),0,0,0,5,0,0,true)
            commander.send(depart.copyOfRange(4,depart.size));through(commander,0x0b0b)
            val warp=NavigationMessages.accepted(NavigationMessages.Warp(10000,59,2489,0,5),0,0,0)
            commander.send(warp.copyOfRange(4,warp.size))
            assertEquals(3600,ByteBuffer.wrap(through(commander,0x0b01)).getInt(10))
            fixture.engine.submit(WorldCommand.AdvanceTo(150_000))
            val resign=ByteBuffer.allocate(27).putShort(0x709).putInt(-1).putInt(10000)
                .putInt(-1).putInt(-1).putInt(39).putInt(0).put(0).array()
            commander.send(resign)
            val accepted=ByteBuffer.wrap(through(commander,0x709))
            assertEquals(0,accepted.getInt(0));assertEquals(840,accepted.getInt(18))
            assertEquals(2489,ByteBuffer.wrap(through(commander,0x0b07,10000)).getInt(14))
            aide.send(SyntheticClient.opcode(0x0300))
            assertEquals(2489,ByteBuffer.wrap(through(aide,0x0b07,10001)).getInt(14))
            assertEquals(OutfitPosition(2489),state.stocks.outfits.getValue(1).position)
            assertEquals(0,state.stocks.outfits.getValue(1).commanderId)
            assertEquals(2489,state.players.getValue("load-001").grid)
            assertEquals(1000,state.players.getValue("load-001").mcp)
            assertEquals(800,state.players.getValue("load-001").cruise)
            commander.send(resign)
            val replay=ByteBuffer.wrap(through(commander,0x709))
            assertEquals(1,replay.getInt(0));assertEquals(840,replay.getInt(18))
            assertEquals(800,state.players.getValue("load-000").cruise)
            assertEquals(OutfitPosition(2489),state.stocks.outfits.getValue(1).position)
        }
    }

    @Test fun lowFuelAideRejectsNewWarpReservationWithoutChargingOrDisconnecting() {
        val state = State(aide = true)
        state.players = state.players + ("load-001" to state.players.getValue("load-001").copy(cruise = 199))
        LoadFixture(directory, 1, LoadMetrics(), galaxy, ticking = false, stockStore = state.inventory,
            navigationStore = state.nav, worldStateCommitter = state).use { fixture ->
            val commander = fixture.join(fixture.account(0), true, false).client
            commander.send(SyntheticClient.opcode(0x0f00))
            through(commander, 0x0f01)
            val departure = PlaceMessages.switched(PlaceMessages.SwitchMode(10000, 59, 5), 0, 0, 0, 5, 0, 0, true)
            commander.send(departure.copyOfRange(4, departure.size))
            through(commander, 0x0b0b)
            val players = state.players
            val stocks = state.stocks
            val warp = NavigationMessages.accepted(NavigationMessages.Warp(10000, 59, 2489, 0, 5), 0, 0, 0)
            commander.send(warp.copyOfRange(4, warp.size))
            assertEquals(1, ByteBuffer.wrap(through(commander, 0x0b01)).int)
            assertEquals(2487, ByteBuffer.wrap(through(commander, 0x0b07, 10000)).getInt(14))
            assertEquals(players, state.players)
            assertEquals(stocks, state.stocks)
            commander.send(SyntheticClient.opcode(0x0300))
            through(commander, 0x0301)
        }
    }

    @Test fun aiSwitchesAtWarpCompletionMoveTheWholeFleetOrKeepTheReservationIntact()=runBlocking {
      for(blocker in 0..2) {
        val state=State(aide=true)
        if(blocker==2)state.restoreWarpWithLowFuelAide()
        LoadFixture(directory.resolve("ai-arrival-$blocker"),2,LoadMetrics(),galaxy,ticking=false,
            stockStore=state.inventory,navigationStore=state.nav,worldStateCommitter=state).use {fixture ->
            val commander=fixture.join(fixture.account(0),true,false).client
            commander.send(SyntheticClient.opcode(0x0f00));through(commander,0x0f01)
            val aide=fixture.join(fixture.account(1),true,false).client
            aide.send(SyntheticClient.opcode(0x0f00));through(aide,0x0f01)
            val depart=PlaceMessages.switched(PlaceMessages.SwitchMode(10000,59,5),0,0,0,5,0,0,true)
            if(blocker!=2) {commander.send(depart.copyOfRange(4,depart.size));through(commander,0x0b0b)}
            fun reserve(client:SyntheticClient,id:Int) {
                val warp=NavigationMessages.accepted(NavigationMessages.Warp(id,59,2489,0,5),0,0,0)
                client.send(warp.copyOfRange(4,warp.size))
                assertEquals(3600,ByteBuffer.wrap(through(client,0x0b01)).getInt(10))
            }
            if(blocker!=2)reserve(commander,10000)
            if(blocker==1) {fixture.engine.submit(WorldCommand.AdvanceTo(42));reserve(aide,10001)}
            fixture.engine.submit(WorldCommand.AdvanceTo(150_000))
            val settings=ByteBuffer.allocate(15).putShort(0x0f18).putInt(0).putInt(10000)
                .put(byteArrayOf(1,0,1,0,1)).array()
            commander.send(settings)
            val accepted=ByteBuffer.wrap(through(commander,0x0f18))
            assertEquals(0,accepted.getInt(0));assertEquals(10000,accepted.getInt(10))
            assertContentEquals(byteArrayOf(1,0,1,0,1),accepted.array().copyOfRange(14,19))
            if(blocker==0) {
                assertEquals(2489,ByteBuffer.wrap(through(commander,0x0b07,10000)).getInt(14))
                aide.send(SyntheticClient.opcode(0x0300))
                assertEquals(2489,ByteBuffer.wrap(through(aide,0x0b07,10001)).getInt(14))
            }
            commander.send(SyntheticClient.opcode(0x0300));through(commander,0x0301)
            val leader=state.players.getValue("load-000");val member=state.players.getValue("load-001")
            assertEquals(if(blocker==0)2489 else 2487,leader.grid);assertEquals(leader.grid,member.grid)
            assertEquals(if(blocker==0)null else 2489,leader.warpTarget)
            assertEquals(920,leader.mcp);assertEquals(if(blocker==0)800 else 1000,leader.cruise)
            assertEquals(if(blocker==1)2489 else null,member.warpTarget)
            assertEquals(if(blocker==1)920 else 1000,member.mcp)
            assertEquals(if(blocker==2)100 else if(blocker==0)800 else 1000,member.cruise)
            assertEquals(OutfitPosition(leader.grid),state.stocks.outfits.getValue(1).position)
            assertEquals(21,state.stocks.tacticalCommands.aiSwitches[10000])
            commander.send(settings);assertEquals(0,ByteBuffer.wrap(through(commander,0x0f18)).getInt(0))
            commander.send(SyntheticClient.opcode(0x0300));through(commander,0x0301)
            assertEquals(leader,state.players.getValue("load-000"))
        }
      }
    }

    @Test fun deferredFleetArrivalsKeepClockRefreshAliveUntilTheBlockingMemberMoves()=runBlocking {
      for(lowFuel in listOf(false,true)) {
        val state=State(aide=true)
        if(lowFuel)state.restoreWarpWithLowFuelAide()
        LoadFixture(directory.resolve("deferred-$lowFuel"),2,LoadMetrics(),galaxy,ticking=false,
            stockStore=state.inventory,navigationStore=state.nav,worldStateCommitter=state).use {fixture ->
            val commander=fixture.join(fixture.account(0),true,false).client
            commander.send(SyntheticClient.opcode(0x0f00));through(commander,0x0f01)
            val aide=fixture.join(fixture.account(1),true,false).client
            aide.send(SyntheticClient.opcode(0x0f00));through(aide,0x0f01)
            val depart=PlaceMessages.switched(PlaceMessages.SwitchMode(10000,59,5),0,0,0,5,0,0,true)
            if(!lowFuel) {commander.send(depart.copyOfRange(4,depart.size));through(commander,0x0b0b)}
            fun reserve(client:SyntheticClient,id:Int) {
                val warp=NavigationMessages.accepted(NavigationMessages.Warp(id,59,2489,0,5),0,0,0)
                client.send(warp.copyOfRange(4,warp.size))
                assertEquals(3600,ByteBuffer.wrap(through(client,0x0b01)).getInt(10))
            }
            if(!lowFuel)reserve(commander,10000)
            if(!lowFuel) {
                fixture.engine.submit(WorldCommand.AdvanceTo(42))
                reserve(aide,10001)
            }
            fixture.engine.submit(WorldCommand.AdvanceTo(150_000))
            commander.send(SyntheticClient.opcode(0x0300));through(commander,0x0301)
            assertEquals(2487,state.players.getValue("load-000").grid)
            assertEquals(2489,state.players.getValue("load-000").warpTarget)
            assertEquals(920,state.players.getValue("load-000").mcp)
            assertEquals(OutfitPosition(2487),state.stocks.outfits.getValue(1).position)
            if(lowFuel) {
                val leave=NavigationMessages.accepted(NavigationMessages.Warp(10001,59,2488,0,5),0,0,0)
                aide.send(leave.copyOfRange(4,leave.size));through(aide,0x0b07,10001)
            } else {
                fixture.engine.submit(WorldCommand.AdvanceTo(150_042))
                aide.send(SyntheticClient.opcode(0x0300));through(aide,0x0b07,10001)
            }
            commander.send(SyntheticClient.opcode(0x0300));through(commander,0x0b07,10000)
            val leader=state.players.getValue("load-000");val member=state.players.getValue("load-001")
            assertEquals(2489,leader.grid);assertNull(leader.warpTarget)
            assertEquals(920,leader.mcp);assertEquals(800,leader.cruise);assertEquals(2L,leader.moves)
            assertEquals(if(lowFuel)2488 else 2489,member.grid);assertNull(member.warpTarget)
            assertEquals(if(lowFuel)960 else 920,member.mcp);assertEquals(if(lowFuel)0 else 800,member.cruise)
            assertEquals(2L,member.moves)
            assertEquals(OutfitPosition(2489),state.stocks.outfits.getValue(1).position)
        }
      }
    }
}
