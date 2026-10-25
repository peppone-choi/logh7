package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class SpotChatTest {
    @TempDir lateinit var directory: Path
    private fun code(packet: ByteArray) = ByteBuffer.wrap(packet).getShort(4).toInt() and 65535
    private fun body(packet: ByteArray) = packet.copyOfRange(4, packet.size)
    private fun through(client: SyntheticClient, end: Int): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        repeat(100) { val packet = client.receive(); result += packet; if (code(packet) == end) return result }
        error("Missing opcode $end")
    }
    @Test fun roomRoutingIsCrossFactionButNeverCrossesLocationPrivateOwnerOrGeneration() {
        val hub = GamePresence()
        val character = CharacterMessages.Generate(4, 10000, 2, 0, 0, "Alpha", "Pilot", 30, 1, 1, 1000001,
            List(8) { 50 }, 0, 0, 0, 20, 0, 0, "Ship", 1)
        val self = mutableListOf<List<ByteArray>>()
        val peer = mutableListOf<List<ByteArray>>()
        val own = GamePresence.Member("self", character,
            Navigator(1, 10000, Power.EMPIRE, 2487, 1000, 1000, 1000, baseId = 1049, mode = 4, spotId = 10490003), self::add)
        val other = own.copy(connection = "peer", character = character.copy(id = 10001, power = 3),
            player = own.player.copy(characterId = 10001, power = Power.ALLIANCE), send = peer::add)
        hub.update(own); hub.update(other); self.clear(); peer.clear()
        val message = ChatMessages.Spot(10000, "같은 방")
        val echo = hub.spotChat("self", message, 123)
        assertTrue(self.isEmpty())
        assertContentEquals(echo, peer.single().single())
        assertEquals(message, ChatMessages.spot(body(echo)))
        assertEquals(123, ByteBuffer.wrap(echo).getInt(6))
        for (location in listOf(other.player.copy(spotId = 10490605), other.player.copy(spotOwner = 10001),
            other.player.copy(baseId = 1050), other.player.copy(grid = 2488), other.player.copy(generation = 2))) {
            hub.update(other.copy(player = location)); peer.clear()
            hub.spotChat("self", message, 124)
            assertTrue(peer.isEmpty())
        }
        hub.update(own.copy(player = own.player.copy(baseId = 0, spotId = 0, mode = 5)))
        hub.update(other.copy(player = other.player.copy(baseId = 0, spotId = 0, mode = 5))); peer.clear()
        hub.spotChat("self", message, 125)
        assertTrue(peer.isEmpty())
        assertFailsWith<IllegalArgumentException> { hub.spotChat("self", message.copy(characterId = 10001), 0) }
        hub.remove("peer"); peer.clear(); hub.spotChat("self", message, 126)
        assertTrue(peer.isEmpty())
        assertFailsWith<IllegalStateException> { hub.spotChat("absent", message, 0) }
    }
    @Test fun encryptedClientsExchangeChatOnceAndStopReceivingAfterChangingRooms() {
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire", 2), GalaxySystem(4, 2015, 4, "Alliance", 3)),
            listOf(GalaxyBase(1049, 2487, 3, 0, "Odin", 0, 1f, 1, 0, 0f, 1f)),
            mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
        LoadFixture(directory, 2, LoadMetrics(), galaxy, ticking = false).use { fixture ->
            val first = fixture.join(fixture.account(0), true, false).client
            first.send(SyntheticClient.opcode(0x0f00)); through(first, 0x0f01)
            val second = fixture.join(fixture.account(1), true, false).client
            second.send(SyntheticClient.opcode(0x0f00)); through(second, 0x0f01); through(first, 0x0b0a)
            var elapsed = 0L
            fun dock(client: SyntheticClient, id: Int) {
                client.send(body(PlaceMessages.docked(PlaceMessages.Dock(id, 59, 1049, 4), 0, 0, 0)))
                through(client, 0x0b00) // Peer unit updates must not stand in for our command's ACK.
                through(client, 0x0325)
                elapsed += 8 * 3600_000L / 24
                kotlinx.coroutines.runBlocking { fixture.engine.submit(WorldCommand.AdvanceTo(elapsed)) }
                through(client, 0x0b0b)
                client.send(body(PlaceMessages.moved(PlaceMessages.Move(id, 10490001, 0), 0, 0, 0)))
                through(client, 0x0e00)
            }
            dock(first, 10000); dock(second, 10001)
            // Clock queries drain independent presence notifications and verify cipher continuity.
            fun drain(client: SyntheticClient): List<ByteArray> {
                client.send(SyntheticClient.opcode(0x0300)); return through(client, 0x0301)
            }
            drain(first); drain(second)
            fun chat(client: SyntheticClient, id: Int, text: String) =
                client.send(body(ChatMessages.spot(ChatMessages.Spot(id, text), 0xffffffffL)))
            chat(first, 10000, ""); chat(first, 10000, "   ")
            assertFalse(drain(first).any { code(it) == 0x0f1d }) // Empty input must not disconnect or broadcast.
            assertFalse(drain(second).any { code(it) == 0x0f1d })
            chat(first, 10000, "Hello 안녕")
            val echoed = through(first, 0x0f1d).last()
            assertEquals(fixture.engine.snapshot().session!!.gameSeconds.toInt(), ByteBuffer.wrap(echoed).getInt(6)) // Server clock replaces client time.
            assertContentEquals(echoed, through(second, 0x0f1d).last())
            chat(second, 10001, "Reply")
            assertContentEquals(through(second, 0x0f1d).last(), through(first, 0x0f1d).last())
            assertFalse(drain(first).any { code(it) == 0x0f1d })
            assertFalse(drain(second).any { code(it) == 0x0f1d })
            second.send(body(PlaceMessages.moved(PlaceMessages.Move(10001, 10490003, 0), 0, 0, 0)))
            through(second, 0x0e00); drain(first); drain(second)
            chat(first, 10000, "Lobby only")
            through(first, 0x0f1d)
            assertFalse(drain(second).any { code(it) == 0x0f1d })
            chat(first, 10001, "Spoof")
            first.expectClosed()
        }
    }
}
