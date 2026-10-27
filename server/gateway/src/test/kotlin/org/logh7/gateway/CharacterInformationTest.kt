package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class CharacterInformationTest {
    @TempDir lateinit var directory: Path
    private val character = CharacterMessages.Generate(4,10000,2,0,0,"Viewer","Pilot",30,1,1,1000001,
        List(8) { 50 },0,0,0,20,0,0,"Ship",1)
    @Test fun profileVisibilityMatchesCurrentRoomFactionGenerationAndConnectionMembership() {
        val hub = GamePresence()
        val own = GamePresence.Member("viewer",character,
            Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,spotId=10490003),{})
        val peer = own.copy(connection="peer",character=character.copy(id=10001,power=3),
            player=own.player.copy(characterId=10001,power=Power.ALLIANCE))
        hub.update(own); hub.update(peer)
        assertEquals(own,hub.detail("viewer",10000)); assertEquals(peer,hub.detail("viewer",10001))
        assertNull(hub.detail("forged",10001)); assertNull(hub.detail("viewer",99999))
        for (player in listOf(peer.player.copy(spotId=10490004),peer.player.copy(spotOwner=10001),
            peer.player.copy(baseId=1050),peer.player.copy(grid=2488),peer.player.copy(generation=2),
            peer.player.copy(spotId=0))) {
            hub.update(peer.copy(player=player)); assertNull(hub.detail("viewer",10001))
        }
        val friendly = peer.copy(character=peer.character.copy(power=2),player=peer.player.copy(power=Power.EMPIRE,spotId=0))
        hub.update(friendly); assertEquals(friendly,hub.detail("viewer",10001))
        hub.remove("peer"); assertNull(hub.detail("viewer",10001))
        hub.remove("viewer"); assertNull(hub.detail("viewer",10000))
    }
    private fun opcode(bytes: ByteArray) = ByteBuffer.wrap(bytes).getShort(4).toInt() and 65535
    private fun through(client: SyntheticClient, end: Int): ByteArray {
        repeat(64) { val message=client.receive(); if (opcode(message)==end) return message }
        error("Missing reply $end")
    }
    private fun query(client: SyntheticClient, id: Int): ByteArray {
        client.send(ByteBuffer.allocate(6).putShort(0x354).putInt(id).array())
        return through(client,0x355)
    }
    @Test fun encryptedQueriesReturnLiveSelfAndPeerAndDenialDoesNotBreakTheCipher() {
        val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Empire")),emptyList(),
            mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false).use { fixture ->
            val first=fixture.join(fixture.account(0),true,false).client
            first.send(SyntheticClient.opcode(0x0f00)); through(first,0x0f01)
            val second=fixture.join(fixture.account(1),true,false).client
            second.send(SyntheticClient.opcode(0x0f00)); through(second,0x0f01)
            val own=query(first,10000); assertEquals(0,ByteBuffer.wrap(own).int)
            assertEquals(10000,ByteBuffer.wrap(own).getInt(6)); assertTrue(own[10]>0)
            val peer=query(first,10001); assertEquals(0,ByteBuffer.wrap(peer).int)
            assertEquals(10001,ByteBuffer.wrap(peer).getInt(6)); assertTrue(peer[10]>0)
            val denied=query(first,99999); assertEquals(1,ByteBuffer.wrap(denied).int)
            assertEquals(0,denied[10].toInt()); fixture.clock(first)
            second.close(); fixture.awaitState(online=1,connections=1,participants=2)
            assertEquals(1,ByteBuffer.wrap(query(first,10001)).int)
            assertEquals(0,ByteBuffer.wrap(query(first,10000)).int); fixture.clock(first)
        }
    }
}
