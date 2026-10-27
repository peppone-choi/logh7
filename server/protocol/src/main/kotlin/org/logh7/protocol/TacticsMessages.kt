package org.logh7.protocol

import java.nio.ByteBuffer

/** 0048cb80: u8 state followed by u32 grid, with no native padding. Author: 최병호. */
object TacticsMessages {
    data class Base(val id: Int,val x: Float,val y: Float,val z: Float)
    data class Unit(val id: Int,val kind: Int,val outfit: Int,val character: Int,val supplies: Int,
        val cruise: Float,val destroyed: Int,val x: Float,val y: Float,val z: Float,val direction: Float)
    fun notify(grid: Int, active: Boolean): ByteArray {
        require(grid in 0..4999)
        return LoginMessages.sessionMessage(0x0f1f,ByteBuffer.allocate(5).put(if(active)1 else 0).putInt(grid).array())
    }
    fun queryIds(message: ByteArray, opcode: Int, byteCount: Boolean = false): List<Int> {
        val input=ByteBuffer.wrap(message)
        require(input.remaining()>=if(byteCount)3 else 4)
        require(input.short.toInt() and 65535==opcode)
        val count=if(byteCount)input.get().toInt() and 255 else input.short.toInt() and 65535
        require(count<=if(byteCount)4 else 600)
        require(input.remaining()==count*4)
        return List(count){input.int}.also {require(it.all {id->id>0} && it.distinct().size==it.size)}
    }
    fun definitions(grid: Int, units: List<Unit>): ByteArray {
        require(grid in 0..4999 && units.size<=600 && units.map {it.id}.distinct().size==units.size)
        val out=ByteBuffer.allocate(2+42*units.size).putShort(units.size.toShort())
        units.forEach {u->
            require(u.id>0 && u.kind in 0..199 && u.outfit>=0 && u.supplies>=0 && u.destroyed in 0..65535)
            out.putInt(u.id).putShort(u.kind.toShort()).put(5).putInt(grid).putInt(u.outfit).putInt(0).put(0)
                .putInt(0).put(100).put(0).putShort(0).putShort(u.destroyed.toShort()).putInt(u.supplies).putInt(0).putFloat(u.cruise)
        }
        return LoginMessages.sessionMessage(0x0325,out.array())
    }
    fun characters(grid: Int, ids: List<Int>): ByteArray {
        require(grid in 0..4999 && ids.size<=600 && ids.all {it>0} && ids.distinct().size==ids.size)
        val out=ByteBuffer.allocate(4+4*ids.size).putShort(grid.toShort()).putShort(ids.size.toShort())
        ids.forEach(out::putInt)
        return LoginMessages.sessionMessage(0x0337,out.array())
    }
    private fun validate(units: List<Unit>) {
        require(units.size<=600 && units.map {it.id}.distinct().size==units.size)
        require(units.all {it.id>0 && it.character>=0 && listOf(it.x,it.y,it.z,it.direction).all(Float::isFinite)})
    }
    fun states(units: List<Unit>): ByteArray {
        validate(units)
        val out=ByteBuffer.allocate(2+47*units.size).putShort(units.size.toShort())
        units.forEach {u->out.putInt(u.id).put(100).put(0).putInt(u.character)
            .putFloat(u.x).putFloat(u.y).putFloat(u.z).putFloat(u.direction).putInt(u.id)
            .putFloat(u.x).putFloat(u.y).putFloat(u.z).putFloat(u.direction).put(1)}
        return LoginMessages.sessionMessage(0x033b,out.array())
    }
    fun positions(units: List<Unit>): ByteArray {
        validate(units)
        val out=ByteBuffer.allocate(2+20*units.size).putShort(units.size.toShort())
        units.forEach {u->out.putInt(u.id).putFloat(u.x).putFloat(u.y).putFloat(u.z).putFloat(u.direction)}
        return LoginMessages.sessionMessage(0x0349,out.array())
    }
    fun basePositions(bases: List<Base>): ByteArray {
        require(bases.size<=4 && bases.map {it.id}.distinct().size==bases.size)
        require(bases.all {it.id>0 && listOf(it.x,it.y,it.z).all(Float::isFinite)})
        val out=ByteBuffer.allocate(1+16*bases.size).put(bases.size.toByte())
        bases.forEach {out.putInt(it.id).putFloat(it.x).putFloat(it.y).putFloat(it.z)}
        return LoginMessages.sessionMessage(0x034b,out.array())
    }
}
