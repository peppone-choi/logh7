package org.logh7.protocol

import java.nio.ByteBuffer
import kotlin.test.*
import org.junit.jupiter.api.Test

class CommanderCommandsTest {
    @Test fun bothNativeCataloguesExposeReorganizationOnlyOnCommanderCards() {
        val definitions = ByteBuffer.wrap(WorldMessages.cards())
        val permissions = ByteBuffer.wrap(WorldMessages.captainCommands())
        assertEquals(261, definitions.short.toInt())
        assertEquals(261, permissions.short.toInt())
        val commanders = setOf(39,44,47,50,175,180,183,186)
        for (card in 0..260) {
            assertEquals(card, definitions.short.toInt())
            definitions.position(definitions.position()+16)
            val listed = List(definitions.get().toInt() and 255) { definitions.short.toInt() }
            assertEquals(card, permissions.short.toInt())
            val allowed = List(permissions.get().toInt() and 255) {
                permissions.short.toInt().also { permissions.position(permissions.position()+6) }
            }
            assertEquals(listed, allowed, "Card $card has inconsistent native catalogues")
            assertEquals(card in commanders, 63 in listed, "Reorganization on card $card")
        }
        assertFalse(definitions.hasRemaining())
        assertFalse(permissions.hasRemaining())
    }
}
