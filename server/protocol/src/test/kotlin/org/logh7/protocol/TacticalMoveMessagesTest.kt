package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalMoveMessagesTest {
    @Test fun serverOwnedNpcMoveUsesCharacterZeroButClientCannotSubmitIt() {
        val npc=move.copy(character=0,units=listOf(move.units.single().copy(id=1)))
        val packet=TacticalMoveMessages.reply(npc,100,5)
        assertEquals(0,ByteBuffer.wrap(packet).getInt(14));assertEquals(1,ByteBuffer.wrap(packet).getInt(19))
        assertFailsWith<IllegalArgumentException>{TacticalMoveMessages.move(packet.drop(4).toByteArray())}
    }
    private val move = TacticalMoveMessages.Move(10000,
        listOf(TacticalMoveMessages.Unit(1010000, 1.5f, TacticalMoveMessages.Point(12f, -3f, 20f))),
        2f, -0.5f, listOf(TacticalMoveMessages.Point(100f, 4f, -200f)))
    private fun request(move: TacticalMoveMessages.Move = this.move) =
        TacticalMoveMessages.reply(move, 123456, 5).let { it.copyOfRange(4, it.size) }

    @Test fun nativeMoveIsPackedAndUsesExplicitServerSchedule() {
        val bytes = ByteBuffer.wrap(TacticalMoveMessages.reply(move, 123456, 5))
        assertEquals(60, bytes.limit())
        assertEquals(0, bytes.getInt(0)); assertEquals(0x0400, bytes.getShort(4).toInt())
        assertEquals(123456, bytes.getInt(6)); assertEquals(5, bytes.getInt(10))
        assertEquals(10000, bytes.getInt(14)); assertEquals(1, bytes.get(18).toInt())
        assertEquals(1010000, bytes.getInt(19)); assertEquals(1.5f, bytes.getFloat(23))
        assertEquals(12f, bytes.getFloat(27)); assertEquals(-3f, bytes.getFloat(31)); assertEquals(20f, bytes.getFloat(35))
        assertEquals(2f, bytes.getFloat(39)); assertEquals(-0.5f, bytes.getFloat(43))
        assertEquals(1, bytes.get(47).toInt()); assertEquals(100f, bytes.getFloat(48))
        assertEquals(4f, bytes.getFloat(52)); assertEquals(-200f, bytes.getFloat(56))
        assertEquals(move, TacticalMoveMessages.move(request()))
        val untrusted = request().also { ByteBuffer.wrap(it).putInt(2, -1).putInt(6, -1) }
        assertEquals(move, TacticalMoveMessages.move(untrusted))
        assertEquals(1, ByteBuffer.wrap(TacticalMoveMessages.reply(move, 1, 5, false)).int)
    }

    @Test fun maximumNativeArraysRemainWithinTheirPackedBounds() {
        val maximum = move.copy(units = List(32) { move.units.single().copy(id = it + 1) },
            waypoints = List(32) { move.waypoints.single().copy(x = it.toFloat()) })
        assertEquals(1048, request(maximum).size)
        assertEquals(maximum, TacticalMoveMessages.move(request(maximum)))
        assertFailsWith<IllegalArgumentException> { move.copy(units = maximum.units + move.units) }
        assertFailsWith<IllegalArgumentException> { move.copy(waypoints = maximum.waypoints + move.waypoints) }
    }

    @Test fun malformedArraysNonFiniteCoordinatesAndInvalidIdentitiesAreRejected() {
        fun rejects(change: (ByteArray) -> ByteArray) {
            assertFailsWith<IllegalArgumentException> { TacticalMoveMessages.move(change(request())) }
        }
        rejects { it.copyOf(it.size - 1) }; rejects { it + byteArrayOf(0) }
        rejects { it.also { ByteBuffer.wrap(it).putShort(0, 0x0401) } }
        rejects { it.also { ByteBuffer.wrap(it).putInt(10, 0) } }
        rejects { it.also { ByteBuffer.wrap(it).putInt(15, -1) } }
        for (offset in listOf(14, 43)) for (count in listOf(0, 33, 255)) {
            rejects { it.also { it[offset] = count.toByte() } }
        }
        for (offset in listOf(19, 23, 27, 31, 35, 39, 44, 48, 52)) {
            for (number in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                rejects { it.also { ByteBuffer.wrap(it).putFloat(offset, number) } }
            }
        }
        rejects { it.also { ByteBuffer.wrap(it).putFloat(35, -1f) } }
        assertFailsWith<IllegalArgumentException> { move.copy(units = move.units + move.units) }
        assertFailsWith<IllegalArgumentException> { TacticalMoveMessages.reply(move, 0xffffffffL, 1) }
    }
}
