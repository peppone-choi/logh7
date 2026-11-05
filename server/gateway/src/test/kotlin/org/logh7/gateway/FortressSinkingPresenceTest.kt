package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages
import java.nio.ByteBuffer
import kotlin.test.*

/** PR #214 review: a flagship sunk inside a breached fortress moves its docked owner's client. Author: Peppone Choi. */
class FortressSinkingPresenceTest {
    private fun opcode(packet:ByteArray)=ByteBuffer.wrap(packet).getShort(4).toInt() and 65535
    private val character=CharacterMessages.Generate(4,10001,3,0,0,"Pilot","Pilot",30,1,1,1000001,List(8){50},0,0,0,20,0,0,"Ship",1)

    @Test fun dockedTacticalOwnerLeavesTheBattleAndGetsItsReturnLocation() {
        val hub=GamePresence();val received=mutableListOf<List<ByteArray>>()
        val docked=Navigator(1,10001,Power.ALLIANCE,2487,1000,1000,900,baseId=1002,mode=4,cpPaused=true)
        hub.update(GamePresence.Member("one",character,docked,received::add));received.clear()
        val returned=docked.copy(grid=2490,baseId=1004,cpPaused=false,returnBase=1004)
        hub.fortressSunk(returned,character,emptyList(),100)
        val packets=received.single()
        assertEquals(0x0f1f,opcode(packets.first()),"tactical exit first")
        assertEquals(2487,ByteBuffer.wrap(packets.first()).getInt(7))
        assertEquals(returned,hub.communicationMember("one")!!.player)
        // Not docked in tactical: no exit, still the character and unit refresh.
        received.clear()
        hub.fortressSunk(returned.copy(baseId=1005),character,emptyList(),101)
        assertTrue(received.single().none {opcode(it)==0x0f1f})
        // An unknown character or another generation is ignored.
        received.clear();hub.fortressSunk(returned.copy(characterId=10009),character,emptyList(),102)
        hub.fortressSunk(returned.copy(generation=2),character,emptyList(),102)
        assertTrue(received.isEmpty())
    }
}
