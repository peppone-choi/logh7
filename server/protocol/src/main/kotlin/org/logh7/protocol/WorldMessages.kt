package org.logh7.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** CD readers: 00413050, 004134e0, 004142e0. No native struct padding on the wire. */
object WorldMessages {
    data class Base(val id: Int, val grid: Int, val model: Int, val kind: Int, val name: String,
        val physicalClass: Int, val radius: Float, val cycle: Int, val direction: Int, val angle: Float, val diameter: Float)
    private fun body(write: DataOutputStream.() -> Unit) = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use(write)
    }.toByteArray()
    private fun DataOutputStream.string(value: String) {
        require(value.length <= 12 && '\u0000' !in value)
        writeByte(value.length + 1); (value + '\u0000').forEach { writeChar(it.code) }
    }
    fun types(types: List<Triple<Int, Int, Int>>) = body {
        require(types.size in 1..100)
        writeByte(types.size)
        types.forEach { (kind, type, star) ->
            require(kind in 0..255 && type in 0..255 && star in 0..255)
            writeByte(kind); writeByte(type); writeByte(star)
        }
    }
    fun grid(cells: List<Int>): ByteArray {
        require(cells.size == 5000 && cells.all { it in 0..99 })
        val packed = body {
            var at = 0
            while (at < cells.size) {
                var count = 1
                while (at + count < cells.size && count < 255 && cells[at + count] == cells[at]) count++
                writeByte(count); writeByte(cells[at]); at += count
            }
        }
        require(packed.size in 2..5000)
        return body { writeByte(100); writeByte(50); writeShort(packed.size); write(packed) }
    }
    fun bases(bases: List<Base>) = body {
        require(bases.size <= 350)
        writeShort(bases.size)
        bases.forEach {
            writeInt(it.id); writeShort(it.grid); writeShort(it.model); writeShort(it.kind); string(it.name)
            writeByte(it.physicalClass); writeFloat(it.radius); writeInt(it.cycle); writeByte(it.direction)
            writeFloat(it.angle); writeFloat(it.diameter)
        }
    }
    /** 0040f9f0: card kind, command list, three raw bit-mask fields. Only warp is exposed. */
    fun captainCommands() = body {
        writeShort(261)
        for (card in 0..260) {
            writeShort(card)
            val captain = card == 59 || card == 195
            writeByte(if (captain) 1 else 0)
            if (captain) {
                writeShort(43)
                write(byteArrayOf(-1, -1, 31)); write(byteArrayOf(-1, 63)); writeByte(63)
            }
        }
    }
    /** The client indexes the card catalogue by card kind, rather than searching a sparse list. */
    fun cards() = body {
        writeShort(261)
        for (card in 0..260) {
            val power = when (card) { 59 -> 2; 195 -> 3; else -> 0 }
            writeShort(card); writeByte(if (power == 0) 0 else 1)
            writeByte(power); writeByte(power); writeByte(0)
            writeShort(0); writeShort(0); writeByte(0); writeByte(20); writeByte(0)
            writeShort(0); writeByte(0); writeShort(0)
            writeByte(if (power == 0) 0 else 1)
            if (power != 0) writeShort(43)
        }
    }
    fun powerDirection() = ByteArray(1370).also {
        val out = java.nio.ByteBuffer.wrap(it)
        repeat(11) { out.putFloat(1f) }
        out.put(3).put(3) // Strategy warp radii; local navigation policy.
        repeat(4) { out.putFloat(3f) }
    }
    /** 004109a0. Local ship-kind 0 profile; original ship statistics remain unverified. */
    fun ships() = body {
        writeByte(1)
        writeShort(0); writeByte(0); writeByte(0); writeShort(0); writeShort(0)
        string("Local ship")
        writeShort(0); writeFloat(10f)
        repeat(6) { writeShort(100) }
        writeInt(1000); writeInt(1000)
        repeat(13) { writeShort(100) }
        writeInt(1000); writeInt(1000)
        repeat(5) { writeShort(100) }
        writeByte(0); writeShort(0); writeByte(0); writeByte(0); writeShort(0)
        writeByte(0); writeByte(0); writeShort(0); writeByte(0); writeShort(0); writeByte(0)
        repeat(6) { writeShort(0) }
    }
}
