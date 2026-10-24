package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class MultiplayerPresenceTest {
    @TempDir lateinit var directory: Path
    private fun opcode(bytes: ByteArray) = ByteBuffer.wrap(bytes).getShort(4).toInt() and 65535
    private fun readThrough(client: SyntheticClient, end: Int): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        repeat(32) { val message = client.receive(); result += message; if (opcode(message) == end) return result }
        error("Transaction did not end")
    }
    private fun initialize(client: SyntheticClient): List<Long> {
        client.send(SyntheticClient.opcode(0x0f00))
        return readThrough(client, 0x0f01).filter { opcode(it) == 0x0323 }
            .map { ByteBuffer.wrap(it).getInt(6).toLong() }
    }
    private fun warp(client: SyntheticClient, id: Int, destination: Int): List<ByteArray> {
        val body = NavigationMessages.accepted(NavigationMessages.Warp(id, 59, destination, 0, 5), 0, 0, 0)
        client.send(body.copyOfRange(4, body.size))
        val result = readThrough(client, 0x0b07)
        assertEquals(listOf(0x0b01, 0x0356, 0x0325, 0x0317, 0x0b07), result.map(::opcode))
        return readThrough(client, 0x0b0a)
    }
    @Test fun twoEncryptedClientsShareEntryMovementDisconnectAndReconnectionWithoutMovingEachOther() {
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire"), GalaxySystem(4, 2015, 4, "Alliance")),
            emptyList(), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
        LoadFixture(directory, 3, LoadMetrics(), galaxy).use { fixture ->
            val first = fixture.join(fixture.account(0), create = true, initialize = false).client
            assertEquals(listOf(10000L), initialize(first))
            val second = fixture.join(fixture.account(1), create = true, initialize = false).client
            assertEquals(listOf(10001L, 10000L), initialize(second))
            val joined = readThrough(first, 0x0b0a)
            assertEquals(listOf(0x0b09, 0x0323, 0x0b0a), joined.map(::opcode))
            assertEquals(10001, ByteBuffer.wrap(joined[1]).getInt(6))

            val leftOnFirst = warp(first, 10000, 2488)
            assertEquals(listOf(0x0b08, 0x0b09, 0x0b0a), leftOnFirst.map(::opcode))
            assertEquals(1010001, ByteBuffer.wrap(leftOnFirst[0]).getInt(7))
            val leftOnSecond = readThrough(second, 0x0b0a)
            assertEquals(1010000, ByteBuffer.wrap(leftOnSecond[0]).getInt(7))
            assertEquals(2487, fixture.engine.snapshot().navigators.getValue(fixture.account(1)).grid)

            val metOnSecond = warp(second, 10001, 2488)
            assertEquals(listOf(0x0b09, 0x0323, 0x0b0a), metOnSecond.map(::opcode))
            val metOnFirst = readThrough(first, 0x0b0a)
            assertEquals(10001, ByteBuffer.wrap(metOnFirst[1]).getInt(6))
            assertEquals(960, fixture.engine.snapshot().navigators.getValue(fixture.account(0)).mcp)

            second.close()
            val disconnected = readThrough(first, 0x0b0a)
            assertEquals(1010001, ByteBuffer.wrap(disconnected[0]).getInt(7))
            fixture.awaitState(online = 1, connections = 1, participants = 2)
            val restored = fixture.join(fixture.account(1), create = false, initialize = false).client
            assertEquals(listOf(10001L, 10000L), initialize(restored))
            assertEquals(10001, ByteBuffer.wrap(readThrough(first, 0x0b0a)[1]).getInt(6))
            assertEquals(2488, fixture.engine.snapshot().navigators.getValue(fixture.account(1)).grid)

            val duplicate = fixture.join(fixture.account(1), create = false, initialize = false)
            assertFalse(duplicate.accepted)
            fixture.awaitState(online = 2, connections = 2, participants = 2)
            fixture.clock(first); fixture.clock(restored) // Independent cipher sequences still accept requests.
        }
    }
    @Test fun visibilityNeverCrossesFactionGenerationOrGridAndStaleCloseCannotRemoveReconnect() {
        val hub = GamePresence()
        val messages = mutableListOf<List<ByteArray>>()
        val generated = CharacterMessages.Generate(4, 10000, 2, 0, 0, "Alpha", "Pilot", 30, 1, 1, 1,
            List(8) { 50 }, 0, 0, 0, 20, 0, 0, "Ship", 1)
        val own = GamePresence.Member("first", generated, Navigator(1, 10000, Power.EMPIRE, 2487, 1000, 1000, 1000), messages::add)
        hub.update(own)
        for (player in listOf(own.player.copy(characterId = 10001, power = Power.ALLIANCE),
            own.player.copy(characterId = 10002, generation = 2), own.player.copy(characterId = 10003, grid = 2488))) {
            assertTrue(hub.update(GamePresence.Member("other-${player.characterId}", generated.copy(id = player.characterId.toLong()), player, {})).peers.isEmpty())
        }
        assertTrue(messages.isEmpty())
        hub.remove("first")
        hub.update(own.copy(connection = "reconnected"))
        hub.remove("first")
        assertEquals(listOf(10000L), hub.update(own.copy(connection = "new", character = generated.copy(id = 10004),
            player = own.player.copy(characterId = 10004))).peers.map { it.character.id })
    }
}
