package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class LogisticsMessagesTest {
    @Test fun reorganizationPreviewPreservesNegativeCrewChangeAndCurrentSupplies() {
        val query=LogisticsMessages.reorganization(ByteBuffer.allocate(42).putShort(0x0c02).array())
        val response=LogisticsMessages.reorganizationPreview(query,1,1000,1000,0,-5,789,true)
        val decoded=LogisticsMessages.reorganization(response.copyOfRange(4,response.size))
        assertEquals(-5,decoded.maxCrew);assertEquals(789,decoded.supplies)
    }
    private fun ByteBuffer.stack(kind: Int, level: Int, quantity: Int): ByteBuffer =
        putShort(kind.toShort()).put(level.toByte()).putShort(quantity.toShort())
    private fun reorganization() = ByteBuffer.allocate(52).putShort(0x0c02)
        .putInt(11).putInt(22).put(1).putInt(33).putInt(44).putInt(1049).putInt(700)
        .put(2).put(1).stack(32, 3, 65535).put(1).stack(1003, 2, 32768)
        .putInt(55).putInt(66).putInt(77).array()
    private fun assignment() = ByteBuffer.allocate(75).putShort(0x0c0b)
        .putInt(11).putInt(22).putInt(33).putInt(44).putInt(1049).putInt(700).put(2)
        .put(1).stack(32, 3, 7).put(1).stack(1003, 2, 8).putInt(55)
        .put(1).stack(40, 1, 9).put(1).stack(1000, 0, 10).putInt(77)
        .put(1).stack(56, 0, 11).put(1).stack(1003, 2, 12).putInt(66).array()

    @Test fun nativeReorganizationHasByteModeAndSeparateShipAndTroopFields() {
        val result = LogisticsMessages.reorganization(reorganization())
        assertEquals(LogisticsMessages.Header(11,22,33,44),result.header)
        assertEquals(1,result.mode); assertEquals(1049,result.base); assertEquals(700,result.outfit)
        assertEquals(2,result.kind)
        assertEquals(listOf(LogisticsMessages.ShipMove(32,3,65535)),result.moves.ships)
        // Troop delta is signed; ship byte is units, not a grade.
        assertEquals(listOf(LogisticsMessages.TroopMove(1003,2,-32768)),result.moves.troops)
        assertEquals(listOf(55,66,77),listOf(result.maxTroops,result.maxCrew,result.supplies))
    }
    @Test fun strategicReplyUsesAuthoritativeHeaderAndDoesNotEchoCapacityHintsOrRejectedMoves() {
        val query=LogisticsMessages.reorganization(reorganization())
        for (accepted in listOf(true,false)) {
            val bytes=LogisticsMessages.reorganizationResponse(query,0xffffffffL,123,456,789,accepted)
            assertEquals(if (accepted) 0 else 1,ByteBuffer.wrap(bytes).int)
            val result=LogisticsMessages.reorganization(bytes.copyOfRange(4,bytes.size))
            assertEquals(LogisticsMessages.Header(-1,22,123,456),result.header)
            assertEquals(1,result.mode); assertEquals(0,result.maxTroops); assertEquals(0,result.maxCrew)
            assertEquals(789,result.supplies)
            assertEquals(if (accepted) query.moves else LogisticsMessages.Moves(emptyList(),emptyList()),result.moves)
        }
        assertFails { LogisticsMessages.reorganizationResponse(query.copy(mode=0),1,1,1,0,true) }
    }
    @Test fun assignmentHasNoModeAndKeepsAllSixListsAndSupplyFields() {
        val result = LogisticsMessages.assignment(assignment())
        assertEquals(LogisticsMessages.Header(11,22,33,44),result.header)
        assertEquals(1049,result.base); assertEquals(700,result.outfit); assertEquals(2,result.kind)
        assertEquals(7,result.moves.ships.single().boats); assertEquals(8,result.moves.troops.single().units)
        assertEquals(55,result.moveSupplies)
        assertEquals(40,result.resultBase.ships.single().kind); assertEquals(10,result.resultBase.troops.single().units)
        assertEquals(77,result.resultBaseSupplies)
        assertEquals(56,result.resultOutfit.ships.single().kind); assertEquals(12,result.resultOutfit.troops.single().units)
        assertEquals(66,result.resultOutfitSupplies)
    }
    @Test fun encodersPreserveIndependentNativeFieldOrder() {
        val reorg = LogisticsMessages.reorganization(reorganization())
        val assignment = LogisticsMessages.assignment(assignment())
        assertContentEquals(reorganization(),LogisticsMessages.encode(reorg))
        assertContentEquals(assignment(),LogisticsMessages.encode(assignment))
        val signed = reorg.copy(moves=LogisticsMessages.Moves(
            listOf(LogisticsMessages.ShipMove(32,-1,300)),
            listOf(LogisticsMessages.TroopMove(1003,2,-1))))
        val wire = LogisticsMessages.encode(signed)
        assertEquals(255,wire[31].toInt() and 255)
        assertEquals(65535,ByteBuffer.wrap(wire).getShort(38).toInt() and 65535)
        assertEquals(signed,LogisticsMessages.reorganization(wire))
        // Result counts use unsigned ranges even where move deltas use signed ones.
        val large = assignment.copy(resultBase=LogisticsMessages.Inventory(
            listOf(LogisticsMessages.ShipStock(32,255,65535)),
            listOf(LogisticsMessages.TroopStock(1003,2,65535))))
        assertEquals(large,LogisticsMessages.assignment(LogisticsMessages.encode(large)))
        val returned = large.copy(moves=signed.moves,moveSupplies=-55)
        val returnedWire = LogisticsMessages.encode(returned)
        assertEquals(-55,ByteBuffer.wrap(returnedWire).getInt(39))
        assertEquals(returned,LogisticsMessages.assignment(returnedWire))
        assertFails { LogisticsMessages.ShipMove(32,128,300) }
        assertFails { LogisticsMessages.TroopMove(1003,2,32768) }
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
