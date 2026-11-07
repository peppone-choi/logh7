package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.persistence.FileWorldStateStore
import org.logh7.protocol.ChatMessages
import org.logh7.protocol.NavigationMessages
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

/** Distance-scaled warp waits (design 2026-10-30): ACK with the wait, 0b07 only on execution. */
class WarpReservationWireTest {
    @TempDir lateinit var directory: Path
    private val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire"), GalaxySystem(4, 2015, 4, "Alliance")),
        emptyList(), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
    private fun until(client: SyntheticClient, opcode: Int): ByteBuffer {
        repeat(300) { val message = ByteBuffer.wrap(client.receive()); if (message.getShort(4).toInt() == opcode) return message }
        error("Missing 0x%04x".format(opcode))
    }
    private fun warp(client: SyntheticClient, grid: Int, character: Int = 10000, card: Int = 59) {
        val request = NavigationMessages.accepted(NavigationMessages.Warp(character, card, grid, 0, 5), 0, 0, 0)
        client.send(request.copyOfRange(4, request.size))
    }

    @Test fun firstWarpGuideArrivesAfterGridBootstrapAndStopsAfterFirstMove() = runBlocking<Unit> {
        LoadFixture(directory, 1, LoadMetrics(), galaxy, ticking = false).use { fixture ->
            val account = fixture.account(0)
            val client = fixture.join(account, true, false).client
            fun guideUntil(client: SyntheticClient, end: Int): List<String> {
                val notices = mutableListOf<String>()
                repeat(300) {
                    val message = client.receive()
                    when (ByteBuffer.wrap(message).getShort(4).toInt()) {
                        end -> return notices
                        0x0f1d -> {
                            val notice = ChatMessages.spot(message.copyOfRange(4, message.size))
                            assertEquals(10000, notice.characterId)
                            notices += notice.text
                        }
                    }
                }
                error("Missing 0x%04x".format(end))
            }
            fun clock(client: SyntheticClient) = client.send(SyntheticClient.opcode(0x0300))
            client.send(SyntheticClient.opcode(0x0f00)); until(client, 0x0f01)
            clock(client); until(client, 0x0301)
            client.send(SyntheticClient.opcode(0x0f02)); assertEquals(emptyList(), guideUntil(client, 0x0f03))
            clock(client); until(client, 0x0301)
            clock(client)
            assertEquals(listOf("직무카드→함장→워프"), guideUntil(client, 0x0301))
            clock(client); assertEquals(emptyList(), guideUntil(client, 0x0301))

            warp(client, 2488); until(client, 0x0b07)
            assertEquals(1L, fixture.engine.snapshot().navigators.getValue(account).moves)
            client.close()
            fixture.awaitState(online = 0, connections = 0, participants = 1)
            val rejoined = fixture.join(account, false, false).client
            rejoined.send(SyntheticClient.opcode(0x0f00)); until(rejoined, 0x0f01)
            rejoined.send(SyntheticClient.opcode(0x0f02)); until(rejoined, 0x0f03)
            clock(rejoined); until(rejoined, 0x0301)
            clock(rejoined); assertEquals(emptyList(), guideUntil(rejoined, 0x0301))
        }
    }

    @Test fun reservedWarpAcknowledgesTheWaitAndCompletesOnlyAfterExecution() = runBlocking<Unit> {
        LoadFixture(directory, 1, LoadMetrics(), galaxy, ticking = false).use { fixture ->
            val client = fixture.join(fixture.account(0), true, false).client
            client.send(org.logh7.gateway.load.SyntheticClient.opcode(0x0f00)); until(client, 0x0f01)
            warp(client, 2588) // Diagonal: 142 ly, two started 100 ly -> 80 MCP and one G-hour.
            val ack = until(client, 0x0b01)
            assertEquals(0, ack.getInt(0)); assertEquals(3600, ack.getInt(10)); assertEquals(920, ack.getInt(24))
            assertEquals(2588, ack.getShort(28).toInt())
            client.expect(0x0356) // CP only; no 0317/0b07 while the fleet waits at its origin.
            val reserved = fixture.engine.snapshot().navigators.values.single()
            assertEquals(2487, reserved.grid); assertEquals(2588, reserved.warpTarget)
            fixture.engine.submit(WorldCommand.AdvanceTo(200_000))
            fixture.clock(client)
            assertEquals(2588, until(client, 0x0317).getShort(6).toInt())
            val moved = until(client, 0x0b07)
            assertEquals(2588, moved.getInt(14)); assertEquals(858, moved.getInt(29))
            val stored = fixture.engine.snapshot().navigators.values.single()
            assertEquals(2588, stored.grid); assertNull(stored.warpTarget); assertEquals(920, stored.mcp)
        }
    }

    @Test fun blockedReservationExplainsTheCancellationAndAllowsAnotherWarp() = blockedReservation(reconnecting = false)

    @Test fun blockedReservationDuringLoginWaitsForTheNativeGridBootstrap() = blockedReservation(reconnecting = true)

    @Test fun offlineCancellationSurvivesRestartAndInterruptedDelivery() =
        blockedReservation(reconnecting = false, offlineSettlement = true)

