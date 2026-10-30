package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalSupportMessagesTest {
    @Test fun decodesFixedNativeCommandLayoutAndUsesAuthoritativeReplyClock() {
        for (action in TacticalSupportMessages.Action.entries) {
            val request = ByteBuffer.allocate(22).putShort(action.opcode.toShort())
                .putInt(-1).putInt(-1).putInt(10000).putInt(41).putInt(1010000).array()
            val command = TacticalSupportMessages.command(request)
            assertEquals(TacticalSupportMessages.Command(action, 10000, 41, 1010000), command)
            val reply = TacticalSupportMessages.reply(command, 777600, 120)
            assertEquals(26, reply.size)
            val out = ByteBuffer.wrap(reply)
            assertEquals(0, out.int)
            assertEquals(action.opcode, out.short.toInt() and 65535)
            assertEquals(777600, out.int); assertEquals(120, out.int)
            assertEquals(10000, out.int); assertEquals(41, out.int); assertEquals(1010000, out.int)
            assertFalse(out.hasRemaining())
            assertEquals(command, TacticalSupportMessages.command(reply.copyOfRange(4, reply.size)))
            val denied = TacticalSupportMessages.reply(command, 777600, 0, accepted = false)
            assertEquals(1, ByteBuffer.wrap(denied).int)
            assertContentEquals(reply.copyOfRange(14, reply.size), denied.copyOfRange(14, denied.size))
        }
    }

    @Test fun rejectsMalformedRequestsWithoutTreatingAnotherTacticalOpcodeAsSupport() {
        val valid = ByteBuffer.allocate(22).putShort(0x0413).putLong(0).putInt(10000)
            .putInt(41).putInt(1010000).array()
        for (size in 0 until valid.size) {
            assertFailsWith<IllegalArgumentException> { TacticalSupportMessages.command(valid.copyOf(size)) }
        }
        assertFailsWith<IllegalArgumentException> { TacticalSupportMessages.command(valid + byteArrayOf(0)) }
        assertFailsWith<IllegalArgumentException> {
            TacticalSupportMessages.command(valid.copyOf().also { ByteBuffer.wrap(it).putShort(0, 0x0406) })
        }
        for (offset in listOf(10, 14, 18)) {
            for (invalid in listOf(0, -1)) {
                assertFailsWith<IllegalArgumentException> {
                    TacticalSupportMessages.command(valid.copyOf().also { ByteBuffer.wrap(it).putInt(offset, invalid) })
                }
            }
        }
        val command = TacticalSupportMessages.command(valid)
        assertFailsWith<IllegalArgumentException> { TacticalSupportMessages.reply(command, -1, 0) }
        assertFailsWith<IllegalArgumentException> { TacticalSupportMessages.reply(command, 0xffffffffL, 1) }
    }

    @Test fun notificationsUsePackedAbsoluteBalancesAndUnsignedDamageWithoutTimePrefix() {
        val repair = TacticalSupportMessages.repaired(41, 1010000, 12000, 65535)
        assertEquals(20, repair.size)
        val r = ByteBuffer.wrap(repair)
        assertEquals(0, r.int); assertEquals(0x042d, r.short.toInt())
        assertEquals(41, r.int); assertEquals(1010000, r.int); assertEquals(12000, r.int)
        assertEquals(65535, r.short.toInt() and 65535); assertFalse(r.hasRemaining())
        val supply = TacticalSupportMessages.supplied(42, 1010000, 0, 10000)
        assertEquals(22, supply.size)
        val s = ByteBuffer.wrap(supply)
        assertEquals(0, s.int); assertEquals(0x042e, s.short.toInt())
        assertEquals(42, s.int); assertEquals(1010000, s.int)
        assertEquals(0, s.int); assertEquals(10000, s.int); assertFalse(s.hasRemaining())
        assertFailsWith<IllegalArgumentException> { TacticalSupportMessages.repaired(41, 1, 0, 65536) }
        assertFailsWith<IllegalArgumentException> { TacticalSupportMessages.repaired(41, 1, -1, 0) }
        assertFailsWith<IllegalArgumentException> { TacticalSupportMessages.supplied(42, 1, 0, -1) }
    }
}
