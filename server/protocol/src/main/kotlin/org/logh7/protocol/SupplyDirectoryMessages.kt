package org.logh7.protocol

import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Native search14 / 00561c80: u16 count, supplies/free-room then simple outfit. Author: Peppone Choi. */
object SupplyDirectoryMessages {
    data class Query(val character: Int, val grid: Int, val raw: ByteArray)
    data class Outfit(val id: Int, val kind: Int, val number: Int, val supplies: Int,
        val maximum: Int, val base: WorldMessages.Base)
    fun matches(message: ByteArray) = message.size == 31 && ByteBuffer.wrap(message).getShort(6).toInt() == 14
    fun query(message: ByteArray): Query {
        require(matches(message))
        val b = ByteBuffer.wrap(message)
        require(b.short.toInt() == 0x1200 && b.getShort(8).toInt() == 124)
        require((10..18).all { message[it] == 0.toByte() } && (23..30).all { message[it] == 0.toByte() })
        val character = b.getInt(2); val grid = b.getInt(19)
        require(character > 0 && grid in 0..4999)
        return Query(character, grid, message.copyOfRange(2, message.size))
    }
    fun response(query: Query, outfits: List<Outfit>): List<ByteArray> {
        require(outfits.size <= 300 && outfits.distinctBy { it.id }.size == outfits.size)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(outfits.size)
            for (outfit in outfits) {
                require(outfit.id > 0 && outfit.kind in 0..3 && outfit.number in 0..255 &&
                    outfit.supplies in 0..outfit.maximum && outfit.base.grid == query.grid)
                out.writeInt(outfit.supplies); out.writeInt(outfit.maximum - outfit.supplies)
                out.writeInt(outfit.id); out.writeByte(outfit.number); out.writeByte(outfit.kind)
                out.write(WorldMessages.simpleBases(listOf(outfit.base)))
            }
        }
        return listOf(LoginMessages.sessionMessage(0x1200, query.raw),
            LoginMessages.sessionMessage(0x120b, bytes.toByteArray()), LoginMessages.sessionMessage(0x1201, byteArrayOf()))
    }
}