    private fun blockedReservation(reconnecting: Boolean, offlineSettlement: Boolean = false) = runBlocking<Unit> {
        val base = GalaxyBase(1177, 2015, 80, 0, "Alliance", 0, 20f, 86400, 0, 0f, 2f)
        val world = Galaxy(galaxy.systems.map { it.copy(power = if (it.grid == 2487) 2 else 3) }, listOf(base), galaxy.starts)
        val players = mapOf("load-001" to Navigator(1, 10001, Power.ALLIANCE, 2587, 1000, 1000, 1000))
        val stocks = ActiveShips.initialize(PlanetStocks(1, 0, mapOf(1177 to PlanetStock(1177, 3, 0, 100, 200, 300)),
            mapOf(1 to OutfitStock(1, 3, OutfitKind.FLEET, 1177, 10, 10001,
                UnitInventory(mapOf(UnitStack(119) to 1)), position = OutfitPosition(2587))),
            mapOf(10 to OutfitWarehouse(10, 3, OutfitKind.FLEET, 1177, 1)), deploymentInitialized = true))
        val stateDirectory = directory.resolve("world")
        FileWorldStateStore(stateDirectory).use { it.saveWorld(players, stocks) }
        fun fixture(store: FileWorldStateStore) = LoadFixture(directory, 2, LoadMetrics(), world, ticking = false,
            stockStore = store.stockStore, navigationStore = store.navigationStore, worldStateCommitter = store,
            powerByIndex = { if (it == 0) 2 else 3 })
        fun boot(client: SyntheticClient) {
            client.send(SyntheticClient.opcode(0x0f00)); until(client, 0x0f01)
            client.send(SyntheticClient.opcode(0x0f02)); until(client, 0x0f03)
            client.send(SyntheticClient.opcode(0x0300)); until(client, 0x0301)
        }
        fun assertNotice(client: SyntheticClient) {
            val notice = until(client, 0x0f1d).array()
            assertEquals(ChatMessages.Spot(10000, "[워프] 진입 불가·CP 미환불"), ChatMessages.spot(notice.copyOfRange(4, notice.size)))
        }
        FileWorldStateStore(stateDirectory).use { store -> fixture(store).use firstRun@{ fixture ->
            var pilot = fixture.join(fixture.account(0), true, false).client
            val rival = fixture.join(fixture.account(1), true, false).client
            for (client in listOf(pilot, rival)) {
                client.send(SyntheticClient.opcode(0x0f00)); until(client, 0x0f01)
            }
            warp(pilot, 2588)
            assertEquals(920, until(pilot, 0x0b01).getInt(24))
            pilot.expect(0x0356)
            warp(rival, 2588, 10001, 195)
            assertEquals(2588, until(rival, 0x0b07).getInt(14))
            assertEquals(OutfitPosition(2588), fixture.engine.snapshot().planetStocks!!.outfits.getValue(1).position)
            if (reconnecting || offlineSettlement) {
                pilot.close()
                fixture.awaitState(online = 1, connections = 1, participants = 2)
                if (reconnecting) pilot = fixture.join(fixture.account(0), false, false).client
            }
            fixture.engine.submit(WorldCommand.AdvanceTo(200_000))
            if (offlineSettlement) {
                val offline = fixture.engine.synchronizeTime().navigators.getValue(fixture.account(0))
                assertEquals(NavigationRejection.GRID, offline.offlineWarpCancellation)
                assertNull(offline.warpTarget); assertEquals(920, offline.mcp)
                assertEquals(2487, offline.grid); assertEquals(1000, offline.cruise)
                return@firstRun
            }
            pilot.send(SyntheticClient.opcode(0x0300))
            val moved = until(pilot, 0x0b07)
            assertEquals(2487, moved.getInt(14)); assertEquals(1000, moved.getInt(29))
            if (reconnecting) boot(pilot)
            assertNotice(pilot)
            val cancelled = fixture.engine.snapshot().navigators.getValue(fixture.account(0))
            assertNull(cancelled.warpTarget); assertEquals(920, cancelled.mcp)
            assertEquals(1000, cancelled.cruise); assertEquals(0L, cancelled.moves)
            warp(pilot, 2488)
            assertEquals(2488, until(pilot, 0x0b07).getInt(14))
            val arrived = fixture.engine.snapshot().navigators.getValue(fixture.account(0))
            assertEquals(880, arrived.mcp); assertEquals(900, arrived.cruise); assertEquals(1L, arrived.moves)
        } }
        if (!offlineSettlement) return@runBlocking
        FileWorldStateStore(stateDirectory).use { store -> fixture(store).use restarted@{ fixture ->
            val account = fixture.account(0)
            assertEquals(NavigationRejection.GRID, store.loadNavigation().getValue(account).offlineWarpCancellation)
            val first = fixture.join(account, false, false).client
            boot(first); assertNotice(first)
            first.close()
            fixture.awaitState(online = 0, connections = 0, participants = 1)
            assertEquals(NavigationRejection.GRID, store.loadNavigation().getValue(account).offlineWarpCancellation)
            val second = fixture.join(account, false, false).client
            boot(second); assertNotice(second)
            second.send(SyntheticClient.opcode(0x0300)); until(second, 0x0301)
            assertNull(store.loadNavigation().getValue(account).offlineWarpCancellation)
            second.close()
            fixture.awaitState(online = 0, connections = 0, participants = 1)
            val third = fixture.join(account, false, false).client
            boot(third)
            third.send(SyntheticClient.opcode(0x0300))
            repeat(300) {
                val message = third.receive()
                val opcode = ByteBuffer.wrap(message).getShort(4).toInt()
                if (opcode == 0x0f1d) {
                    val notice = ChatMessages.spot(message.copyOfRange(4, message.size))
                    assertFalse(notice.text.startsWith("[워프]"), "Acknowledged cancellation was sent again")
                }
                if (opcode == 0x0301) return@restarted
            }
            error("No clock response")
        } }
    }
}
