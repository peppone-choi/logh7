package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class BootstrapMessagesTest {
    private val character = CharacterMessages.Generate(4, 10000, 2, 0, 0, "Alpha", "Pilot", 18, 1, 1,
        1_000_001, listOf(60) + List(7) { 50 }, 0, 0, 0, 20, 3, 0, "AlphaShip", 1)
    @Test fun initialCardTableAndCharacterUseMatchingRoleIds() {
        val cards = ByteBuffer.wrap(BootstrapMessages.response(byteArrayOf(3, 4))!!)
        cards.position(6)
        assertEquals(3, cards.short.toInt())
        val ids = List(3) { cards.short.toInt().also { cards.position(cards.position() + 17) } }
        assertEquals(listOf(0, 59, 195), ids); assertFalse(cards.hasRemaining())
        val body = BootstrapMessages.character(character)
        val tail = ByteBuffer.wrap(body, body.size - 14, 14)
        assertEquals(2, tail.get().toInt()); assertEquals(0, tail.short.toInt()); assertEquals(0, tail.int)
        assertEquals(59, tail.short.toInt()); assertEquals(1_010_000, tail.int); assertEquals(0, tail.get().toInt())
        val alliance = BootstrapMessages.character(character.copy(power = 3))
        assertEquals(195, ByteBuffer.wrap(alliance, alliance.size - 7, 2).short.toInt())
    }
    @Test fun fixedCatalogueLengthsFollowWireWidthsAndRejectPayloads() {
        assertEquals(438, BootstrapMessages.response(byteArrayOf(3, 0x10))!!.size)
        assertEquals(1376, BootstrapMessages.response(byteArrayOf(3, 8))!!.size)
        assertEquals(10, BootstrapMessages.response(byteArrayOf(3, 0x14))!!.size)
        assertEquals(6, BootstrapMessages.response(byteArrayOf(15, 2))!!.size)
        assertNull(BootstrapMessages.response(byteArrayOf(0x7f, 0)))
        assertFails { BootstrapMessages.response(byteArrayOf(3, 4, 0)) }
    }
}
