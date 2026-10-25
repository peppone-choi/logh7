package org.logh7.protocol

import java.nio.ByteBuffer

/** CD writers 00551a80/00553fc0, readers 00555eb0/00558b70. Author: 최병호. */
object LogisticsMessages {
    // Client clock, identity and balances are transport fields, never authority.
    data class Header(val time: Int, val characterId: Int, val pcp: Int, val mcp: Int)
    /** A ship move has a signed unit delta and a separate physical boat count, not a grade. */
    data class ShipMove(val kind: Int, val units: Int, val boats: Int) {
        init { require(kind in 0..65535 && units in -128..127 && boats in 0..65535) }
    }
    data class TroopMove(val kind: Int, val grade: Int, val units: Int) {
        init { require(kind in 0..65535 && grade in 0..255 && units in -32768..32767) }
    }
    data class ShipStock(val kind: Int, val units: Int, val boats: Int) {
        init { require(kind in 0..65535 && units in 0..255 && boats in 0..65535) }
    }
    data class TroopStock(val kind: Int, val grade: Int, val units: Int) {
        init { require(kind in 0..65535 && grade in 0..255 && units in 0..65535) }
    }
    data class Moves(val ships: List<ShipMove>, val troops: List<TroopMove>) {
        init { require(ships.size <= 99 && troops.size <= 24) }
    }
    data class Inventory(val ships: List<ShipStock>, val troops: List<TroopStock>) {
        init { require(ships.size <= 99 && troops.size <= 24) }
    }
    data class Reorganization(val header: Header, val mode: Int, val base: Int, val outfit: Int,
        val kind: Int, val moves: Moves, val maxTroops: Int, val maxCrew: Int, val supplies: Int) {
        init { require(mode in 0..255 && kind in 0..255) }
    }
    // Assignment has no mode field: PCP follows characterId directly.
    data class Assignment(val header: Header, val base: Int, val outfit: Int, val kind: Int, val moves: Moves,
        val moveSupplies: Int, val resultBase: Inventory, val resultBaseSupplies: Int,
        val resultOutfit: Inventory, val resultOutfitSupplies: Int) {
        init { require(kind in 0..255) }
    }

    private fun ByteBuffer.u8() = get().toInt() and 255
    private fun ByteBuffer.u16() = short.toInt() and 65535
    private fun <T> ByteBuffer.rows(max: Int, read: ByteBuffer.() -> T): List<T> {
        require(hasRemaining()) { "Missing logistics row count" }
        val count = u8()
        require(count <= max && remaining() >= count * 5) { "Invalid logistics row count" }
        return List(count) { read() }
    }
    private fun ByteBuffer.moves() = Moves(
        rows(99) { ShipMove(u16(), get().toInt(), u16()) },
        rows(24) { TroopMove(u16(), u8(), short.toInt()) })
    private fun ByteBuffer.inventory() = Inventory(
        rows(99) { ShipStock(u16(), u8(), u16()) },
        rows(24) { TroopStock(u16(), u8(), u16()) })
    private fun input(message: ByteArray, opcode: Int, minimum: Int): ByteBuffer {
        require(message.size >= minimum) { "Truncated logistics command" }
        return ByteBuffer.wrap(message).also { require(it.u16() == opcode) }
    }
    fun reorganization(message: ByteArray): Reorganization {
        val input = input(message, 0x0c02, 42)
        val time = input.int; val characterId = input.int; val mode = input.u8()
        val header = Header(time, characterId, input.int, input.int)
        val base = input.int; val outfit = input.int; val kind = input.u8()
        val moves = input.moves()
        require(input.remaining() == 12) { "Invalid reorganization tail" }
        return Reorganization(header, mode, base, outfit, kind, moves, input.int, input.int, input.int)
    }
    fun assignment(message: ByteArray): Assignment {
        val input = input(message, 0x0c0b, 45)
        val header = Header(input.int, input.int, input.int, input.int)
        val base = input.int; val outfit = input.int; val kind = input.u8()
        val moves = input.moves()
        require(input.remaining() >= 4) { "Missing supply delta" }
        val moveSupplies = input.int; val resultBase = input.inventory()
        require(input.remaining() >= 4) { "Missing base supplies" }
        val resultBaseSupplies = input.int; val resultOutfit = input.inventory()
        require(input.remaining() == 4) { "Invalid assignment tail" }
        return Assignment(header, base, outfit, kind, moves, moveSupplies, resultBase,
            resultBaseSupplies, resultOutfit, input.int)
    }

    private fun ByteBuffer.header(value: Header) {
        putInt(value.time).putInt(value.characterId).putInt(value.pcp).putInt(value.mcp)
    }
    private fun ByteBuffer.row(kind: Int, middle: Int, last: Int) {
        putShort(kind.toShort()).put(middle.toByte()).putShort(last.toShort())
    }
    private fun ByteBuffer.moves(value: Moves) {
        require(value.ships.size <= 99 && value.troops.size <= 24)
        put(value.ships.size.toByte()); value.ships.forEach { row(it.kind,it.units,it.boats) }
        put(value.troops.size.toByte()); value.troops.forEach { row(it.kind,it.grade,it.units) }
    }
    private fun ByteBuffer.inventory(value: Inventory) {
        require(value.ships.size <= 99 && value.troops.size <= 24)
        put(value.ships.size.toByte()); value.ships.forEach { row(it.kind,it.units,it.boats) }
        put(value.troops.size.toByte()); value.troops.forEach { row(it.kind,it.grade,it.units) }
    }
    /** Bare opcode + native payload, without session framing/result status. */
    fun encode(value: Reorganization): ByteArray {
        val out = ByteBuffer.allocate(42 + 5 * (value.moves.ships.size + value.moves.troops.size))
        out.putShort(0x0c02).putInt(value.header.time).putInt(value.header.characterId).put(value.mode.toByte())
            .putInt(value.header.pcp).putInt(value.header.mcp).putInt(value.base).putInt(value.outfit).put(value.kind.toByte())
        out.moves(value.moves)
        out.putInt(value.maxTroops).putInt(value.maxCrew).putInt(value.supplies)
        return out.array()
    }
    fun encode(value: Assignment): ByteArray {
        val rows = value.moves.ships.size + value.moves.troops.size + value.resultBase.ships.size +
            value.resultBase.troops.size + value.resultOutfit.ships.size + value.resultOutfit.troops.size
        val out = ByteBuffer.allocate(45 + rows * 5)
        out.putShort(0x0c0b); out.header(value.header)
        out.putInt(value.base).putInt(value.outfit).put(value.kind.toByte()); out.moves(value.moves)
        out.putInt(value.moveSupplies); out.inventory(value.resultBase)
        out.putInt(value.resultBaseSupplies); out.inventory(value.resultOutfit)
        out.putInt(value.resultOutfitSupplies)
        return out.array()
    }
}
