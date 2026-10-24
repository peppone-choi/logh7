package org.logh7.protocol

import java.nio.ByteBuffer
import kotlin.test.*
import org.junit.jupiter.api.Test

class NavigationMessagesTest {
    private val command = NavigationMessages.Warp(10000, 59, 2488, 0, 5)
    @Test fun warpUsesPackedWireFieldsAndIgnoresClientBalances() {
        val response = NavigationMessages.accepted(command, 42, 123, 456)
        assertEquals(37, response.size)
        val message = response.copyOfRange(4, response.size)
        assertEquals(33, message.size)
        assertEquals(command, NavigationMessages.warp(message))
        assertEquals(command, NavigationMessages.warp(message.copyOf().also {
            ByteBuffer.wrap(it).putInt(16, -1).putInt(20, Int.MIN_VALUE).put(26, -1)
        }))
        assertFailsWith<IllegalArgumentException> { NavigationMessages.warp(message.copyOf(32)) }
        assertFailsWith<IllegalArgumentException> { NavigationMessages.warp(message.copyOf().also { it[32] = 4 }) }
        assertFailsWith<IllegalArgumentException> { NavigationMessages.warp(message.copyOf().also { it[27] = -1 }) }
        assertEquals(1, ByteBuffer.wrap(NavigationMessages.rejected(command)).getInt(0))
    }
    @Test fun movedContainsAuthoritativeDestinationAndFuel() {
        val input = ByteBuffer.wrap(NavigationMessages.moved(command, 42, 1010000, 900))
        assertEquals(33, input.remaining())
        assertEquals(0, input.int) // S2C result field; absent from C2S command bodies.
        assertEquals(0x0b07, input.short.toInt())
        assertEquals(42, input.int); assertEquals(10000, input.int)
        assertEquals(2488, input.int); assertEquals(0, input.int)
        assertEquals(5, input.short.toInt()); assertEquals(1, input.get().toInt())
        assertEquals(1010000, input.int); assertEquals(900, input.int)
        assertFalse(input.hasRemaining())
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 3, 23, 9, -73, 0), NavigationMessages.grid(2487))
    }
}
