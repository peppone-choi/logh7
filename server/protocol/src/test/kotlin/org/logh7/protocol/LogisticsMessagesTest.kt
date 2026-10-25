package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class LogisticsMessagesTest {
    private fun ByteBuffer.stack(kind: Int, level: Int, quantity: Int): ByteBuffer =
        putShort(kind.toShort()).put(level.toByte()).putShort(quantity.toShort())
    private fun reorganization() = ByteBuffer.allocate(52).putShort(0x0c02)
        .putInt(11).putInt(22).put(1).putInt(33).putInt(44).putInt(1049).putInt(700)
        .put(2).put(1).stack(32, 3, 65535).put(1).stack(1003, 2, 32768)
        .putInt(55).putInt(66).putInt(77).array()
    private fun assignment() = ByteBuffer.allocate(75).putShort(0x0c0b)
        .putInt(11).putInt(22).putInt(1).putInt(33).putInt(44).putInt(1049).put(2)
        .put(1).stack(32, 3, 7).put(1).stack(1003, 2, 8).putInt(55)
        .put(1).stack(40, 1, 9).put(1).stack(1000, 0, 10).putInt(700)
        .put(1).stack(56, 0, 11).put(1).stack(1003, 2, 12).putInt(66).array()

    @Test fun nativeReorganizationHasBytePhaseAndFiveByteUnalignedStacks() {
        val result = LogisticsMessages.reorganization(reorganization())
        assertEquals(LogisticsMessages.Header(11,22,33,44),result.header)
        assertEquals(1,result.phase); assertEquals(1049,result.base); assertEquals(700,result.outfit)
        assertEquals(2,result.category)
        assertEquals(listOf(LogisticsMessages.Stack(32,3,65535)),result.moves.ships)
        // Signed transfer interpretation belongs to the engine, not the lossless wire reader.
        assertEquals(listOf(LogisticsMessages.Stack(1003,2,32768)),result.moves.troops)
        assertEquals(listOf(55,66,77),listOf(result.tailFirst,result.tailSecond,result.tailThird))
    }
    @Test fun assignmentHasIntPhaseAndAllSixListsInTheirOriginalOrder() {
        val result = LogisticsMessages.assignment(assignment())
        assertEquals(LogisticsMessages.Header(11,22,33,44),result.header)
        assertEquals(1,result.phase); assertEquals(1049,result.base); assertEquals(2,result.category)
        assertEquals(7,result.moves.ships.single().quantity); assertEquals(8,result.moves.troops.single().quantity)
        assertEquals(55,result.baseMetadata)
        assertEquals(40,result.resultBase.ships.single().kind); assertEquals(10,result.resultBase.troops.single().quantity)
        assertEquals(700,result.outfit)
        assertEquals(56,result.resultOutfit.ships.single().kind); assertEquals(12,result.resultOutfit.troops.single().quantity)
        assertEquals(66,result.tail)
    }
    @Test fun emptyNativeCommandsHaveDifferentFixedLengths() {
        assertEquals(0,LogisticsMessages.reorganization(ByteBuffer.allocate(42).putShort(0x0c02).array()).moves.ships.size)
        assertEquals(0,LogisticsMessages.assignment(ByteBuffer.allocate(45).putShort(0x0c0b).array()).resultOutfit.ships.size)
    }
    @Test fun rejectEveryTruncationWrongOpcodeTrailingBytesAndExcessiveCounts() {
        for ((bytes, read) in listOf(reorganization() to LogisticsMessages::reorganization,
            assignment() to LogisticsMessages::assignment)) {
            for (size in 0 until bytes.size) assertFails { read(bytes.copyOf(size)) }
            assertFails { read(bytes + 0) }
            assertFails { read(bytes.copyOf().also { it[1] = 0 }) }
        }
        assertFails { LogisticsMessages.reorganization(reorganization().also { it[28] = 100 }) }
        assertFails { LogisticsMessages.reorganization(reorganization().also { it[34] = 25 }) }
        // Every list has its own capacity check, including server-result sections.
        for ((offset, count) in listOf(27 to 100,33 to 25,43 to 100,49 to 25,59 to 100,65 to 25)) {
            assertFails { LogisticsMessages.assignment(assignment().also { it[offset] = count.toByte() }) }
        }
    }
}
