package org.logh7.protocol

import java.nio.ByteBuffer

/** Native search34 / 0055f1f0: count:u16, (id:u32,kind:u8,destroyed:u16). Author: 최병호. */
object SupplementDirectoryMessages {
    data class Query(val character: Int,val kind: Int,val raw: ByteArray)
    data class Unit(val id: Int,val kind: Int,val destroyed: Int)
    fun matches(bytes: ByteArray)=bytes.size==31 && ByteBuffer.wrap(bytes).getShort(6).toInt()==34
    fun query(bytes: ByteArray): Query {
        require(matches(bytes));val b=ByteBuffer.wrap(bytes)
        require(b.short.toInt()==0x1200 && b.getShort(8).toInt()==0 &&
            (10..22).all {bytes[it]==0.toByte()} && (25..30).all {bytes[it]==0.toByte()})
        val character=b.getInt(2);val kind=b.getShort(23).toInt() and 65535
        require(character>0 && kind in 0..199)
        return Query(character,kind,bytes.copyOfRange(2,bytes.size))
    }
    fun response(q: Query,units: List<Unit>): List<ByteArray> {
        require(units.size<=600 && units.map {it.id}.distinct().size==units.size)
        val b=ByteBuffer.allocate(2+7*units.size).putShort(units.size.toShort())
        units.forEach {require(it.id>0 && it.kind==q.kind && it.destroyed in 0..65535)
            b.putInt(it.id).put(it.kind.toByte()).putShort(it.destroyed.toShort())}
        return listOf(LoginMessages.sessionMessage(0x1200,q.raw),LoginMessages.sessionMessage(0x1207,b.array()),
            LoginMessages.sessionMessage(0x1201,byteArrayOf()))
    }
}
