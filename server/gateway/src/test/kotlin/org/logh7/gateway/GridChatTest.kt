package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class GridChatTest {
    @TempDir lateinit var directory: Path
    private fun code(packet: ByteArray) = ByteBuffer.wrap(packet).getShort(4).toInt() and 65535
    private fun body(packet: ByteArray) = packet.copyOfRange(4, packet.size)
    @Test fun allFactionAndFleetCastsFollowCurrentMembershipAndNeverCrossGridOrGeneration() {
        val hub = GamePresence()
        val received = (0..5).associateWith { mutableListOf<List<ByteArray>>() }
        val character = CharacterMessages.Generate(4, 10000, 2, 0, 0, "Alpha", "Pilot", 30, 1, 1, 1000001,
            List(8) { 50 }, 0, 0, 0, 20, 0, 0, "Ship", 1)
        val own = GamePresence.Member("0", character,
            Navigator(1, 10000, Power.EMPIRE, 2487, 1000, 1000, 1000, baseId = 1049, mode = 4, spotId = 10490003),
            received.getValue(0)::add, outfit = 11)
        val members = (0..5).map { index -> own.copy(connection = "$index", character = character.copy(id = 10000L + index),
            player = own.player.copy(characterId = 10000 + index), send = received.getValue(index)::add) }.toMutableList()
        members[2] = members[2].copy(character = members[2].character.copy(power = 3), player = members[2].player.copy(power = Power.ALLIANCE))
        members[3] = members[3].copy(player = members[3].player.copy(grid = 2488))
        members[4] = members[4].copy(player = members[4].player.copy(generation = 2))
        members[5] = members[5].copy(outfit = 12, player = members[5].player.copy(baseId = 1049, mode = 4, spotId = 10490003))
        members.forEach { hub.update(it) }
        fun chat(cast: Int, expected: Set<Int>) {
            received.values.forEach { it.clear() }
            val message = ChatMessages.Grid(10000, cast, "한글 日本 A")
            val echo = hub.gridChat("0", message, 123)
            assertEquals(message, ChatMessages.grid(body(echo)))
            assertEquals(123, ByteBuffer.wrap(echo).getInt(6))
            assertTrue(received.getValue(0).isEmpty())
            assertEquals(expected, received.filterValues { it.isNotEmpty() }.keys)
            expected.forEach { assertContentEquals(echo, received.getValue(it).single().single()) }
        }
        chat(0, setOf(1,2,5)); chat(1, setOf(1,5)); chat(2, setOf(1))
        hub.update(members[1].copy(outfit = 0)); chat(2, emptySet())
        hub.update(members[0].copy(outfit = 0)); chat(2, emptySet()) // Unassigned players never become one shared fleet.
        hub.update(members[1].copy(outfit = 0, player = members[1].player.copy(grid = 2488))); chat(0, setOf(2,5))
        hub.remove("2"); chat(0, setOf(5))
        assertFailsWith<IllegalArgumentException> { hub.gridChat("0", ChatMessages.Grid(10001,0,"Spoof"),0) }
        assertFailsWith<IllegalStateException> { hub.gridChat("absent", ChatMessages.Grid(10000,0,"Absent"),0) }
    }
    private fun through(client: SyntheticClient, end: Int): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        repeat(100) { val packet = client.receive(); result += packet; if (code(packet) == end) return result }
        error("Missing opcode $end")
    }
    @Test fun encryptedGridChatEchoesOncePreservesUnicodeAndKeepsCipherAlive() {
        val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2), GalaxySystem(4,2015,4,"Alliance",3)),
            listOf(GalaxyBase(1049,2487,3,0,"Odin",0,1f,1,0,0f,1f)),
            mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false).use { fixture ->
            val first = fixture.join(fixture.account(0),true,false).client
            first.send(SyntheticClient.opcode(0x0f00)); through(first,0x0f01)
            val second = fixture.join(fixture.account(1),true,false).client
            second.send(SyntheticClient.opcode(0x0f00)); through(second,0x0f01)
            fun drain(client: SyntheticClient): List<ByteArray> {
                client.send(SyntheticClient.opcode(0x0300)); return through(client,0x0301)
            }
            drain(first); drain(second)
            fun chat(client: SyntheticClient, id: Int, cast: Int, text: String) =
                client.send(body(ChatMessages.grid(ChatMessages.Grid(id,cast,text),0xffffffffL)))
            for (text in listOf("","   ")) chat(first,10000,0,text)
            assertFalse(drain(first).any { code(it)==0x0f1c }); assertFalse(drain(second).any { code(it)==0x0f1c })
            for (cast in 0..1) {
                chat(first,10000,cast,"안녕 日本 A")
                val echo = through(first,0x0f1c).last()
                assertEquals("안녕 日本 A", ChatMessages.grid(body(echo)).text)
                assertEquals(fixture.engine.snapshot().session!!.gameSeconds.toInt(), ByteBuffer.wrap(echo).getInt(6))
                assertContentEquals(echo,through(second,0x0f1c).last())
                assertFalse(drain(first).any { code(it)==0x0f1c }); assertFalse(drain(second).any { code(it)==0x0f1c })
            }
            chat(first,10000,2,"No fleet"); through(first,0x0f1c)
            assertFalse(drain(second).any { code(it)==0x0f1c })
            chat(second,10001,0,"답장")
            assertContentEquals(through(second,0x0f1c).last(),through(first,0x0f1c).last())
            chat(first,10001,0,"Spoof"); first.expectClosed()
            assertFalse(drain(second).any { code(it)==0x0f1c })
        }
    }
}
