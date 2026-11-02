package org.logh7.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** Native 0040c560/0041bbd0 and 0040c780/0041c5f0; no C++ padding. Author: Peppone Choi. */
object OutfitMessages {
    data class Definition(val id: Int, val kind: Int, val power: Int, val camp: Int, val number: Int,
        val achievement: Int = 0, val strategy: Int = 0, val practice: List<Int> = List(10) { 0 })
    data class Entry(val id: Int, val kind: Int, val power: Int, val camp: Int, val number: Int, val supplies: Int)
    data class GridQuery(val grid: Int, val power: Int, val camp: Int)
    fun query(message: ByteArray): List<Int> {
        require(message.size >= 3)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() == 0x032a)
        val count = input.get().toInt() and 255
        require(count <= 100 && input.remaining() == count*4)
        return List(count) { input.int.also { require(it >= 0) } }
    }
    fun gridQuery(message: ByteArray): GridQuery {
        require(message.size == 8)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() == 0x032c)
        return GridQuery(input.int,input.get().toInt() and 255,input.get().toInt() and 255).also {
            require(it.grid in -1..4999 && (it.power in 0..4 || it.power == 255) && (it.camp in 0..1 || it.camp == 255))
        }
    }
    private fun DataOutputStream.identity(id: Int, kind: Int, power: Int, camp: Int, number: Int) {
        require(id > 0 && kind in 0..4 && power in 0..4 && camp in 0..1 && number in 0..255)
        writeInt(id); writeByte(kind); writeByte(power); writeByte(camp); writeByte(number)
    }
    private fun body(write: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream(); DataOutputStream(bytes).use { it.write() }; return bytes.toByteArray()
    }
    fun information(values: List<Definition>): ByteArray = body {
        require(values.size <= 100 && values.map { it.id }.distinct().size == values.size)
        writeByte(values.size)
        for (value in values) {
            identity(value.id,value.kind,value.power,value.camp,value.number)
            require(value.achievement in 0..65535 && value.strategy >= 0 && value.practice.size == 10 && value.practice.all { it in 0..255 })
            writeShort(value.achievement); writeInt(value.strategy); value.practice.forEach(::writeByte)
        }
    }
    fun grid(values: List<Entry>): ByteArray = body {
        require(values.size <= 300 && values.map { it.id }.distinct().size == values.size)
        writeShort(values.size)
        for (value in values) {
            identity(value.id,value.kind,value.power,value.camp,value.number)
            require(value.supplies >= 0); writeInt(value.supplies)
        }
    }
}
