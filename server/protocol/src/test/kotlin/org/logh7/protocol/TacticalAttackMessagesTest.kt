package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalAttackMessagesTest {
    @Test fun attackModeIsIndependentOfShootTargetKindAndStopCarriesZero() {
        for(mode in 0..2) {
            val order=TacticalAttackMessages.Attack(10000,listOf(1010000),mode,0)
            val wire=TacticalAttackMessages.reply(order,100,if(mode==0)0 else 120)
            assertEquals(mode,wire[23].toInt())
            assertEquals(order,TacticalAttackMessages.attack(wire.drop(4).toByteArray()))
        }
    }
    @Test fun personalShieldOnlyAndFatalHitsUseAbsoluteFieldsWithoutInventingDestroyedBoats() {
        val shield=java.nio.ByteBuffer.wrap(TacticalAttackMessages.hit(100,1010000,0,1010001,0,0,4,12))
        assertEquals(1010001,shield.getInt(16));assertEquals(0,shield.getShort(20).toInt())
        assertEquals(0,shield.getShort(22).toInt());assertEquals(4,shield.get(24).toInt());assertEquals(12,shield.getShort(25).toInt())
        val dead=java.nio.ByteBuffer.wrap(TacticalAttackMessages.hit(100,1010000,1,1010001,1,1,0,20))
        assertEquals(1,dead.getShort(20).toInt());assertEquals(1,dead.getShort(22).toInt())
        assertFailsWith<IllegalArgumentException>{TacticalAttackMessages.hit(100,1010000,0,1010001,0)}
    }
    @Test fun hitNotificationMatchesPackedNativeReaderAndCarriesAbsoluteLosses() {
        val bytes=TacticalAttackMessages.hit(0x01020304,1010000,0,7,123)
        assertEquals("00000000042601020304000f69500001000000070000007bff000064",bytes.joinToString(""){"%02x".format(it)})
        assertEquals(28,bytes.size)
        assertFailsWith<IllegalArgumentException>{TacticalAttackMessages.hit(0,1,3,7,1)}
        assertFailsWith<IllegalArgumentException>{TacticalAttackMessages.hit(0,1,0,7,301)}
    }
    // Independent bytes in native writer order; not produced by the codec under test.
    private fun request(opcode: Int, units: List<Int>, shoot: Boolean = false): ByteArray =
        ByteBuffer.allocate(20 + units.size * 4 + if (shoot) 1 else 0)
            .putShort(opcode.toShort()).putInt(-1).putInt(Int.MAX_VALUE).putInt(10000)
            .put(units.size.toByte()).also { out ->
                units.forEach(out::putInt)
                if (shoot) out.put(2)
            }.put(1).putInt(990001).array()

    @Test fun attackAndShootHaveDifferentTailsAndAuthoritativeResponseSchedule() {
        val attack = TacticalAttackMessages.attack(request(0x0405, listOf(1010000, 1010001)))
        assertEquals(TacticalAttackMessages.Attack(10000, listOf(1010000, 1010001), 1, 990001), attack)
        val shoot = TacticalAttackMessages.shoot(request(0x0406, listOf(1010000), true))
        assertEquals(TacticalAttackMessages.Shoot(10000, listOf(1010000), 2, 1, 990001), shoot)
        for (packet in listOf(TacticalAttackMessages.reply(attack, 200, 120), TacticalAttackMessages.reply(shoot, 200, 120))) {
            val input = ByteBuffer.wrap(packet)
            assertEquals(0, input.int)
            assertTrue(input.short.toInt() in 0x0405..0x0406)
            assertEquals(200, input.int); assertEquals(120, input.int); assertEquals(10000, input.int)
        }
        assertEquals(32, TacticalAttackMessages.reply(attack, 200, 120).size)
        assertEquals(29, TacticalAttackMessages.reply(shoot, 200, 120).size)
        assertEquals(1, ByteBuffer.wrap(TacticalAttackMessages.reply(shoot, 200, 0, false)).int)
        assertFailsWith<IllegalArgumentException> { TacticalAttackMessages.reply(attack, 0xffffffffL, 1) }
    }

    @Test fun nativeArrayLimitAndExactSizeAreEnforcedBeforeAllocation() {
        assertEquals(32, TacticalAttackMessages.attack(request(0x0405, (1..32).toList())).units.size)
        for (bad in listOf(request(0x0405, emptyList()), request(0x0405, (1..33).toList()),
            request(0x0405, listOf(7, 7)), request(0x0405, listOf(0)), request(0x0405, listOf(-1)),
            request(0x0405, listOf(7)).dropLast(1).toByteArray(), request(0x0405, listOf(7)) + byteArrayOf(0),
            request(0x0406, listOf(7), true))) {
            assertFailsWith<IllegalArgumentException> { TacticalAttackMessages.attack(bad) }
        }
        for (bad in listOf(byteArrayOf(), request(0x0406, emptyList(), true), request(0x0406, (1..33).toList(), true),
            request(0x0406, listOf(7), true).dropLast(1).toByteArray(), request(0x0406, listOf(7), true) + byteArrayOf(0),
            request(0x0405, listOf(7)))) {
            assertFailsWith<IllegalArgumentException> { TacticalAttackMessages.shoot(bad) }
        }
    }
}
