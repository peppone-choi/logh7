package org.logh7.protocol

import java.nio.ByteBuffer

/** Native 00551a80 / 00553fc0, without C++ alignment padding. Author: 최병호. */
object LogisticsMessages {
    // Keep unverified command metadata opaque. None of these values authorizes a stock change.
    data class Header(val first: Int, val second: Int, val third: Int, val fourth: Int)
    data class Stack(val kind: Int, val level: Int, val quantity: Int) {
        init { require(kind in 0..65535 && level in 0..255 && quantity in 0..65535) }
    }
    data class Inventory(val ships: List<Stack>, val troops: List<Stack>)
    data class Reorganization(val header: Header, val phase: Int, val base: Int, val outfit: Int,
        val category: Int, val moves: Inventory, val tailFirst: Int, val tailSecond: Int, val tailThird: Int)
    data class Assignment(val header: Header, val phase: Int, val base: Int, val category: Int, val moves: Inventory,
        val baseMetadata: Int, val resultBase: Inventory, val outfit: Int, val resultOutfit: Inventory,
        val tail: Int)

    private fun ByteBuffer.u8() = get().toInt() and 255
    private fun ByteBuffer.u16() = short.toInt() and 65535
    private fun ByteBuffer.stacks(max: Int): List<Stack> {
        require(hasRemaining()) { "Missing logistics stack count" }
        val count = u8()
        require(count <= max && remaining() >= count * 5) { "Invalid logistics stack count" }
        return List(count) { Stack(u16(), u8(), u16()) }
    }
    private fun ByteBuffer.inventory() = Inventory(stacks(99), stacks(24))
    private fun ByteBuffer.headerWithPhase(): Pair<Header, Int> {
        val first = int; val second = int; val phase = u8()
        return Header(first, second, int, int) to phase
    }
    private fun input(message: ByteArray, opcode: Int, minimum: Int): ByteBuffer {
        require(message.size >= minimum) { "Truncated logistics command" }
        return ByteBuffer.wrap(message).also { require(it.u16() == opcode) }
    }
    fun reorganization(message: ByteArray): Reorganization {
        val input = input(message, 0x0c02, 42)
        val (header, phase) = input.headerWithPhase()
        val base = input.int; val outfit = input.int; val category = input.u8()
        val moves = input.inventory()
        require(input.remaining() == 12) { "Invalid reorganization tail" }
        return Reorganization(header, phase, base, outfit, category, moves, input.int, input.int, input.int)
    }
    fun assignment(message: ByteArray): Assignment {
        val input = input(message, 0x0c0b, 45)
        val first = input.int; val second = input.int; val phase = input.int
        val header = Header(first, second, input.int, input.int)
        val base = input.int; val category = input.u8()
        val moves = input.inventory()
        require(input.remaining() >= 4) { "Missing base metadata" }
        val baseMetadata = input.int; val resultBase = input.inventory()
        require(input.remaining() >= 4) { "Missing outfit" }
        val outfit = input.int; val resultOutfit = input.inventory()
        require(input.remaining() == 4) { "Invalid assignment tail" }
        return Assignment(header, phase, base, category, moves, baseMetadata, resultBase, outfit, resultOutfit, input.int)
    }
}
